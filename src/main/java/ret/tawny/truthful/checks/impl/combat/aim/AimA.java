package ret.tawny.truthful.checks.impl.combat.aim;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.math.MathHelper;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'A', type = CheckType.AIM)
public final class AimA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(15.0);
    private double lastDeltaPitch;
    private double lastDeltaYaw;

    @Override
    public void handleRelMove(final RelMovePacketWrapper event) {
        if (!event.isRotationUpdate()) return;

        final Player player = event.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null) return;

        final float deltaYaw = Math.abs(data.getDeltaYaw());
        final float deltaPitch = Math.abs(data.getDeltaPitch());

        final double differenceYaw = Math.abs(deltaYaw - this.lastDeltaYaw);
        final double differencePitch = Math.abs(deltaPitch - this.lastDeltaPitch);

        // Smoothing Detection: If difference is tiny but non-zero
        boolean isSmoothed = differenceYaw < 0.01 || differencePitch < 0.01;

        // Requirement: Must be rotating significantly enough to measure
        boolean validRotation = deltaYaw > 1.5 && deltaPitch > 1.5 && deltaPitch < 30.f;

        if (validRotation && !isSmoothed) {

            final double divisorPitch = MathHelper.getGcd(deltaPitch, this.lastDeltaPitch);
            final double divisorYaw = MathHelper.getGcd(deltaYaw, this.lastDeltaYaw);

            // Avoid division by zero
            if (divisorPitch < 1e-4 || divisorYaw < 1e-4) {
                this.lastDeltaYaw = deltaYaw;
                this.lastDeltaPitch = deltaPitch;
                return;
            }

            final double ratioPitch = deltaPitch / divisorPitch;
            final double ratioYaw = deltaYaw / divisorYaw;

            // Sensitivity grid logic
            // A valid ratio usually stays well below 3,000.
            double threshold = 12000.0;

            if (ratioPitch > threshold || ratioYaw > threshold) {
                if (buffer.increase(player, 1.25) > 15.0) {
                    flag(data, String.format("Rotations do not match sensitivity grid. Ratios: Y:%.0f/P:%.0f", ratioYaw, ratioPitch));
                    buffer.reset(player, 5.0);
                }
            } else {
                buffer.decrease(player, 2.0); // Faster decay for valid mouse inputs
            }
        } else {
            buffer.decrease(player, 0.15);
        }

        this.lastDeltaYaw = deltaYaw;
        this.lastDeltaPitch = deltaPitch;
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}