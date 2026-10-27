package ret.tawny.truthful.checks.impl.movement.velocity;

import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.ExemptionType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.sync.VelocityQueue;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'B', type = CheckType.VELOCITY)
public final class VelocityB extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0D);

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
        if (!queue.hasAcknowledgedVelocity(1, false)
                || data.isUnderBlock() || data.isInLiquid() || data.isInWeb()
                || data.isOnClimbable() || data.isNearVehicle() || data.isNearEntity()
                || data.isGliding() || data.isRiptiding() || data.isExempt(ExemptionType.RIPTIDE)
                || data.getVelocities().hasExplosionVelocity() || data.isInExplosionGraceWindow(1500L)
                || data.isExempt(ExemptionType.SPEAR_LUNGE) || data.isExempt(ExemptionType.MACE_SMASH)
                || data.isExempt(ExemptionType.WIND_CHARGE)
                // KB onto a slime block/bed inverts vertical motion (bounce), which
                // none of the candidates below model. Skip rather than misjudge it.
                || data.getTicksTracked() - data.getLastSlimeTick() < 6) {
            buffer.decrease(data.getPlayer(), 0.25D);
            return;
        }

        double actualY = data.getDeltaY();
        double lastY = data.getLastDeltaY();
        Vector receivedVelocity = queue.getAcknowledgedVelocityVector(1, false);
        double expectedY = receivedVelocity.getY();
        if (expectedY < 0.08D || data.isServerGround()) {
            buffer.decrease(data.getPlayer(), 0.25D);
            return;
        }

        double gravity = data.getGravity();
        int jumpBoost = data.getPotionLevel(PotionEffectType.JUMP_BOOST);
        double baseJumpStrength = data.getJumpStrength() + (jumpBoost * 0.1D);
        double predGravity = (lastY - gravity) * 0.98D + expectedY;
        double predJumpPlusVel = baseJumpStrength + expectedY;
        double predPureJump = baseJumpStrength;
        double predDirect = expectedY;
        double minVDev = Math.min(Math.abs(actualY - predGravity), Math.min(
                Math.abs(actualY - predJumpPlusVel), Math.min(
                        Math.abs(actualY - predPureJump), Math.abs(actualY - predDirect))));

        double allowedVDev = 0.11D + Math.min(0.08D, data.getPing() * 0.0004D);
        if (minVDev > allowedVDev) {
            double excess = minVDev - allowedVDev;
            if (buffer.increase(data.getPlayer(), 1.0D + excess * 7.0D) > 5.0D) {
                double kbRes;
                try {
                    kbRes = data.getKnockbackResistance();
                } catch (Throwable ignored) {
                    kbRes = 0.0D;
                }
                flag(data, String.format("Vertical Impulse Fail Y=%.4f expectedY=%.4f dev=%.4f allowed=%.4f kbRes=%.2f",
                        actualY, expectedY, minVDev, allowedVDev, kbRes), 1.5D + excess * 8.0D);
                buffer.reset(data.getPlayer(), 2.0D);
            }
        } else {
            buffer.decrease(data.getPlayer(), 0.3D);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}
