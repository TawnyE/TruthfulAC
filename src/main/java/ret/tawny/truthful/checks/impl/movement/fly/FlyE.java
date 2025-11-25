package ret.tawny.truthful.checks.impl.movement.fly;

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

@CheckData(order = 'E', type = CheckType.FLY)
@SuppressWarnings("unused")
public final class FlyE extends Check {

    private final CheckBuffer buffer = new CheckBuffer(5.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null)
            return;

        if (player.getAllowFlight() || player.isInsideVehicle() || player.isGliding())
            return;
        try {
            if (player.isRiptiding())
                return;
        } catch (Throwable ignored) {
        }

        if (data.isInLiquid() || data.isOnClimbable())
            return;

        // --- FIX: WEB EXEMPTION ---
        if (data.isInWeb() || (data.getTicksTracked() - data.getLastWebTick() < 20)) {
            buffer.decrease(player, 0.5);
            return;
        }

        // Logic: Ground Spoof
        // Client claims to be on ground, but server says they are in air.
        // This is "NoFall" or "Ground Spoof".
        boolean clientGround = relMovePacketWrapper.isGround();
        boolean serverGround = data.isOnGround(); // Calculated by WorldUtils

        // Only check if strictly in air (server says NO ground nearby)
        if (clientGround && !serverGround) {
            // Check if they are actually near any block (slabs/stairs might be tricky)
            // WorldUtils.safeGround handles this, but let's be sure.
            // Also check for boat/entity collisions which might trick ground check.
            if (data.isNearVehicle() || data.isNearEntity())
                return;

            if (buffer.increase(player, 1.0) > 5.0) {
                flag(data, "Ground Spoof (NoFall)");
                if (Truthful.getInstance().getConfiguration().isLagbacks()) {
                    player.teleport(data.getLastLocation());
                }
                buffer.reset(player, 2.0);
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
