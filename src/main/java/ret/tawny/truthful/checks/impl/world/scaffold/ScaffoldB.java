package ret.tawny.truthful.checks.impl.world.scaffold;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.entity.Player;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.world.BlockUtils;
import ret.tawny.truthful.wrapper.impl.client.action.PlayerBlockPlacePacketWrapper;

@CheckData(order = 'B', type = CheckType.SCAFFOLD)
@SuppressWarnings("unused")
public final class ScaffoldB extends Check {

    // Tolerance for floating point errors
    private static final double EPSILON = 1.0E-4;

    @Override
    public void handlePacketPlayerReceive(final PacketEvent event) {
        if(!event.getPacketType().equals(PacketType.Play.Client.BLOCK_PLACE)) return;

        final Player player = event.getPlayer();
        final PlayerData playerData = Truthful.getInstance().getDataManager().getPlayerData(player);
        if(playerData == null) return;

        final PlayerBlockPlacePacketWrapper wrapper = new PlayerBlockPlacePacketWrapper(event);

        if (BlockUtils.isAbnormal(wrapper.getBlock().getType())) return;

        if (!this.validate(wrapper)) {
            flag(playerData, "Invalid block hit vector. " + wrapper.getHitVec());
        }
    }

    private boolean validate(final PlayerBlockPlacePacketWrapper wrapper) {
        final double facingX = wrapper.getHitVec().getX();
        final double facingY = wrapper.getHitVec().getY();
        final double facingZ = wrapper.getHitVec().getZ();

        // Check bounds with epsilon
        if (facingX < -EPSILON || facingX > 1.0 + EPSILON ||
                facingY < -EPSILON || facingY > 1.0 + EPSILON ||
                facingZ < -EPSILON || facingZ > 1.0 + EPSILON) {
            return false;
        }

        if (wrapper.getBlockFace() == null) return true;

        // Check face alignment
        // If we click the NORTH face, Z must be 0 (or close to it).
        return switch (wrapper.getBlockFace()) {
            case NORTH -> Math.abs(facingZ) < EPSILON;
            case SOUTH -> Math.abs(facingZ - 1.0) < EPSILON;
            case WEST -> Math.abs(facingX) < EPSILON;
            case EAST -> Math.abs(facingX - 1.0) < EPSILON;
            case DOWN -> Math.abs(facingY) < EPSILON;
            case UP -> Math.abs(facingY - 1.0) < EPSILON;
            default -> true;
        };
    }
}