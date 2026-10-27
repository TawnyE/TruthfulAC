package ret.tawny.truthful.checks.impl.world.scaffold;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.world.BlockPropertyRegistry;
import ret.tawny.truthful.wrapper.impl.client.action.PlayerBlockPlacePacketWrapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ScaffoldFace(D) - placement target legality. Runs on the placement
 * packet itself, before the server can echo the accepted block back - one
 * movement tick later the target cell legitimately holds the new block and
 * a solidity test would be meaningless. A vanilla placement always
 * resolves to a cell that is empty or replaceable, so a solid target cell
 * means the client placed into an occupied block: a ghost or desync
 * placement. Interactions are separated from placements via the hand and
 * interactable-block gates, liquid, replaceable and non-solid targets are
 * skipped, and repeated hits on the same cell inside one desync burst
 * collapse into a single increase. Face ordinals and cursor values belong
 * to BadPacket5 and are not re-validated here.
 */
@CheckData(order = 'D', type = CheckType.SCAFFOLD)
public final class ScaffoldFace extends Check {

    private static final double FLAG_BUFFER = 5.0D;
    private static final int REPEAT_TICKS = 20;

    private final CheckBuffer buffer = new CheckBuffer(FLAG_BUFFER);

    private static final class State {
        long lastCell = Long.MIN_VALUE;
        int lastTick = Integer.MIN_VALUE;
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null) return;

        final PlayerBlockPlacePacketWrapper place = data.getCurrentBlockPlacement();
        if (place == null || place.getBlockPosition() == null || place.getBlockFace() == null) {
            buffer.decrease(player, 0.05D);
            return;
        }

        if (data.isAllowFlight() || data.isFlying() || data.getTicksSinceTeleport() < 5) {
            buffer.decrease(player, 0.05D);
            return;
        }

        final Vector3i clicked = place.getBlockPosition();
        if (!data.getWorldCache().isChunkLoaded(clicked.getX() >> 4, clicked.getZ() >> 4)) {
            buffer.decrease(player, 0.05D);
            return;
        }

        final int clickedId = data.getWorldCache().getBlockId(clicked.getX(), clicked.getY(), clicked.getZ());
        final String clickedName = BlockPropertyRegistry.getName(clickedId);
        if (isReplaceable(clickedId, clickedName)) {
            buffer.decrease(player, 0.3D);
            return;
        }

        if (isInteractable(clickedName)
                || place.getHand() != InteractionHand.MAIN_HAND || !data.isHoldingBlock()) {
            buffer.decrease(player, 0.05D);
            return;
        }

        final BlockFace face = place.getBlockFace();
        final int targetX = clicked.getX() + face.getModX();
        final int targetY = clicked.getY() + face.getModY();
        final int targetZ = clicked.getZ() + face.getModZ();

        if (!data.getWorldCache().isChunkLoaded(targetX >> 4, targetZ >> 4)) {
            buffer.decrease(player, 0.05D);
            return;
        }

        final int targetId = data.getWorldCache().getBlockId(targetX, targetY, targetZ);
        if (targetId == 0 || BlockPropertyRegistry.isLiquid(targetId)
                || isReplaceable(targetId, BlockPropertyRegistry.getName(targetId))
                || !BlockPropertyRegistry.isSolid(targetId)) {
            buffer.decrease(player, 0.3D);
            return;
        }

        final State st = states.computeIfAbsent(player.getUniqueId(), k -> new State());
        final int tick = data.getTicksTracked();
        final long cellKey = packCell(targetX, targetY, targetZ);
        if (cellKey == st.lastCell && tick - st.lastTick <= REPEAT_TICKS) {
            st.lastTick = tick;
            return;
        }
        st.lastCell = cellKey;
        st.lastTick = tick;

        if (buffer.increase(player, 1.0D) > FLAG_BUFFER) {
            flag(data, String.format("into solid (%d,%d,%d) %s", targetX, targetY, targetZ,
                    BlockPropertyRegistry.getNameFast(targetId)));
            buffer.reset(player, 2.5D);
        }
    }

    private static long packCell(final int x, final int y, final int z) {
        return ((x & 0x3FFFFFFL) << 38) | ((z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
    }

    private static boolean isReplaceable(final int globalId, final String name) {
        if (globalId == 0) return true;
        if (BlockPropertyRegistry.isLiquid(globalId)) return true;
        return name.equals("SNOW") || name.equals("GRASS") || name.equals("SHORT_GRASS")
                || name.equals("TALL_GRASS") || name.equals("FERN") || name.equals("LARGE_FERN")
                || name.equals("DEAD_BUSH") || name.equals("FIRE") || name.endsWith("_AIR")
                || name.endsWith("_FIRE");
    }

    private static boolean isInteractable(final String name) {
        return name.contains("CHEST") || name.contains("SHULKER") || name.contains("BARREL")
                || name.contains("FURNACE") || name.contains("SMOKER") || name.contains("DISPENSER")
                || name.contains("DROPPER") || name.contains("HOPPER") || name.contains("ANVIL")
                || name.contains("CARTOGRAPHY") || name.contains("GRINDSTONE") || name.contains("SMITHING")
                || name.contains("STONECUTTER") || name.contains("LOOM") || name.contains("ENCHANTING")
                || name.contains("DOOR") || name.contains("FENCE_GATE") || name.contains("BUTTON")
                || name.contains("LEVER") || name.contains("BELL") || name.contains("LECTERN")
                || name.contains("CAMPFIRE") || name.contains("CAULDRON") || name.contains("BREWING")
                || name.contains("BEACON") || name.contains("NOTE_BLOCK") || name.contains("REPEATER")
                || name.contains("COMPARATOR") || name.contains("CAKE") || name.contains("POTTED")
                || name.contains("FLOWER_POT") || name.contains("SIGN") || name.contains("JUKEBOX")
                || name.contains("COMPOSTER") || name.contains("BOOKSHELF") || name.contains("VAULT")
                || name.contains("CRAFTER") || name.contains("DECORATED_POT") || name.contains("RESPAWN")
                || name.contains("DRAGON_EGG") || name.contains("BERRY")
                || (name.contains("BED") && !name.contains("BEDROCK"));
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
        buffer.remove(event.getPlayer());
    }
}
