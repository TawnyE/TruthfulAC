package ret.tawny.truthful.checks.impl.packet.badpacket;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BadPacketP - sneaks right after hitting, the looser window. Sprint toggles
 * are skipped (w-tapping is legit), this is the sneak version of what I
 * watches at tighter timing.
 */
@CheckData(order = 'P', type = CheckType.BAD_PACKET)
public final class BadPacketP extends Check {

    private static final long POST_WINDOW_MS = 80L;

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private final Map<UUID, Long> lastAttack = new ConcurrentHashMap<>();

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        Object type = event.getPacketType();
        if (!(event.getPlayer() instanceof Player player)) return;

        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity wrapper;
            try {
                wrapper = new WrapperPlayClientInteractEntity(event);
            } catch (Throwable ignored) {
                return;
            }
            if (wrapper.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                lastAttack.put(player.getUniqueId(), System.currentTimeMillis());
            }
            return;
        }

        if (type != PacketType.Play.Client.ENTITY_ACTION) return;
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isServerFrozen()) return;

        WrapperPlayClientEntityAction.Action action;
        try {
            action = new WrapperPlayClientEntityAction(event).getAction();
        } catch (Throwable ignored) {
            return;
        }
        if (action != WrapperPlayClientEntityAction.Action.START_SNEAKING) {
            buffer.decrease(player, 0.1);
            return;
        }

        long sinceAttack = System.currentTimeMillis() - lastAttack.getOrDefault(player.getUniqueId(), 0L);
        if (sinceAttack < POST_WINDOW_MS) {
            if (buffer.increase(player, 1.0) > 6.0) {
                flag(data, "Sneak after attack");
                buffer.reset(player, 3.0);
            }
        } else {
            buffer.decrease(player, 0.1);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        lastAttack.remove(event.getPlayer().getUniqueId());
    }
}
