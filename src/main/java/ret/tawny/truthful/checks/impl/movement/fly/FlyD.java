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
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'D', type = CheckType.FLY)
@SuppressWarnings("unused")
public final class FlyD extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null)
            return;

        if (player.getAllowFlight() || player.isInsideVehicle() || player.isGliding())
            return;
        try {
            if (player.isRiptiding())
                return;
        } catch (Throwable ignored) {
        }

        if (data.isInLiquid() || data.isOnClimbable() || data.isUnderBlock())
            return;
        if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                || player.hasPotionEffect(PotionEffectType.LEVITATION))
            return;

        // --- FIX: WEB EXEMPTION ---
        if (data.isInWeb() || (data.getTicksTracked() - data.getLastWebTick() < 20)) {
            buffer.decrease(player, 0.5);
            return;
        }

        double deltaY = data.getDeltaY();
        double lastDeltaY = data.getLastDeltaY();

        // Logic: Slow Falling
        // If falling (deltaY < 0) but falling slower than expected.
        // Expected drop increases over time.
        // If deltaY is consistently small (e.g. -0.1) while in air, it's suspicious.

        if (data.getTicksInAir() > 10 && deltaY < 0.0 && deltaY > -0.5) {
            double predictedDrop = (lastDeltaY - 0.08) * 0.98;

            // If we are falling significantly slower than predicted
            if (deltaY > predictedDrop + 0.1) {
                if (buffer.increase(player, 1.0) > 10.0) {
                    flag(data, String.format("Slow Falling. Y: %.4f, Pred: %.4f", deltaY, predictedDrop));
                    if (Truthful.getInstance().getConfiguration().isLagbacks()) {
                        player.teleport(data.getLastLocation());
                    }
                    buffer.reset(player, 5.0);
                }
            } else {
                buffer.decrease(player, 0.25);
            }
        } else {
            buffer.decrease(player, 0.25);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}
