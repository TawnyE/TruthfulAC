package ret.tawny.truthful.checks.impl.movement.speed;

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
import ret.tawny.truthful.utils.world.WorldUtils;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'C', type = CheckType.SPEED)
@SuppressWarnings("unused")
public final class SpeedC extends Check {

    private final CheckBuffer buffer = new CheckBuffer(12.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        if (!relMovePacketWrapper.isPositionUpdate()) return;

        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null) return;

        if (player.getAllowFlight() || player.isFlying() || player.isGliding() || player.isInsideVehicle()) return;

        if (data.isTeleportTick() || !data.getVelocities().isEmpty()) return;
        try { if (player.isRiptiding()) return; } catch (Throwable ignored) {}
        if (WorldUtils.hasLowFrictionBelow(player)) return;

        if (data.getDeltaXZ() < 0.22) return;

        Vector move = new Vector(data.getDeltaX(), 0, data.getDeltaZ());

        Vector look = player.getLocation().getDirection();
        look.setY(0).normalize();

        float angle = move.angle(look);
        double degrees = Math.toDegrees(angle);

        if (player.isSprinting()) {
            if (degrees > 60.0) {
                if (buffer.increase(player, 1.0) > 12.0) {
                    flag(data, String.format("Omni-Sprint. Angle: %.1f", degrees));
                    // Manual teleport removed
                }
            } else {
                buffer.decrease(player, 0.15);
            }
        }

        if (!data.isOnGround() && !data.isLastGround()) {
            if (data.getDeltaXZ() > 0.3 && degrees > 40.0) {
                if (buffer.increase(player, 1.0) > 15.0) {
                    flag(data, String.format("Impossible Air Strafe. Speed: %.3f, Angle: %.1f", data.getDeltaXZ(), degrees));
                }
            }
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}