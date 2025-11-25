package ret.tawny.truthful.checks.impl.movement.elytra;

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

@CheckData(order = 'E', type = CheckType.ELYTRA)
@SuppressWarnings("unused")
public final class ElytraE extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null) return;

        if (!player.isGliding()) return;
        if (player.isInsideVehicle()) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        try { if (player.isRiptiding()) return; } catch (Throwable ignored) {}
        if (data.isInLiquid() || data.isInWeb() || data.isOnClimbable()) return;

        double speed = data.getDeltaXZ();

        // If slow, alignment is erratic. Only check at speed.
        if (speed > 0.6) {
            Vector move = new Vector(data.getDeltaX(), 0, data.getDeltaZ()).normalize();
            Vector look = player.getLocation().getDirection().setY(0).normalize();

            try {
                float angle = move.angle(look); // Radians
                double degrees = Math.toDegrees(angle);

                // Safe limit 60 degrees.
                // Vanilla Elytra allows slight sideways drift but not 90 degrees.
                if (degrees > 60.0) {
                    if (buffer.increase(player, 1.0) > 10.0) {
                        flag(data, String.format("Elytra Strafe / Misalignment. Angle: %.1f", degrees));
                        buffer.reset(player, 5.0);
                    }
                } else {
                    buffer.decrease(player, 0.25);
                }
            } catch (Exception ignored) {}
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}