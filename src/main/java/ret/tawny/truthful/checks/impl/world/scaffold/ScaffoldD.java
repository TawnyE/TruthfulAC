package ret.tawny.truthful.checks.impl.world.scaffold;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.action.PlayerBlockPlacePacketWrapper;

@CheckData(order = 'D', type = CheckType.SCAFFOLD)
@SuppressWarnings("unused")
public final class ScaffoldD extends Check {

    private final CheckBuffer buffer = new CheckBuffer(5.0);

    @Override
    public void handlePacketPlayerReceive(final PacketEvent event) {
        if (!event.getPacketType().equals(PacketType.Play.Client.BLOCK_PLACE))
            return;

        final Player player = event.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isTeleportTick())
            return;

        // --- FIX 1: MUST HOLD BLOCK ---
        // In 1.8, interacting with swords, food, or air sends a BLOCK_PLACE packet.
        // We must verify the player is actually trying to place a block.
        ItemStack handItem = player.getItemInHand();
        if (handItem == null || !handItem.getType().isBlock() || handItem.getType() == Material.AIR) {
            return;
        }

        // Only check if moving fast enough (Sprinting or fast walking)
        if (!player.isSprinting() && data.getDeltaXZ() < 0.22)
            return;

        final PlayerBlockPlacePacketWrapper wrapper = new PlayerBlockPlacePacketWrapper(event);
        float pitch = data.getPitch();

        // --- FIX 2: Vertical Calculation ---
        // Adjust player Y for jumping. When jumping, your Y increases but you might still place
        // on the floor. We check if the block is *significantly* below the feet.
        double playerY = player.getLocation().getY();
        double blockY = wrapper.getBlock().getY();

        // If block is at feet level or higher, it's not bridging under self.
        if (blockY >= playerY - 0.5) {
            buffer.decrease(player, 0.5);
            return;
        }

        // Exemption: Placing ON TOP of a block (Jumping and placing under feet)
        if (wrapper.getBlockFace() == BlockFace.UP) {
            buffer.decrease(player, 0.25);
            return;
        }

        // Range of "Looking Forward"
        boolean lookingForward = pitch < 60.0F && pitch > -60.0F;

        if (lookingForward && player.isSprinting()) {
            // If sprinting forward, looking forward, but placing below us (bridging),
            // and NOT placing on the top face of a block (handled above), it's suspicious.

            if (buffer.increase(player, 1.0) > 5.0) {
                flag(data, String.format("Sprint Bridging without looking down. Pitch: %.1f, Face: %s",
                        pitch, wrapper.getBlockFace()));
            }
        } else {
            buffer.decrease(player, 0.5);
        }
    }
}