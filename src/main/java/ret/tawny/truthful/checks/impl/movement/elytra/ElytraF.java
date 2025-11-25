package ret.tawny.truthful.checks.impl.movement.elytra;

import org.bukkit.GameMode;
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

@CheckData(order = 'F', type = CheckType.ELYTRA)
@SuppressWarnings("unused")
public final class ElytraF extends Check {

    private final CheckBuffer buffer = new CheckBuffer(12.0);

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
        if (data.getTicksTracked() - data.getLastFireworkTick() < 60) return;

        double speed = data.getDeltaXZ();
        float pitch = data.getPitch();

        double maxSpeed;

        // If looking UP (Negative pitch in Bukkit), you must slow down.
        // If looking DOWN (Positive pitch), you can speed up.

        if (pitch < -10.0) { // Looking up
            maxSpeed = 1.2; // Gravity slows you down
        } else if (pitch < 45.0) { // Level-ish
            maxSpeed = 2.0;
        } else { // Diving
            maxSpeed = 4.5; // Terminal velocity roughly
        }

        if (speed > maxSpeed) {
            // Allow momentum bleeding. If decelerating, it's fine.
            double acceleration = speed - data.getLastDeltaXZ();
            if (acceleration < -0.01) {
                buffer.decrease(player, 0.1);
                return;
            }

            if (buffer.increase(player, 1.0) > 12.0) {
                flag(data, String.format("Invalid Pitch/Speed. Speed: %.2f, Limit: %.2f, Pitch: %.1f", speed, maxSpeed, pitch));
                if (Truthful.getInstance().getConfiguration().isLagbacks()) {
                    player.teleport(data.getLastLocation());
                }
                buffer.reset(player, 6.0);
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