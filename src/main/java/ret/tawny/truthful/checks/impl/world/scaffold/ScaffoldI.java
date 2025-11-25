package ret.tawny.truthful.checks.impl.world.scaffold;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.world.BlockUtils;
import ret.tawny.truthful.wrapper.impl.client.action.PlayerBlockPlacePacketWrapper;

@CheckData(order = 'I', type = CheckType.SCAFFOLD)
@SuppressWarnings("unused")
public final class ScaffoldI extends Check {

    private final CheckBuffer buffer = new CheckBuffer(4.0);

    // Tolerance relaxed to 0.05 to account for network desync/rotation smoothing
    private static final double TOLERANCE = 0.05;

    @Override
    public void handlePacketPlayerReceive(final PacketEvent event) {
        if (!event.getPacketType().equals(PacketType.Play.Client.BLOCK_PLACE)) return;

        final Player player = event.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isTeleportTick()) return;

        final PlayerBlockPlacePacketWrapper wrapper = new PlayerBlockPlacePacketWrapper(event);
        final BlockFace face = wrapper.getBlockFace();

        if (face == null || face == BlockFace.SELF) return;
        if (BlockUtils.isAbnormal(wrapper.getBlock().getType())) return;

        Location eyePos = player.getEyeLocation();
        Block targetBlock = wrapper.getBlock();

        // Check 1: Trace with Current Rotation
        boolean hitCurrent = rayTraceFace(eyePos, eyePos.getDirection(), targetBlock, face);

        // Check 2: Trace with Last Rotation (Lag compensation)
        Vector lastDir = getVectorForRotation(data.getLastPitch(), data.getLastYaw());
        boolean hitLast = rayTraceFace(eyePos, lastDir, targetBlock, face);

        if (!hitCurrent && !hitLast) {
            if (buffer.increase(player, 1.5) > 4.0) {
                flag(data, String.format("Raytrace Mismatch (Strict). Face: %s, P: %.1f",
                        face.name(), data.getPitch()));

                if (Truthful.getInstance().getConfiguration().isLagbacks()) {
                    player.teleport(data.getLastLocation());
                }
            }
        } else {
            buffer.decrease(player, 0.25);
        }
    }

    private boolean rayTraceFace(Location origin, Vector direction, Block block, BlockFace face) {
        double blockX = block.getX();
        double blockY = block.getY();
        double blockZ = block.getZ();

        double planeX = blockX;
        double planeY = blockY;
        double planeZ = blockZ;
        Vector normal;

        switch (face) {
            case UP:    planeY += 1.0; normal = new Vector(0, 1, 0); break;
            case DOWN:  normal = new Vector(0, -1, 0); break;
            case EAST:  planeX += 1.0; normal = new Vector(1, 0, 0); break;
            case WEST:  normal = new Vector(-1, 0, 0); break;
            case SOUTH: planeZ += 1.0; normal = new Vector(0, 0, 1); break;
            case NORTH: normal = new Vector(0, 0, -1); break;
            default: return true;
        }

        double denom = normal.dot(direction);
        if (Math.abs(denom) < 1.0E-6) return false;

        Vector planePoint = new Vector(planeX, planeY, planeZ);
        Vector diff = planePoint.subtract(origin.toVector());

        double t = diff.dot(normal) / denom;
        if (t < 0) return false; // Behind player
        if (t > 6.0) return false; // Too far

        Vector intersect = origin.toVector().add(direction.clone().multiply(t));

        return isInside(intersect, blockX, blockY, blockZ, face);
    }

    private boolean isInside(Vector point, double bx, double by, double bz, BlockFace face) {
        double x = point.getX();
        double y = point.getY();
        double z = point.getZ();

        double minX = bx - TOLERANCE; double maxX = bx + 1 + TOLERANCE;
        double minY = by - TOLERANCE; double maxY = by + 1 + TOLERANCE;
        double minZ = bz - TOLERANCE; double maxZ = bz + 1 + TOLERANCE;

        switch (face) {
            case UP:
            case DOWN:
                return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
            case NORTH:
            case SOUTH:
                return x >= minX && x <= maxX && y >= minY && y <= maxY;
            case WEST:
            case EAST:
                return y >= minY && y <= maxY && z >= minZ && z <= maxZ;
            default:
                return true;
        }
    }

    private Vector getVectorForRotation(float pitch, float yaw) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double x = -Math.sin(yawRad) * Math.cos(pitchRad);
        double y = -Math.sin(pitchRad);
        double z = Math.cos(yawRad) * Math.cos(pitchRad);
        return new Vector(x, y, z);
    }
}