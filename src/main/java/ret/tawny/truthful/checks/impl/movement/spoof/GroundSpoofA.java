package ret.tawny.truthful.checks.impl.movement.spoof;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.world.WorldUtils;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'A', type = CheckType.SPOOF)
@SuppressWarnings("unused")
public final class GroundSpoofA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(4.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        if (!relMovePacketWrapper.isPositionUpdate()) return;

        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData playerData = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (playerData == null) return;

        // Exemptions
        if (player.getAllowFlight() || player.isFlying() || player.isGliding() || player.isInsideVehicle()) return;
        if (playerData.isTeleportTick() || playerData.isInLiquid() || WorldUtils.hasClimbableNearby(player)) return;

        // Vehicle Exit Buffer (Reduced to 10 ticks in previous fix)
        if (playerData.getTicksTracked() - playerData.getLastVehicleExitTick() < 10) {
            buffer.decrease(player, 0.5);
            return;
        }

        // Boat/Entity Collision Exemption
        if (playerData.isNearVehicle() || playerData.isNearEntity()) {
            buffer.decrease(player, 0.5);
            return;
        }

        final boolean clientGround = relMovePacketWrapper.isGround();
        final boolean serverGround = playerData.isOnGround();

        // Logic: Client claims ground, Server says air.
        // "NoPacket" NoFall often tries to sneak a single onGround=true packet while falling.
        if (clientGround && !serverGround) {
            // We require a few ticks of air to prevent false flags on slab edges/stairs (math errors)
            if (playerData.getTicksInAir() > 4) {
                // Stricter buffer for NoFall
                if (buffer.increase(player, 1.0) > 4.0) {
                    flag(playerData, "Ground Spoof (NoFall). Server: Air, Client: Ground");

                    // Force set back to air logic if lagbacks enabled
                    if (Truthful.getInstance().getConfiguration().isLagbacks()) {
                        player.teleport(playerData.getLastLocation());
                    }
                    buffer.reset(player, 2.0);
                }
            }
        } else {
            // Decay
            buffer.decrease(player, 0.25);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}