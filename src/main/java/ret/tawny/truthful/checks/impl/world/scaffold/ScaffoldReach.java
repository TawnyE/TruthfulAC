package ret.tawny.truthful.checks.impl.world.scaffold;

import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.action.PlayerBlockPlacePacketWrapper;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ScaffoldReach(B) - placement distance. The placement is judged on the
 * movement tick it lands on, so the distance is the best (nearest) of the
 * current and the one-tick-back eye to the centre of the clicked face,
 * compared against the vanilla block interaction range plus 0.25 of jitter.
 * No ping scaling and no maturity windows: only clear, repeated overreach
 * builds the buffer. Flight, vehicles and fresh teleports are skipped
 * entirely.
 */
@CheckData(order = 'B', type = CheckType.SCAFFOLD)
public final class ScaffoldReach extends Check {

    private static final double JITTER = 0.25D;
    private static final double FLAG_BUFFER = 4.0D;

    private final CheckBuffer buffer = new CheckBuffer(FLAG_BUFFER);

    private static final class State {
        double recentMax;
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    @Override
    public void handleRelMove(final RelMovePacketWrapper wrapper) {
        final PlayerData data = wrapper.getPlayerData();
        if (data == null) return;

        final Player player = data.getPlayer();
        final PlayerBlockPlacePacketWrapper place = data.getCurrentBlockPlacement();
        if (place == null || place.getBlockPosition() == null || place.getBlockFace() == null) {
            buffer.decrease(player, 0.05D);
            return;
        }

        if (data.isAllowFlight() || data.isFlying() || data.isInsideVehicle()
                || data.getTicksSinceTeleport() < 5) {
            buffer.decrease(player, 0.05D);
            return;
        }

        final Vector3i clicked = place.getBlockPosition();
        if (!data.getWorldCache().isChunkLoaded(clicked.getX() >> 4, clicked.getZ() >> 4)) {
            buffer.decrease(player, 0.05D);
            return;
        }

        final BlockFace face = place.getBlockFace();
        final double faceX = clicked.getX() + 0.5D + face.getModX() * 0.5D;
        final double faceY = clicked.getY() + 0.5D + face.getModY() * 0.5D;
        final double faceZ = clicked.getZ() + 0.5D + face.getModZ() * 0.5D;

        final double eyeX = data.getX();
        final double eyeY = data.getY() + data.getEyeHeightScaled();
        final double eyeZ = data.getZ();

        final double dx = faceX - eyeX;
        final double dy = faceY - eyeY;
        final double dz = faceZ - eyeZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        final double bdx = faceX - (eyeX - data.getDeltaX());
        final double bdy = faceY - (eyeY - data.getDeltaY());
        final double bdz = faceZ - (eyeZ - data.getDeltaZ());
        final double backDist = Math.sqrt(bdx * bdx + bdy * bdy + bdz * bdz);
        if (backDist < dist) dist = backDist;

        final double max = Truthful.getInstance().getVersionManager().getAdapter()
                .getBlockInteractionRange(player) + JITTER;

        final State st = states.computeIfAbsent(player.getUniqueId(), k -> new State());
        if (dist > max) {
            st.recentMax = Math.max(st.recentMax, dist);
            if (buffer.increase(player, 1.0D) > FLAG_BUFFER) {
                flag(data, String.format("dist=%.3f max=%.3f recent=%.3f", dist, max, st.recentMax));
                buffer.reset(player, 2.0D);
                st.recentMax = 0.0D;
            }
            return;
        }

        st.recentMax = Math.max(0.0D, st.recentMax - 0.05D);
        buffer.decrease(player, 0.5D);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
        buffer.remove(event.getPlayer());
    }
}
