package ret.tawny.truthful.checks.impl.packet.sprint;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'B', type = CheckType.SPRINT)
public final class SprintB extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper wrapper) {
        if (!wrapper.isPositionUpdate()) return;

        final Player player = wrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null) return;

        if (data.getGameMode() != GameMode.SURVIVAL ||
                data.isAllowFlight() || data.isGliding() || data.isInsideVehicle()) {
            return;
        }

        if (!data.isSprinting()) {
            buffer.decrease(player, 0.5);
            return;
        }

        // Airborne momentum is not steering: a legit player can turn 180
        // mid-jump while the velocity vector still points the old way. The
        // old airborne zero-limit flagged every such turn, so this check now
        // only judges grounded ticks (which is where omni-sprint actually
        // manifests).
        if (!data.isOnGround()) {
            buffer.decrease(player, 0.5);
            return;
        }

        if (data.getDeltaXZ() < 0.22) return;
        if (data.hasVelocity() || data.getTicksTracked() - data.getLastDamageTick() < 10) {
            buffer.decrease(player, 0.5);
            return;
        }

        Vector move = new Vector(data.getDeltaX(), 0.0, data.getDeltaZ()).normalize();
        Vector look = data.getLocation().getDirection().setY(0.0).normalize();

        double dot = move.dot(look);
        double limit = 0.4;

        if (Math.abs(data.getDeltaYaw()) > 1.5) limit = 0.25;

        if (dot < limit) {
            double multiplier = (dot < -0.2) ? 2.0 : 1.0;

            if (buffer.increase(player, 1.0 * multiplier) > 8.0) {
                // DETAILED INFO FOR ALERT
                flag(data, String.format("OmniSprint (Direction). Dot: %.3f, Limit: %.2f", dot, limit));
                buffer.reset(player, 4.0);
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
