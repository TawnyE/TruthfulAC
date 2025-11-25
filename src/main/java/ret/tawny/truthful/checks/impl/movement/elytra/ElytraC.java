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

@CheckData(order = 'C', type = CheckType.ELYTRA)
@SuppressWarnings("unused")
public final class ElytraC extends Check {

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
        if (data.getTicksTracked() - data.getLastFireworkTick() < 60) return;

        double deltaXZ = data.getDeltaXZ();
        double deltaY = data.getDeltaY();

        // Only check if moving significantly forward and "floating" (deltaY is small)
        if (deltaXZ > 0.5 && deltaY < 0.1 && deltaY > -0.5) {

            if (Math.abs(deltaY) < 0.001) deltaY = 0.001; // Avoid div/0

            double ratio = deltaXZ / Math.abs(deltaY);

            // Vanilla Max Efficiency:
            // ~15-20. Cheats go 50+.
            // Limit 50 is very safe.
            double limit = 50.0;

            if (ratio > limit) {
                if (buffer.increase(player, 1.0) > 10.0) {
                    flag(data, String.format("Impossible Glide Ratio. Ratio: %.2f, Limit: %.2f", ratio, limit));
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