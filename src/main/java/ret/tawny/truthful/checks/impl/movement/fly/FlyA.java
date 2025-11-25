package ret.tawny.truthful.checks.impl.movement.fly;

import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'A', type = CheckType.FLY)
public final class FlyA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(12.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null) return;

        // --- EXEMPTIONS ---
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (player.getAllowFlight() || player.isFlying() || player.isInsideVehicle()) return;
        if (player.isGliding()) return;
        try { if (player.isRiptiding()) return; } catch (Throwable ignored) {}

        // FIX: Vehicle Exit Grace Period Reduced (40 -> 10)
        // Prevents "Boat Fly" exploit where players toggle fly immediately after exiting.
        if (data.getTicksTracked() - data.getLastVehicleExitTick() < 10) {
            buffer.decrease(player, 0.5);
            return;
        }

        // Web Grace Period
        if (data.isInWeb() || (data.getTicksTracked() - data.getLastWebTick() < 20)) {
            buffer.decrease(player, 0.5);
            return;
        }

        if (player.hasPotionEffect(PotionEffectType.LEVITATION) ||
                player.hasPotionEffect(PotionEffectType.SLOW_FALLING) ||
                player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) return;

        if (data.isInLiquid() || data.isOnClimbable() || data.isUnderBlock()) {
            buffer.decrease(player, 0.5);
            return;
        }

        if (!data.getVelocities().isEmpty() || data.isTeleportTick()) return;

        // Ghost block / connection lag exemption
        if (data.getTicksTracked() - data.getLastGhostBlockTick() < 10) {
            buffer.decrease(player, 1.0);
            return;
        }

        if (data.isOnGround() || data.isLastGround()) {
            buffer.decrease(player, 0.5);
            return;
        }

        double currentDeltaY = data.getDeltaY();
        double lastDeltaY = data.getLastDeltaY();

        // --- STEP EXEMPTION ---
        if (currentDeltaY > 0.0) {
            if (Math.abs(currentDeltaY - 0.5) < 0.001 ||
                    Math.abs(currentDeltaY - 0.6) < 0.001 ||
                    Math.abs(currentDeltaY - 1.0) < 0.001 ||
                    Math.abs(currentDeltaY - 0.42) < 0.001) {
                buffer.decrease(player, 0.25);
                return;
            }
        }

        // Vanilla Gravity Prediction
        double predictedDeltaY = (lastDeltaY - 0.08D) * 0.9800000190734863D;

        // Threshold tolerance
        double difference = Math.abs(currentDeltaY - predictedDeltaY);

        // Only check if difference is significant and we aren't essentially stationary
        if (difference > 0.005 && Math.abs(currentDeltaY) > 0.005) {
            if (isInBubbleColumn(player)) return;

            if (buffer.increase(player, 1.0) > 12.0) {
                flag(data, String.format("Gravity Mismatch. Diff: %.5f, Y: %.4f", difference, currentDeltaY));
            }
        } else {
            buffer.decrease(player, 0.25);
        }
    }

    private boolean isInBubbleColumn(Player player) {
        try {
            Block b = player.getLocation().getBlock();
            return b.getType().name().contains("BUBBLE_COLUMN");
        } catch (Exception e) {
            return false;
        }
    }
}