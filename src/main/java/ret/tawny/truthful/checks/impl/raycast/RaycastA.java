package ret.tawny.truthful.checks.impl.raycast;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.world.WorldUtils;
import ret.tawny.truthful.wrapper.impl.client.action.PlayerBlockPlacePacketWrapper;

@CheckData(order = 'A', type = CheckType.RAYCAST)
@SuppressWarnings("unused")
public final class RaycastA extends Check {

    public RaycastA() {
        // Only listen to BlockPlace. Drop Item (Q) sends BlockDig, handled by GhostHandA.
        Truthful.getInstance().getScheduler().registerDispatcher(this::handleInteraction, PacketType.Play.Client.BLOCK_PLACE);
    }

    private void handleInteraction(final PacketEvent packetEvent) {
        if (!isEnabled()) return;

        final Player player = packetEvent.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null) return;

        final PlayerBlockPlacePacketWrapper wrapper = new PlayerBlockPlacePacketWrapper(packetEvent);
        final BlockFace face = wrapper.getBlockFace();
        final Block targetBlock = wrapper.getBlock();

        // --- FILTER: Invalid Interactions ---
        // 1. Face 255 (null) is an Air Click / Use Item interaction. Ignore.
        // 2. Air/Liquid clicks are not "Raycast" violations usually.
        if (face == null || targetBlock == null || targetBlock.getType() == Material.AIR || targetBlock.isLiquid()) {
            return;
        }

        // --- CALCULATE CLOSEST POINT ---
        // Prevents false flags when clicking corners of blocks.
        Location eyeLoc = player.getEyeLocation();
        Vector closestPoint = getClosestPoint(eyeLoc, targetBlock);

        double distance = eyeLoc.toVector().distance(closestPoint);

        // --- DYNAMIC REACH CALCULATION ---
        double maxReach = 4.5; // Base

        if (player.isSprinting()) maxReach += 0.3; // Sprint buffer
        maxReach += (data.getPing() * 0.003); // Ping buffer

        // Verticality buffer (reaching down is weird in 1.8)
        if (eyeLoc.getY() > targetBlock.getY() + 1.0) maxReach += 0.2;

        if (maxReach > 6.0) maxReach = 6.0; // Hard limit

        // Check 1: Reach
        if (distance > maxReach) {
            if (player.isInsideVehicle()) return; // Vehicle hitboxes are offset

            // Only flag if significantly over
            if (distance > maxReach + 0.2) {
                flag(data, String.format("Reach Distance. Dist: %.2f, Max: %.2f", distance, maxReach));
            }
        }
    }

    private Vector getClosestPoint(Location origin, Block block) {
        double minX = block.getX();
        double minY = block.getY();
        double minZ = block.getZ();
        double maxX = block.getX() + 1.0;
        double maxY = block.getY() + 1.0;
        double maxZ = block.getZ() + 1.0;

        double x = clamp(origin.getX(), minX, maxX);
        double y = clamp(origin.getY(), minY, maxY);
        double z = clamp(origin.getZ(), minZ, maxZ);

        return new Vector(x, y, z);
    }

    private double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}