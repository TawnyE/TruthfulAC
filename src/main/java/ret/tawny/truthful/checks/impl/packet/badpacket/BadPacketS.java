package ret.tawny.truthful.checks.impl.packet.badpacket;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
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
 * BadPacketS - interaction sequence drift (1.19+).
 *
 * Every block interaction (placement, dig start/finish, use-item) carries a
 * monotonically increasing sequence counter from the client. Vanilla never
 * repeats or skips a number. A drifting counter means fabricated packets.
 */
@CheckData(order = 'S', type = CheckType.BAD_PACKET)
public final class BadPacketS extends Check {

    private final CheckBuffer buffer = new CheckBuffer(8.0);
    private final Map<UUID, Integer> lastSequence = new ConcurrentHashMap<>();

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isServerFrozen()) return;

        Integer seq = tryGetSequence(event, event.getPacketType());
        if (seq == null) return; // packet types we don't sequence-check

        UUID id = player.getUniqueId();
        Integer last = lastSequence.get(id);

        // A login/respawn/teleport/vehicle transition can reset the client's
        // counter - resync the baseline there instead of judging.
        boolean resyncWindow = data.isExempt(ret.tawny.truthful.data.ExemptionType.JOIN)
                || data.isTeleportTick()
                || data.getTicksSinceTeleport() < 5
                || data.getTicksTracked() - data.getLastVehicleExitTick() < 5;

        // Vanilla strictly increments by exactly one. Both directions are
        // judged now: the old code only flagged seq <= last, so a client
        // fabricating monotonically increasing but SKIPPED sequences passed
        // (and then overwrote the baseline, discarding the evidence).
        if (last != null && !resyncWindow && seq != last + 1) {
            if (seq <= last) {
                if (buffer.increase(player, 1.5) > 4.0) {
                    flag(data, String.format("Bad interaction sequence (last=%d got=%d)", last, seq));
                    buffer.reset(player, 2.0);
                }
            } else {
                if (buffer.increase(player, 1.0) > 6.0) {
                    flag(data, String.format("Skipped interaction sequence (last=%d got=%d)", last, seq));
                    buffer.reset(player, 3.0);
                }
            }
        } else {
            buffer.decrease(player, 0.2);
        }
        lastSequence.put(id, seq);
    }

    private Integer tryGetSequence(PacketReceiveEvent event, PacketTypeCommon type) {
        try {
            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
                int seq = new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement(event).getSequence();
                return seq >= 0 ? seq : null;
            }
            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.PLAYER_DIGGING) {
                // Only the digging actions that carry a server-side sequence.
                var dig = new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging(event);
                var action = dig.getAction();
                if (action == com.github.retrooper.packetevents.protocol.player.DiggingAction.START_DIGGING
                        || action == com.github.retrooper.packetevents.protocol.player.DiggingAction.FINISHED_DIGGING) {
                    int seq = dig.getSequence();
                    return seq >= 0 ? seq : null;
                }
            }
            if (type == com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client.USE_ITEM) {
                int seq = new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem(event).getSequence();
                return seq >= 0 ? seq : null;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        lastSequence.remove(event.getPlayer().getUniqueId());
    }
}
