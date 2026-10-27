package ret.tawny.truthful.checks.impl.packet.badpacket;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
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
 * BadPacketR - swaps slots right after hitting. Post-attack slot changes are
 * how auto-sword cheats shuffle weapons mid-combo.
 */
@CheckData(order = 'R', type = CheckType.BAD_PACKET)
public final class BadPacketR extends Check {

    private static final long POST_WINDOW_MS = 55L;

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

        if (type != PacketType.Play.Client.HELD_ITEM_CHANGE) return;
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isServerFrozen()) return;

        try {
            new WrapperPlayClientHeldItemChange(event);
        } catch (Throwable ignored) {
            return;
        }

        long sinceAttack = System.currentTimeMillis() - lastAttack.getOrDefault(player.getUniqueId(), 0L);
        if (sinceAttack < POST_WINDOW_MS) {
            if (buffer.increase(player, 1.0) > 5.0) {
                flag(data, "Slot swap after attack");
                buffer.reset(player, 2.5);
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
