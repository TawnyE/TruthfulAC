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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AimB - basic grid snap. The GCD step is valid, but rotations don't land on
 * the grid (large moulo error, stable across ticks). Catches decent assist
 * that fakes a sensitivity but doesn't quantize properly. Coarse by design.
 */
@CheckData(order = 'B', type = CheckType.AIM)
public final class AimB extends Check {

    private static final double EXPANDER = 16777216.0D;

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private final Map<UUID, AimData> dataMap = new ConcurrentHashMap<>();

    @Override
    public void handleRelMove(final RelMovePacketWrapper event) {
        if (!event.isRotationUpdate()) return;

        final Player player = event.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isRotationExempt() || data.isInsideVehicle()) return;
        if (data.isServerFrozen()) {
            buffer.decrease(player, 0.5);
            return;
        }

        AimData aimData = dataMap.computeIfAbsent(player.getUniqueId(), k -> new AimData());

        final float deltaYaw = data.getRotationDeviation(false);
        final float deltaPitch = data.getRotationDeviation(true);
        final float pitch = data.getPitch();

        if (data.getTicksTracked() - data.getLastHitTick() > 60) {
            aimData.lastDeltaYaw = deltaYaw;
            aimData.stableTicks = 0;
            buffer.decrease(player, 0.2);
            return;
        }

        if (Math.abs(pitch) > 89.0f || deltaYaw < 0.5f || deltaYaw > 15.0f
                || deltaPitch > 15.0f) {
            aimData.lastDeltaYaw = deltaYaw;
            aimData.stableTicks = 0;
            return;
        }

        final long current = (long) (deltaYaw * EXPANDER);
        final long last = (long) (aimData.lastDeltaYaw * EXPANDER);
        if (last <= 0L) {
            aimData.lastDeltaYaw = deltaYaw;
            return;
        }

        final double step = MathHelper.getGcd(current, last) / EXPANDER;
        if (step <= 0.005D) {
            aimData.lastDeltaYaw = deltaYaw;
            aimData.stableTicks = 0;
            buffer.decrease(player, 0.05);
            return;
        }

        // Step must itself be a plausible sensitivity, otherwise AimA owns it.
        double sens = (Math.cbrt(step / 1.2D) - 0.2D) / 0.6D;
        if (sens < -0.05D || sens > 5.0D) {
            aimData.lastDeltaYaw = deltaYaw;
            aimData.stableTicks = 0;
            buffer.decrease(player, 0.05);
            return;
        }

        double mod = deltaYaw % step;
        double error = Math.min(mod, step - mod) / step;
        // Skip aliased fractions (half/quarter steps look off-grid but legit).
        boolean aliased = isNear(error, 0.5D, 0.03D) || isNear(error, 0.33D, 0.03D)
                || isNear(error, 0.25D, 0.03D) || isNear(error, 0.67D, 0.03D);
        if (!aliased && error > 0.20D) {
            aimData.stableTicks++;
            if (aimData.stableTicks >= 3) {
                if (buffer.increase(player, 1.0) > 10.0) {
                    flag(data, "Off-grid rotation");
                    buffer.reset(player, 4.0);
                }
                aimData.stableTicks = 0;
            }
        } else {
            aimData.stableTicks = 0;
            buffer.decrease(player, 0.1);
        }

        aimData.lastDeltaYaw = deltaYaw;
    }

    private static boolean isNear(double value, double target, double tol) {
        return Math.abs(value - target) <= tol;
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        dataMap.remove(event.getPlayer().getUniqueId());
    }

    private static class AimData {
        float lastDeltaYaw = 0f;
        int stableTicks = 0;
    }
}
