package ret.tawny.truthful.checks.impl.movement.spoof;

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
 * GroundSpoofB - Height Alignment (collision grid).
 *
 * A player claiming ground must rest on a collision surface, and every
 * vanilla collision height is a multiple of 1/16 (the 1/64 tolerance below
 * covers float drift and layered partial blocks). A claimed-ground Y that is
 * off that grid - while none of the legitimate "carry" sources (entities,
 * vehicles, pistons, shape-boundary rests, placement settle windows) explain
 * it - is fabricated ground.
 *
 * The old implementation was dead: its final fallback was
 * "if (server sees ground) valid = true", so the verdict was nullified in
 * exactly the case it was meant to test. The modern NoFall companions
 * (C/E/F/G) still cover fall-reset play; this check covers the static claim.
 */
@CheckData(order = 'B', type = CheckType.SPOOF)
public final class GroundSpoofB extends Check {

    private final CheckBuffer buffer = new CheckBuffer(5.0);
    private static final double GRID = 1.0D / 64.0D;

    @Override
    public void handleRelMove(final RelMovePacketWrapper wrapper) {
        if (!wrapper.isPositionUpdate()) return;

        final Player player = wrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null || data.isServerFrozen() || data.isTeleportTick()) return;
        if (data.isAllowFlight() || data.isInsideVehicle() || data.isGliding()) return;
        if (data.isNearVehicle() || data.isNearEntity()) return;
        if (data.isInLiquid() || data.getMovementContext().isPowderSnow()) return;
        if (data.isExempt(ExemptionType.PISTON)) return;

        if (!wrapper.isGround()) {
            buffer.decrease(player, 0.1);
            return;
        }

        // Placement/ghost blocks can still be resolving on the client; never
        // judge the settle window.
        int ticksSincePlace = data.getTicksTracked() - data.getLastBlockPlaceTick();
        int ticksSinceGhost = data.getTicksTracked() - data.getLastGhostBlockTick();
        if (ticksSincePlace < 5 || ticksSinceGhost < 5) {
            buffer.decrease(player, 0.25);
            return;
        }

        // Shape-boundary rests (beds, slabs, farmland, snow layers...) are
        // already on the grid, but cache-miss cases land here too.
        if (WorldUtils.isAtShapeBoundary(data)) {
            buffer.decrease(player, 0.25);
            return;
        }

        final double y = wrapper.getY();
        final double yMod = Math.abs(y % 1.0);

        // Full block boundary, or any 1/64 grid line (all vanilla shapes are
        // multiples of 1/16; the finer tolerance absorbs float drift).
        final boolean onGrid = yMod < 0.001D
                || yMod > 0.999D
                || (yMod % GRID) < 0.001D
                || (GRID - (yMod % GRID)) < 0.001D;

        if (!onGrid) {
            if (buffer.increase(player, 1.0) > 5.0) {
                flag(data, String.format("Phantom ground (Y off the collision grid). Y: %.4f", y));
                buffer.reset(player, 2.0);
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
