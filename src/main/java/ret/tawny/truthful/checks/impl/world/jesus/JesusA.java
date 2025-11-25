package ret.tawny.truthful.checks.impl.world.jesus;

import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.world.WorldUtils;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'A', type = CheckType.JESUS)
@SuppressWarnings("unused")
public final class JesusA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(15.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        if (!relMovePacketWrapper.isPositionUpdate())
            return;

        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null)
            return;

        if (player.getAllowFlight() || player.isFlying() || player.isInsideVehicle())
            return;
        if (data.isTeleportTick())
            return;

        // FIX: Reduced Vehicle Exit Immunity (40 -> 10)
        if (data.getTicksTracked() - data.getLastVehicleExitTick() < 10) {
            buffer.decrease(player, 0.5);
            return;
        }

        if (player.isSwimming())
            return;

        if (data.getTicksTracked() - data.getLastGhostBlockTick() < 10) {
            buffer.decrease(player, 1.0);
            return;
        }

        if (!data.isInLiquid()) {
            buffer.decrease(player, 0.5);
            return;
        }

        // Exiting Liquid exemption
        if (data.wasInLiquid() && WorldUtils.safeGround(player)) {
            buffer.decrease(player, 0.5);
            return;
        }

        if (isInBubbleColumn(player))
            return;

        if (WorldUtils.nearBlock(player)) {
            buffer.decrease(player, 0.25);
            return;
        }

        final double deltaY = data.getDeltaY();
        final double deltaXZ = data.getDeltaXZ();

        boolean solidLiquid = (Math.abs(deltaY) < 0.005) && deltaXZ > 0.05;

        double speedLimit = 0.15 + (getDepthStriderLevel(player) * 0.05);

        if (deltaY > 0)
            speedLimit += 0.1;

        boolean tooFast = deltaXZ > speedLimit && !data.isLastGround();

        if (solidLiquid) {
            if (buffer.increase(player, 1.0) > 15.0) {
                flag(data, String.format("Walking flat on water. dY: %.4f", deltaY));
                buffer.reset(player, 5.0);
            }
        } else if (tooFast) {
            if (buffer.increase(player, 1.0) > 15.0) {
                flag(data, String.format("Moving too fast in water. Hz: %.3f, Limit: %.3f", deltaXZ, speedLimit));
                buffer.reset(player, 5.0);
            }
        } else {
            buffer.decrease(player, 0.25);
        }
    }

    private boolean isInBubbleColumn(Player player) {
        try {
            Block b = player.getLocation().getBlock();
            return b.getType().name().contains("BUBBLE_COLUMN");
        } catch (Exception e) {
            return false;
        }
    }

    private int getDepthStriderLevel(Player player) {
        ItemStack boots = player.getInventory().getBoots();
        if (boots == null)
            return 0;
        return boots.getEnchantmentLevel(Enchantment.DEPTH_STRIDER);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}