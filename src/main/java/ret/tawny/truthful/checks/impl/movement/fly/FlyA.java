package ret.tawny.truthful.checks.impl.movement.fly;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.player.PlayerUtils;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'A', type = CheckType.FLY)
@SuppressWarnings("unused")
public final class FlyA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(15.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null || data.isTeleportTick() || data.isOnGround() || data.getTicksInAir() < 5) return;
        if (player.getAllowFlight() || player.isGliding() || data.isInLiquid() || data.isOnClimbable()) return;
        if (PlayerUtils.getPotion(PotionEffectType.LEVITATION, data) != null || PlayerUtils.getPotion(PotionEffectType.SLOW_FALLING, data) != null) return;

        // --- DEFINITIVE FIX: State-Aware Logic ---
        // This check is a vertical residual test and should only apply to falling motion.
        if (data.isLastGround() || data.getDeltaY() > 0 || !data.getVelocities().isEmpty()) {
            return; // Ignore the tick a player jumps or any upward motion.
        }

        double currentDeltaY = data.getDeltaY();
        double lastDeltaY = data.getLastDeltaY();

        double predictedDeltaY = (lastDeltaY - 0.08D) * 0.98D;
        double difference = Math.abs(currentDeltaY - predictedDeltaY);

        // Increased tolerance to 0.01 to account for minor server/network inconsistencies.
        if (difference > 0.01) {
            if (buffer.increase(player, 1.0) > 15.0) {
                flag(data, String.format("Failed vertical residual test. Diff: %.5f", difference));
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