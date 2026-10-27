package ret.tawny.truthful.checks.impl.packet.badpacket;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.util.Vector3i;
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
 * BadPacket6 - bad dig sequence. Finishing a dig that never started, or
 * starting a new one while one is already going. Vanilla digs in order.
 */
@CheckData(order = '6', type = CheckType.BAD_PACKET)
public final class BadPacket6 extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private final Map<UUID, Long> diggingBlock = new ConcurrentHashMap<>();

    private static long packPos(Vector3i v) {
        return ((long)(v.getX() & 0x3FFFFFF) << 38)
                | ((long)(v.getZ() & 0x3FFFFFF) << 12)
                | ((long)(v.getY() & 0xFFF));
    }

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_DIGGING) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isServerFrozen()) return;

        DiggingAction action;
        Vector3i pos;
        try {
            WrapperPlayClientPlayerDigging dig = new WrapperPlayClientPlayerDigging(event);
            action = dig.getAction();
            pos = dig.getBlockPosition();
        } catch (Throwable ignored) {
            return;
        }
        if (action == null) return;

        UUID id = player.getUniqueId();
        Long current = diggingBlock.get(id);
        boolean bad = false;
        if (action == DiggingAction.START_DIGGING) {
            // Legit retarget: vanilla skips CANCELLED_DIGGING when the cursor moves
            // to a different block mid-swing in several versions; a new START on a
            // DIFFERENT block just replaces the pending dig. Only a re-START on the
            // SAME block with no finish is a broken sequence.
            bad = current != null && current == packPos(pos);
            diggingBlock.put(id, packPos(pos));
        } else if (action == DiggingAction.FINISHED_DIGGING) {
            bad = current == null;
            diggingBlock.remove(id);
        } else if (action == DiggingAction.CANCELLED_DIGGING) {
            diggingBlock.remove(id);
        }

        if (bad) {
            if (buffer.increase(player, 1.5) > 3.0) {
                flag(data, "Bad dig sequence");
                buffer.reset(player, 1.5);
            }
        } else {
            buffer.decrease(player, 0.25);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        diggingBlock.remove(event.getPlayer().getUniqueId());
    }
}
