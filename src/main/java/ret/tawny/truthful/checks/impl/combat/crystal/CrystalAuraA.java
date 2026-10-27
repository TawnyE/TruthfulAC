package ret.tawny.truthful.checks.impl.combat.crystal;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.entity.Player;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.compensation.CompensationTracker;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.action.PlayerBlockPlacePacketWrapper;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CrystalAuraA - place->break detection with positional correlation.
 *
 * The naive version of this check flags ANY attack within 40ms of ANY crystal
 * placement. Real crystal PvP is literally "place N+1, break N", so that
 * version flags exactly the playstyle it claims to detect. Here, a fast
 * place->break only counts when the attacked entity position matches the
 * crystal spawned by that placement. Breaking someone else's crystal fast is
 * neither tracked nor flagged.
 */
@CheckData(order = 'A', type = CheckType.CRYSTAL)
public final class CrystalAuraA extends Check {

    private static final long PLACEMENT_TTL_MS = 1000L;
    private static final double POSITION_MATCH_RADIUS_SQ = 2.25D; // 1.5 blocks
    private static final int MAX_TRACKED_PLACEMENTS = 16;

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private final Map<UUID, Deque<CrystalPlacement>> placements = new ConcurrentHashMap<>();

    private record CrystalPlacement(double x, double y, double z, long timeMs) {}

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        final Player player = (Player) event.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null) return;

        // 1. Track Crystal Placement (cached hand state + packet positions only;
        //    no live Bukkit inventory reads on the netty thread).
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            if (!data.isHoldingCrystal()) return;

            PlayerBlockPlacePacketWrapper place = data.getCurrentBlockPlacement();
            if (place == null || place.getBlockPosition() == null) return;

            Vector3i clicked = place.getBlockPosition();
            BlockFace face = place.getBlockFace();
            if (face == null) face = BlockFace.UP;

            // The crystal entity spawns centered on the face the player clicked.
            double cx = clicked.getX() + face.getModX() + 0.5D;
            double cy = clicked.getY() + face.getModY();
            double cz = clicked.getZ() + face.getModZ() + 0.5D;

            Deque<CrystalPlacement> deque = placements.computeIfAbsent(
                    player.getUniqueId(), k -> new ArrayDeque<>());
            deque.addLast(new CrystalPlacement(cx, cy, cz, System.currentTimeMillis()));
            while (deque.size() > MAX_TRACKED_PLACEMENTS) deque.pollFirst();

            long cutoff = System.currentTimeMillis() - PLACEMENT_TTL_MS;
            while (!deque.isEmpty() && deque.peekFirst().timeMs < cutoff) deque.pollFirst();
        }

        // 2. Attack on a crystal: only "our own freshly placed crystal" counts.
        else if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
            if (interact.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;

            Deque<CrystalPlacement> deque = placements.get(player.getUniqueId());
            if (deque == null || deque.isEmpty()) return;

            CompensationTracker tracker = Truthful.getInstance().getCompensationTracker();
            if (tracker == null) return;
            CompensationTracker.CompensatedEntity target = tracker.getEntityData(interact.getEntityId());
            if (target == null) return;
            CompensationTracker.EntitySnapshot snap = target.getLatest();
            if (snap == null) return;

            long now = System.currentTimeMillis();
            Long matchedPlaceTime = null;

            for (CrystalPlacement p : deque) {
                double dx = snap.x - p.x;
                double dy = snap.y - p.y;
                double dz = snap.z - p.z;
                if (dx * dx + dz * dz <= POSITION_MATCH_RADIUS_SQ && dy >= -1.0 && dy <= 2.0) {
                    matchedPlaceTime = p.timeMs;
                    break;
                }
            }

            if (matchedPlaceTime == null) {
                // Attacking a crystal that isn't ours — normal play.
                buffer.decrease(player, 0.1);
                return;
            }

            long diff = now - matchedPlaceTime;
            if (diff < 100) {
                // < 50ms = same-tick place+detonate. < 40ms is consistent bot cadence
                // for the crystal this very client just placed.
                if (diff < 40) {
                    if (buffer.increase(player, 2.0) > 6.0) {
                        flag(data, String.format("Fast Crystal (self place->break) delay=%dms", diff));
                        buffer.reset(player, 3.0);
                    }
                } else {
                    buffer.decrease(player, 0.25);
                }
            } else {
                buffer.decrease(player, 0.1);
            }
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        placements.remove(event.getPlayer().getUniqueId());
    }
}
