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

@CheckData(order = 'I', type = CheckType.ELYTRA)
@SuppressWarnings("unused")
public final class ElytraI extends Check {

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
        if (data.getTicksTracked() - data.getLastFireworkTick() < 60) return;

        if (data.isInWeb() || data.isInLiquid() || data.isNearVehicle()) {
            buffer.decrease(player, 0.5);
            return;
        }

        double deltaY = data.getDeltaY();
        double deltaXZ = data.getDeltaXZ();

        // FIX: Stall/Braking Exemption
        // If holding S or looking up (pitch < -15), speed drops and Y drops slowly.
        if (data.getPitch() < -15.0f && deltaXZ < 0.4) {
            buffer.decrease(player, 0.25);
            return;
        }

        // Logic: Hover Check
        if (deltaXZ < 0.1 && deltaY > -0.005) {
            // Allow brief stalls (momentum shift)
            if (data.getTicksInAir() < 20) {
                buffer.decrease(player, 0.5);
                return;
            }

            if (buffer.increase(player, 1.0) > 10.0) {
                flag(data, String.format("Elytra Hover. Speed: %.3f, Drop: %.3f", deltaXZ, deltaY));
                buffer.reset(player, 5.0);
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