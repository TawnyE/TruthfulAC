package ret.tawny.truthful.checks.impl.movement.noslow;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'A', type = CheckType.NO_SLOW)
@SuppressWarnings("unused")
public final class NoSlowA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(5.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper event) {
        final Player player = event.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null)
            return;

        // NoSlow Logic
        // If player is using an item (blocking/eating/bow), they should be slowed down.
        // Max speed while using item is significantly lower (approx 0.2 * 0.2 = 0.04?
        // No, it's a multiplier).
        // Usually it's -80% speed? Or set to 0.2?
        // Walking speed is 0.22. Blocking is much slower.

        if (player.isBlocking() || player.isHandRaised()) { // isHandRaised covers eating/drinking in newer versions?
            double deltaX = data.getDeltaX();
            double deltaZ = data.getDeltaZ();
            double speed = Math.hypot(deltaX, deltaZ);

            // Threshold for blocking speed.
            // 0.2 (Base) * 0.2 (Multiplier) is very slow.
            // But sprinting + blocking might be different.
            // Let's set a safe threshold.

            double maxSpeed = 0.2; // Safe upper bound for blocking/eating

            if (speed > maxSpeed && data.isOnGround()) {
                if (buffer.increase(player, 1.0) > 5.0) {
                    flag(data, String.format("NoSlow: %.3f > %.3f", speed, maxSpeed));
                    buffer.reset(player, 2.0);
                }
            } else {
                buffer.decrease(player, 0.25);
            }
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}
