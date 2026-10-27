package ret.tawny.truthful.checks.impl.world.scaffold;

import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.world.BlockPropertyRegistry;
import ret.tawny.truthful.wrapper.impl.client.action.PlayerBlockPlacePacketWrapper;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ScaffoldRotation(A) - placement line-of-sight. A vanilla client can only
 * commit a placement its crosshair is really on, so the view ray has to
 * intersect the placed cell's volume expanded by 0.10. The click can land
 * anywhere between two movement ticks, so both the current and the previous
 * rotation are tried from both the current and the one-tick-back eye
 * position. Rotation-less and silent-aim cheats keep the camera elsewhere
 * while blocks land under them and miss every candidate; godbridge and
 * telly bridging pass because their steep rays genuinely hit the cell.
 */
@CheckData(order = 'A', type = CheckType.SCAFFOLD)
public final class ScaffoldRotation extends Check {

    private static final double EXPANSION = 0.10D;
    private static final double FLAG_BUFFER = 6.0D;

    private final CheckBuffer buffer = new CheckBuffer(FLAG_BUFFER);

    private static final class State {
        int missStreak;
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
        final BlockFace face = place.getBlockFace();
        final int targetX = clicked.getX() + face.getModX();
        final int targetY = clicked.getY() + face.getModY();
        final int targetZ = clicked.getZ() + face.getModZ();

        if (!data.getWorldCache().isChunkLoaded(targetX >> 4, targetZ >> 4)) {
            buffer.decrease(player, 0.05D);
            return;
        }

        final boolean ambiguous = isReplaceable(
                data.getWorldCache().getBlockId(clicked.getX(), clicked.getY(), clicked.getZ()));

        boolean hit = looksAtCell(data, targetX, targetY, targetZ);
        if (!hit && ambiguous) {
            hit = looksAtCell(data, clicked.getX(), clicked.getY(), clicked.getZ());
        }

        final State st = states.computeIfAbsent(player.getUniqueId(), k -> new State());
        if (hit) {
            st.missStreak = 0;
            buffer.decrease(player, 0.25D);
        } else {
            st.missStreak++;
            if (buffer.increase(player, 1.0D) > FLAG_BUFFER) {
                flag(data, String.format("los miss target=(%d,%d,%d) yaw=%.1f pitch=%.1f streak=%d",
                        targetX, targetY, targetZ, data.getYaw(), data.getPitch(), st.missStreak));
                buffer.reset(player, 3.0D);
                st.missStreak = 0;
            }
        }
    }

    private boolean looksAtCell(final PlayerData data, final int x, final int y, final int z) {
        final double minX = x - EXPANSION;
        final double minY = y - EXPANSION;
        final double minZ = z - EXPANSION;
        final double maxX = x + 1.0D + EXPANSION;
        final double maxY = y + 1.0D + EXPANSION;
        final double maxZ = z + 1.0D + EXPANSION;

        final double eyeX = data.getX();
        final double eyeY = data.getY() + data.getEyeHeightScaled();
        final double eyeZ = data.getZ();
        final double backX = eyeX - data.getDeltaX();
        final double backY = eyeY - data.getDeltaY();
        final double backZ = eyeZ - data.getDeltaZ();

        if (insideBox(eyeX, eyeY, eyeZ, minX, minY, minZ, maxX, maxY, maxZ)
                || insideBox(backX, backY, backZ, minX, minY, minZ, maxX, maxY, maxZ)) {
            return true;
        }

        return rayFromRotation(eyeX, eyeY, eyeZ, data.getYaw(), data.getPitch(), minX, minY, minZ, maxX, maxY, maxZ)
                || rayFromRotation(eyeX, eyeY, eyeZ, data.getLastYaw(), data.getLastPitch(), minX, minY, minZ, maxX, maxY, maxZ)
                || rayFromRotation(backX, backY, backZ, data.getYaw(), data.getPitch(), minX, minY, minZ, maxX, maxY, maxZ)
                || rayFromRotation(backX, backY, backZ, data.getLastYaw(), data.getLastPitch(), minX, minY, minZ, maxX, maxY, maxZ);
    }

    private boolean rayFromRotation(final double ox, final double oy, final double oz,
                                     final float yaw, final float pitch,
                                     final double minX, final double minY, final double minZ,
                                     final double maxX, final double maxY, final double maxZ) {
        final double yawRad = Math.toRadians(yaw);
        final double pitchRad = Math.toRadians(pitch);
        final double cosPitch = Math.cos(pitchRad);
        return intersectsBox(ox, oy, oz,
                -Math.sin(yawRad) * cosPitch,
                -Math.sin(pitchRad),
                Math.cos(yawRad) * cosPitch,
                minX, minY, minZ, maxX, maxY, maxZ);
    }

    private boolean intersectsBox(final double ox, final double oy, final double oz,
                                  final double dx, final double dy, final double dz,
                                  final double minX, final double minY, final double minZ,
                                  final double maxX, final double maxY, final double maxZ) {
        double tMin = 0.0D;
        double tMax = Double.MAX_VALUE;

        if (Math.abs(dx) < 1.0E-9D) {
            if (ox < minX || ox > maxX) return false;
        } else {
            final double inv = 1.0D / dx;
            double t1 = (minX - ox) * inv;
            double t2 = (maxX - ox) * inv;
            if (t1 > t2) {
                final double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return false;
        }

        if (Math.abs(dy) < 1.0E-9D) {
            if (oy < minY || oy > maxY) return false;
        } else {
            final double inv = 1.0D / dy;
            double t1 = (minY - oy) * inv;
            double t2 = (maxY - oy) * inv;
            if (t1 > t2) {
                final double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return false;
        }

        if (Math.abs(dz) < 1.0E-9D) {
            if (oz < minZ || oz > maxZ) return false;
        } else {
            final double inv = 1.0D / dz;
            double t1 = (minZ - oz) * inv;
            double t2 = (maxZ - oz) * inv;
            if (t1 > t2) {
                final double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return false;
        }

        return true;
    }

    private static boolean insideBox(final double x, final double y, final double z,
                                     final double minX, final double minY, final double minZ,
                                     final double maxX, final double maxY, final double maxZ) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    private static boolean isReplaceable(final int globalId) {
        if (globalId == 0) return true;
        if (BlockPropertyRegistry.isLiquid(globalId)) return true;
        final String name = BlockPropertyRegistry.getName(globalId);
        return name.equals("SNOW") || name.equals("GRASS") || name.equals("SHORT_GRASS")
                || name.equals("TALL_GRASS") || name.equals("FERN") || name.equals("LARGE_FERN")
                || name.equals("DEAD_BUSH") || name.equals("FIRE") || name.endsWith("_AIR")
                || name.endsWith("_FIRE");
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
        buffer.remove(event.getPlayer());
    }
}
