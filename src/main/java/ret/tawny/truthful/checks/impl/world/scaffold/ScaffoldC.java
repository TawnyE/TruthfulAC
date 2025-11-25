package ret.tawny.truthful.checks.impl.world.scaffold;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.action.PlayerBlockPlacePacketWrapper;

@CheckData(order = 'C', type = CheckType.SCAFFOLD)
@SuppressWarnings("unused")
public final class ScaffoldC extends Check {

    private final CheckBuffer buffer = new CheckBuffer(5.0);

    @Override
    public void handlePacketPlayerReceive(final PacketEvent event) {
        if (!event.getPacketType().equals(PacketType.Play.Client.BLOCK_PLACE)) return;

        final Player player = event.getPlayer();
        // Only check if sprinting (this check is for Sprint-Scaffold)
        if (!player.isSprinting() && !player.isGliding()) return;

        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isTeleportTick()) return;

        final PlayerBlockPlacePacketWrapper wrapper = new PlayerBlockPlacePacketWrapper(event);
        final BlockFace face = wrapper.getBlockFace();

        if (face == null || face == BlockFace.UP || face == BlockFace.DOWN || face == BlockFace.SELF) {
            return;
        }

        // Get Face Normal (Direction the face is pointing)
        Vector normal = new Vector(face.getModX(), face.getModY(), face.getModZ());

        // Get Player Horizontal Velocity
        Vector velocity = new Vector(data.getDeltaX(), 0, data.getDeltaZ());

        // Check dot product
        // If I place on EAST face (Normal 1,0,0), I must be looking/moving WEST.
        // Velocity (-1, 0, 0). Normal (1, 0, 0). Dot = -1. (OK).
        // Scaffold: Sprint East (+1). Place East (+1). Dot = +1. (BAD).

        double dot = velocity.dot(normal);

        // Threshold: 0.0 allows perfectly perpendicular movement.
        // > 0.1 means actively moving in the direction of the face (impossible).
        if (dot > 0.15) {
            if (buffer.increase(player, 1.0) > 5.0) {
                flag(data, String.format("Impossible sprinting direction. Dot: %.3f, Face: %s", dot, face));
            }
        } else {
            buffer.decrease(player, 0.25);
        }
    }
}