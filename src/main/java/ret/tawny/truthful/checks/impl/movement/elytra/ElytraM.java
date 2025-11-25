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

@CheckData(order = 'M', type = CheckType.ELYTRA)
@SuppressWarnings("unused")
public final class ElytraM extends Check {

    private final CheckBuffer buffer = new CheckBuffer(15.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null) return;

        if (!player.isGliding()) return;
        if (player.isInsideVehicle()) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        try { if (player.isRiptiding()) return; } catch (Throwable ignored) {}
        if (data.getTicksTracked() - data.getLastFireworkTick() < 60) return;

        if (data.isInWeb() || data.isInLiquid() || data.isNearVehicle()) {
            buffer.decrease(player, 0.5);
            return;
        }

        double deltaY = data.getDeltaY();
        double deltaXZ = data.getDeltaXZ();
        double lastDeltaXZ = data.getLastDeltaXZ();

        // Logic: Ascension Pattern
        if (deltaY > 0.0 && deltaY < 0.42) {
            // Momentum Trade: If slowing down, rising is allowed.
            if (deltaXZ < lastDeltaXZ) {
                buffer.decrease(player, 0.1);
                return;
            }

            if (buffer.increase(player, 1.0) > 15.0) {
                flag(data, String.format("Invalid Ascension Pattern. Y: %.4f", deltaY));
                // Manual teleport removed
                buffer.reset(player, 7.0);
            }
        } else {
            buffer.decrease(player, 0.1);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}