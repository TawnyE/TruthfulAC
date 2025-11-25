package ret.tawny.truthful.checks.impl.world.scaffold;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.action.PlayerBlockPlacePacketWrapper;

@CheckData(order = 'A', type = CheckType.SCAFFOLD)
@SuppressWarnings("unused")
public final class ScaffoldA extends Check {

    @Override
    public void handlePacketPlayerReceive(final PacketEvent event) {
        if (!event.getPacketType().equals(PacketType.Play.Client.BLOCK_PLACE)) return;

        final Player player = event.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null) return;

        final PlayerBlockPlacePacketWrapper wrapper = new PlayerBlockPlacePacketWrapper(event);
        final BlockFace face = wrapper.getBlockFace();
        final Block block = wrapper.getBlock();

        if (face == null || face == BlockFace.SELF) return;

        // --- GEOMETRY CHECK ---
        // Instead of raytracing, we check relative position.
        // If placing on the SOUTH face (Z+), the player should generally be on the South side (Z > BlockZ).

        Location eyeLoc = player.getEyeLocation();
        double dx = eyeLoc.getX() - (block.getX() + 0.5);
        double dy = eyeLoc.getY() - (block.getY() + 0.5);
        double dz = eyeLoc.getZ() - (block.getZ() + 0.5);

        // Tolerance: 0.5 (block extent) + 0.2 (reach around buffer)
        // If we are excessively on the "wrong side" of the block for the face we clicked, flag.
        boolean impossible = false;

        switch (face) {
            case NORTH: // Face is at Z - 0.5. We should be Z < 0.5 approx.
                // If we are at Z > 0.8 (Far South), we can't see North face.
                if (dz > 0.8) impossible = true;
                break;
            case SOUTH: // Face is at Z + 0.5.
                if (dz < -0.8) impossible = true;
                break;
            case WEST: // Face is at X - 0.5
                if (dx > 0.8) impossible = true;
                break;
            case EAST: // Face is at X + 0.5
                if (dx < -0.8) impossible = true;
                break;
            case UP: // Face is at Y + 0.5. We must be above Y - 0.5 roughly.
                if (dy < -0.8) impossible = true;
                break;
            case DOWN: // Face is at Y - 0.5. We must be below Y + 0.5 roughly.
                if (dy > 0.8) impossible = true;
                break;
        }

        if (impossible) {
            // Final check: Raytrace (ScaffoldI) handles precise aim.
            // This check is just for GROSS violations (e.g. placing on the back of a block through the block).
            flag(data, "Placed on impossible block face relative to position. Face: " + face);
        }
    }
}