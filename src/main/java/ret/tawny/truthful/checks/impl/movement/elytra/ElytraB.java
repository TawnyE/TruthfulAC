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

@CheckData(order = 'B', type = CheckType.ELYTRA)
@SuppressWarnings("unused")
public final class ElytraB extends Check {

    private final CheckBuffer buffer = new CheckBuffer(15.0); // Increased buffer

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null) return;

        // --- EXEMPTIONS ---
        if (!player.isGliding()) return;
        if (player.isInsideVehicle()) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        try { if (player.isRiptiding()) return; } catch (Throwable ignored) {}
        if (data.isInLiquid() || data.isInWeb() || data.isOnClimbable()) return;
        if (data.getTicksTracked() - data.getLastFireworkTick() < 60) return;

        double deltaY = data.getDeltaY();
        double deltaXZ = data.getDeltaXZ();
        double acceleration = data.getDeltaXZ() - data.getLastDeltaXZ(); // Corrected: Check Horizontal Acceleration

        // Logic: Energy Generation
        // It is impossible to gain Speed (Accel > 0) AND gain Height (Rising) at the same time
        // without an external force (Firework).

        boolean rising = deltaY > 0.02; // Buffer for tiny floating point errors
        boolean gainingSpeed = acceleration > 0.01;
        boolean alreadyFast = deltaXZ > 0.4;

        if (rising && gainingSpeed && alreadyFast) {
            // Strict physics check
            if (buffer.increase(player, 1.0) > 15.0) {
                flag(data, String.format("Elytra Energy Generation. Rising: %.4f, Accel: %.4f", deltaY, acceleration));
                buffer.reset(player, 8.0);
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