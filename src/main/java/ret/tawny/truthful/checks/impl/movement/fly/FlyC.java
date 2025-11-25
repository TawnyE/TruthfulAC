package ret.tawny.truthful.checks.impl.movement.fly;

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

@CheckData(order = 'C', type = CheckType.FLY)
@SuppressWarnings("unused")
public final class FlyC extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null) return;

        // 1. Gamemode & Flight
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (player.getAllowFlight() || player.isFlying()) return;
        if (player.isInsideVehicle() || player.isGliding()) return;
        try { if (player.isRiptiding()) return; } catch (Throwable ignored) {}

        // 2. Environmental
        if (data.isOnClimbable() || data.isInLiquid()) return;

        // Web Exemption
        if (data.isInWeb() || (data.getTicksTracked() - data.getLastWebTick() < 20)) {
            buffer.decrease(player, 0.5);
            return;
        }

        // 3. Boat/Entity Collision (CRITICAL FIX)
        // Bouncing on a boat or standing on it creates irregular gravity/hovering effects.
        if (data.isNearVehicle() || data.isNearEntity()) {
            buffer.decrease(player, 0.5);
            return;
        }

        // 4. Ground Check
        if (data.isOnGround()) return;

        double deltaY = data.getDeltaY();

        // 5. Hover Check
        // Logic: Moving very little vertically while in mid-air.
        if (Math.abs(deltaY) < 0.01) {

            // Ignore if under a block (Head Hitter jump)
            if (data.isUnderBlock()) {
                buffer.decrease(player, 0.25);
                return;
            }

            // Ignore if exactly 0.0 (Client often sends 0.0 during lag spikes or ghost ground)
            if (deltaY == 0.0) {
                buffer.decrease(player, 0.1);
                return;
            }

            if (buffer.increase(player, 1.0) > 10.0) {
                flag(data, String.format("Hovering/Gliding in air. Y: %.5f", deltaY));
                // Manual teleport handled by flag()
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