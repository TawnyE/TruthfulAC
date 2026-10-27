package ret.tawny.truthful.checks.impl.packet.badpacket;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.action.PlayerBlockPlacePacketWrapper;

/**
 * BadPacket5 - bad block places. Null face or a cursor hit-vector outside
 * the block (0-1). Vanilla always clicks inside the block, period.
 */
@CheckData(order = '5', type = CheckType.BAD_PACKET)
public final class BadPacket5 extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isServerFrozen()) return;

        PlayerBlockPlacePacketWrapper place = null;
        try {
            place = data.getCurrentBlockPlacement();
        } catch (Throwable ignored) {
            return;
        }
        if (place == null) return;

        boolean bad = false;
        try {
            if (place.getBlockFace() == null) {
                bad = true;
            } else {
                Vector hit = place.getHitVec();
                // Vanilla clamps cursor into [0,1] but legit interacts on
                // interactable blocks (chests, shulkers, dropper GUIs) can land
                // the cursor on the block EDGE which floats slightly past 1.0 /
                // under 0.0. Only flag coordinates nowhere near the block.
                if (hit == null || !Double.isFinite(hit.getX()) || !Double.isFinite(hit.getY()) || !Double.isFinite(hit.getZ())
                        || hit.getX() < -0.15D || hit.getX() > 1.15D
                        || hit.getY() < -0.15D || hit.getY() > 1.15D
                        || hit.getZ() < -0.15D || hit.getZ() > 1.15D) {
                    bad = true;
                }
            }
        } catch (Throwable ignored) {
            return;
        }

        if (bad) {
            if (buffer.increase(player, 1.5) > 3.0) {
                flag(data, "Invalid block place");
                buffer.reset(player, 1.5);
            }
        } else {
            buffer.decrease(player, 0.5);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}
