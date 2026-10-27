package ret.tawny.truthful.checks.impl.movement.velocity;

import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.ExemptionType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.sync.VelocityQueue;
import ret.tawny.truthful.utils.world.BlockPropertyRegistry;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

/**
 * VelocityA - did they take the knockback they were sent? Compares actual
 * motion against the acked server velocity plus legal inputs. Notes on what
 * this deliberately does NOT model: knockback resistance (already baked into
 * the packet server-side, A=v*(1-r), netherite included), attacker sprint
 * bonus (attacker-side, already in the packet), shield blocks (blocked hits
 * send no/reduced velocity, so the early returns below catch them).
 */
@CheckData(order = 'A', type = CheckType.VELOCITY)
public final class VelocityA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0D);

    private static final double[][] INPUT_VECTORS = {
            {0.0, 0.0}, {0.0, 1.0}, {0.0, -1.0}, {1.0, 0.0}, {-1.0, 0.0},
            {1.0, 1.0}, {-1.0, 1.0}, {1.0, -1.0}, {-1.0, -1.0}
    };

    @Override
    public void handleRelMove(final RelMovePacketWrapper wrapper) {
        if (!wrapper.isPositionUpdate()) return;
        final PlayerData data = wrapper.getPlayerData();
        if (data == null) return;

        if (data.isServerFrozen() || data.isTeleportTick() || data.isMovementExempt()) {
            buffer.decrease(data.getPlayer(), 0.25D);
            return;
        }

        VelocityQueue queue = data.getVelocities();
        if (queue.isEmpty()) return;

        // Custom-ability launches (spear lunges, mace slams, wind charges) are
        // plugin-scripted, often multi-tick, and never match a single acked
        // vanilla knockback. Judging them here is pure false positives.
        // Gliding, riptide dashes and live explosions make knockback response
        // unjudgeable (glide physics, scripted dash, multi-stack accumulation
        // inside one tick). Decay, don't evaluate.
        // Only judge on the FIRST response tick after acknowledgment (Grim
        // judges the tick the KB should have been applied, not every tick
        // while velocity is in the queue). On later ticks the player's own
        // input has overwhelmed the KB signal and the deviation is just
        // normal movement, producing the PvP false-flag storm.
        if (!queue.hasAcknowledgedVelocity(1, false)) {
            buffer.decrease(data.getPlayer(), 0.25D);
            return;
        }

        final double actualX = data.getDeltaX();
        final double actualZ = data.getDeltaZ();
        final float yaw = data.getYaw();
        final boolean onGround = data.isServerGround();

        double minVectorDev = Double.MAX_VALUE;
        Vector receivedVelocity = queue.getAcknowledgedVelocityVector(1, false);
        double velXZ = Math.hypot(receivedVelocity.getX(), receivedVelocity.getZ());
        if (velXZ < 0.05D) {
            buffer.decrease(data.getPlayer(), 0.25D);
            return;
        }

        double friction = onGround ? computeGroundFriction(data) : 0.91D;
        double lastX = data.getLastDeltaX() * friction;
        double lastZ = data.getLastDeltaZ() * friction;
        double accelFactor = computeAccelFactor(data, onGround, friction);
        double yawRad = Math.toRadians(yaw);
        double sinYaw = Math.sin(yawRad);
        double cosYaw = Math.cos(yawRad);

        for (double[] input : INPUT_VECTORS) {
            double strafe = input[0];
            double forward = input[1];

            double len = Math.hypot(strafe, forward);
            if (len >= 1.0D) {
                strafe /= len;
                forward /= len;
            }

            double inputX = (strafe * cosYaw - forward * sinYaw) * accelFactor;
            double inputZ = (forward * cosYaw + strafe * sinYaw) * accelFactor;

            double simX = lastX + receivedVelocity.getX() + inputX;
            double simZ = lastZ + receivedVelocity.getZ() + inputZ;

            if (isWallObstructed(data, simX, simZ)) {
                if (Math.abs(actualX) < 0.01) simX = actualX;
                if (Math.abs(actualZ) < 0.01) simZ = actualZ;
            }

            minVectorDev = Math.min(minVectorDev, Math.hypot(actualX - simX, actualZ - simZ));
        }

        long ping = data.getPing();
        double pingGrace = Math.min(0.12D, Math.max(0.0D, (ping - 50L) * 0.00045D));
        double allowedDev = 0.10D + (onGround ? 0.06D : 0.04D) + pingGrace;
        // During ACTIVE KB the player's own input fights the knockback:
        // strafing back toward the attacker adds up to 0.13/tick on top
        // of the KB decay, so the expected vector routinely differs from
        // the actual by the full input accel. Widen by input contribution.
        if (data.getVelocities().hasActiveVelocity()) {
            allowedDev += data.isSprinting() ? 0.16D : 0.10D;
        }

        if (minVectorDev > allowedDev) {
            double excess = minVectorDev - allowedDev;
            if (buffer.increase(data.getPlayer(), 1.0D + excess * 8.0D) > 5.0D) {
                double kbRes;
                try {
                    kbRes = data.getKnockbackResistance();
                } catch (Throwable ignored) {
                    kbRes = 0.0D;
                }
                flag(data, String.format("Horizontal Vector Fail vecDev=%.4f allowed=%.4f velXZ=%.3f rtt=%dms kbRes=%.2f",
                        minVectorDev, allowedDev, velXZ, ping, kbRes), 1.5D + excess * 10.0D);
                buffer.reset(data.getPlayer(), 2.0D);
            }
        } else {
            buffer.decrease(data.getPlayer(), 0.35D);
        }
    }

    private double computeAccelFactor(PlayerData data, boolean onGround, double friction) {
        double base = data.getWalkSpeed();
        int speedLevel = data.getPotionLevel(org.bukkit.potion.PotionEffectType.SPEED);
        if (speedLevel > 0) base *= 1.0D + 0.20D * speedLevel;
        if (data.isSprinting()) base *= 1.30D;
        if (data.isSneaking()) base *= 0.30D;

        if (onGround) {
            double f3 = Math.max(0.048D, friction * friction * friction);
            return base * (0.16277136D / f3);
        }
        return data.isSprinting() ? 0.026D : 0.020D;
    }

    private double computeGroundFriction(PlayerData data) {
        // Shared 3x3 max with SimA. The old single-block sample disagreed with
        // the movement checks on block edges (ice next to dirt read dirt here,
        // ice there) and manufactured velocity flags out of nothing.
        try {
            return ret.tawny.truthful.checks.impl.movement.MovementCheckSupport.computeGroundFriction(data);
        } catch (Throwable ignored) {
            int x = (int) Math.floor(data.getX());
            int y = (int) Math.floor(data.getY() - 0.2D);
            int z = (int) Math.floor(data.getZ());
            return BlockPropertyRegistry.getFriction(data.getWorldCache().getBlockState(x, y, z)) * 0.91D;
        }
    }

    private boolean isWallObstructed(PlayerData data, double simX, double simZ) {
        int px = (int) Math.floor(data.getX());
        int py = (int) Math.floor(data.getY());
        int pz = (int) Math.floor(data.getZ());

        int ox = simX > 0 ? 1 : -1;
        int oz = simZ > 0 ? 1 : -1;

        return BlockPropertyRegistry.isSolid(data.getWorldCache().getBlockState(px + ox, py, pz)) ||
                BlockPropertyRegistry.isSolid(data.getWorldCache().getBlockState(px, py, pz + oz));
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}
