package ret.tawny.truthful.checks.impl.movement.spoof;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.ExemptionType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.world.WorldUtils;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

/**
 * GroundSpoofG - Status Pulse Verification
 *
 * Logic: Targets NoFall modules that send "Ground = True" inside packets that do NOT
 * contain a position update (Rotation-only or Status-only packets).
 *
 * Legitimate clients only toggle ground status when a Position packet actually
 * puts them on a block.
 */
@CheckData(order = 'G', type = CheckType.SPOOF)
public final class GroundSpoofG extends Check {

    private final CheckBuffer buffer = new CheckBuffer(8.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper wrapper) {
        final Player player = wrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null || data.isServerFrozen() || data.isTeleportTick()) return;

        // 1. Basic Exemptions
        if (data.isAllowFlight() || data.isInsideVehicle() || data.isGliding()) {
            return;
        }

        if (data.isJoinExempt() || data.isExempt(ExemptionType.LIQUID)) {
            return;
        }

        // 2. Detection Logic
        // If client claims ground but the packet is NOT a position update
        if (wrapper.isGround() && !wrapper.isPositionUpdate()) {

            // Ignore while actively falling fast; packet-order jitter can briefly desync ground bit
            if (data.getDeltaY() < -0.15) {
                buffer.decrease(player, 0.2);
                return;
            }

            if (data.getAirTicks() < 5) return;

            // Collider-based support probe (sneak-edge tolerant).
            boolean physicallyOnGround = WorldUtils.hasSupportBelow(data, 0.10D);

            // Shape-boundary fallback: beds and cache-missed partial blocks.
            if (!physicallyOnGround && WorldUtils.isAtShapeBoundary(data)) {
                physicallyOnGround = true;
            }

            if (!physicallyOnGround) {

                // Exemption: Edge cases like stepping on complex blocks or placing blocks
                if (data.isUnderBlock() || data.getTicksTracked() - data.getLastBlockPlaceTick() < 5) {
                    return;
                }

                // Entity support (Boats/Shulkers)
                if (data.isNearVehicle() || data.isNearEntity()) {
                    return;
                }

                if (buffer.increase(player, 1.0) > 5.0) {
                    flag(data, "Ground Status Pulse (No Movement) " + ret.tawny.truthful.debug.DebugContext.build(data) + ret.tawny.truthful.debug.DebugContext.blocks(data));
                    buffer.reset(player, 2.0);
                }
            } else {
                buffer.decrease(player, 0.25);
            }
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}