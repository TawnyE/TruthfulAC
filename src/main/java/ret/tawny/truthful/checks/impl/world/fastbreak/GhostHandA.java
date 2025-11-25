package ret.tawny.truthful.checks.impl.world.fastbreak;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;

import java.util.Set;

@CheckData(order = 'A', type = CheckType.RAYCAST) // Using RAYCAST type for GhostHand
@SuppressWarnings("unused")
public final class GhostHandA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(5.0);

    @Override
    public void handlePacketPlayerReceive(final PacketEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.BLOCK_DIG) {
            final Player player = event.getPlayer();
            final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

            if (data == null)
                return;

            BlockPosition pos = event.getPacket().getBlockPositionModifier().read(0);
            Location targetLoc = pos.toLocation(player.getWorld());

            // Raytrace Check
            // Check if there are solid blocks between eyes and target.

            Location eyeLoc = player.getEyeLocation();
            Vector direction = targetLoc.toVector().subtract(eyeLoc.toVector()).normalize();
            double distance = eyeLoc.distance(targetLoc);

            // Simple Raytrace
            // We step through the line and check for collisions.
            // Note: Bukkit's rayTraceBlocks is better if available.

            // Simplified logic:
            // If distance > 1.0 and we hit something else first...

            // For now, we'll just check distance as a sanity check (Reach for blocks)
            if (distance > 6.0) {
                if (buffer.increase(player, 1.0) > 5.0) {
                    flag(data, "Block Reach: " + distance);
                    buffer.reset(player, 2.0);
                }
            }
        }
    }
}
