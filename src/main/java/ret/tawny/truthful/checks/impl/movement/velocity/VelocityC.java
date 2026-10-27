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

@CheckData(order = 'C', type = CheckType.VELOCITY)
public final class VelocityC extends Check {

    private final CheckBuffer buffer = new CheckBuffer(15.0D);

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

        // Only judge during active acknowledged velocity. Running on every
        // tick with anything in the queue flags normal PvP movement.
        if (!queue.hasAcknowledgedVelocity(5, false)) {
            buffer.decrease(data.getPlayer(), 0.25D);
            return;
        }

        if (data.isInLiquid() || data.isInWeb() || data.isOnClimbable()
                || data.isNearVehicle() || data.isUnderBlock()
                || data.isGliding() || data.isRiptiding() || data.isExempt(ExemptionType.RIPTIDE)
                || data.getVelocities().hasExplosionVelocity() || data.isInExplosionGraceWindow(1500L)
                || data.isExempt(ExemptionType.SPEAR_LUNGE) || data.isExempt(ExemptionType.MACE_SMASH)
                || data.isExempt(ExemptionType.WIND_CHARGE)) {
            buffer.decrease(data.getPlayer(), 0.25D);
            return;
        }

        double deltaXZ = data.getDeltaXZ();
        boolean flagged = false;
        double severity = 0;
        String flagReason = null;

        for (VelocityQueue.VelocityEntry entry : queue) {
            int tick = entry.getAckTick();
            if (entry.isAcked() && tick >= 2 && tick <= 5 && !entry.isExplosion()) {
                Vector currentExpected = entry.getCurrent();
                double expectedXZ = Math.hypot(currentExpected.getX(), currentExpected.getZ());

                if (expectedXZ < 0.08D || data.isUnderBlock() || isNearWall(data) || data.isNearEntity()) continue;

                // VelocityQueue has already decayed this entry after the prior
                // position response. Applying friction again here underestimates
                // legal knockback and makes the decay window inconsistent.
                double expectedDecaySpeed = expectedXZ;

                if (deltaXZ < expectedDecaySpeed * 0.30D && !data.isServerGround()) {
                    flagged = true;
                    severity += (expectedDecaySpeed - deltaXZ) * 12.0D;
                    flagReason = String.format("Atypical Velocity Decay on tick %d XZ=%.4f expectedDecay=%.4f",
                            tick, deltaXZ, expectedDecaySpeed);
                }
            }
        }

        if (flagged) {
            if (buffer.increase(data.getPlayer(), severity) > 6.0D) {
                double kbRes;
                try {
                    kbRes = data.getKnockbackResistance();
                } catch (Throwable ignored) {
                    kbRes = 0.0D;
                }
                flag(data, flagReason + String.format(" kbRes=%.2f", kbRes), Math.max(1.0D, severity));
                buffer.reset(data.getPlayer(), 2.0D);
            }
        } else {
            buffer.decrease(data.getPlayer(), 0.2D);
        }
    }

    private double computeGroundFriction(PlayerData data) {
        int x = (int) Math.floor(data.getX());
        int y = (int) Math.floor(data.getY() - 0.2D);
        int z = (int) Math.floor(data.getZ());
        return BlockPropertyRegistry.getFriction(data.getWorldCache().getBlockState(x, y, z)) * 0.91D;
    }

    private boolean isNearWall(PlayerData data) {
        int px = (int) Math.floor(data.getX());
        int py = (int) Math.floor(data.getY());
        int pz = (int) Math.floor(data.getZ());

        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (ox == 0 && oz == 0) continue;
                if (BlockPropertyRegistry.isSolid(data.getWorldCache().getBlockState(px + ox, py, pz + oz)) ||
                        BlockPropertyRegistry.isSolid(data.getWorldCache().getBlockState(px + ox, py + 1, pz + oz))) {
                    return true;
                }
            }
        }
        return false;
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}
