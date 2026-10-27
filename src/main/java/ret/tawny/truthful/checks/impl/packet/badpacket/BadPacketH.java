package ret.tawny.truthful.checks.impl.packet.badpacket;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
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
 * BadPacketH - attack order all over the place. Hitting a different entity
 * every few ms isn't PvP, it's a multi-aura cycling targets.
 */
@CheckData(order = 'H', type = CheckType.BAD_PACKET)
public final class BadPacketH extends Check {

    private static final long BURST_WINDOW_MS = 50L;

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private final Map<UUID, AttackEntry> lastAttack = new ConcurrentHashMap<>();

    private static final class AttackEntry {
        final int entityId;
        final long time;

        AttackEntry(int entityId, long time) {
            this.entityId = entityId;
            this.time = time;
        }
    }

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;
        WrapperPlayClientInteractEntity wrapper;
        try {
            wrapper = new WrapperPlayClientInteractEntity(event);
        } catch (Throwable ignored) {
            return;
        }
        if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isServerFrozen()) return;

        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        AttackEntry prev = lastAttack.put(id, new AttackEntry(wrapper.getEntityId(), now));

        if (prev != null && prev.entityId != wrapper.getEntityId() && now - prev.time < BURST_WINDOW_MS) {
            if (buffer.increase(player, 1.5) > 4.0) {
                flag(data, "Attack target burst");
                buffer.reset(player, 2.0);
            }
        } else {
            buffer.decrease(player, 0.25);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        lastAttack.remove(event.getPlayer().getUniqueId());
    }
}
