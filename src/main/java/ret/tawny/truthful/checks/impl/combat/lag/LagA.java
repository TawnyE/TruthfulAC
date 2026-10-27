package ret.tawny.truthful.checks.impl.combat.lag;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.compensation.CompensationTracker;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.hitbox.SimpleHitbox;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@CheckData(order = 'A', type = CheckType.LAG)
public final class LagA extends Check {

    private static final int MAX_ATTACK_AGE_TICKS    = 40;
    private static final double BACKTRACK_TOLERANCE   = 0.45D;
    private static final double HARD_CEILING          = 7.5D;
    private static final double RAY_MAX_DIST          = 6.5D;

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    // FIXED: Use queue to prevent multi-attack overwrite race condition per movement tick
    private final Map<UUID, Queue<AttackRecord>> pendingAttacks = new ConcurrentHashMap<>();

    private record AttackRecord(
            int entityId,
            long timestamp,
            double x, double y, double z,
            float yaw, float pitch,
            int ticksTracked,
            long pingAtAttack) {}

    @Override
    public void handlePacketPlayerReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;
        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;

        Player player = (Player) event.getPlayer();
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isExempt()
                || data.getGameMode() == GameMode.CREATIVE
                || Truthful.getInstance().isBedrockPlayer(player)) return;

        pendingAttacks.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentLinkedQueue<>())
                .add(new AttackRecord(
                        wrapper.getEntityId(),
                        System.currentTimeMillis(),
                        data.getX(), data.getY(), data.getZ(),
                        data.getYaw(), data.getPitch(),
                        data.getTicksTracked(),
                        data.getPing()
                ));
    }

    @Override
    public void handleRelMove(final RelMovePacketWrapper wrapper) {
        Player player = wrapper.getPlayer();
        PlayerData data = wrapper.getPlayerData();
        if (data == null) return;

        Queue<AttackRecord> queue = pendingAttacks.get(player.getUniqueId());
        if (queue == null || queue.isEmpty()) return;

        AttackRecord record;
        while ((record = queue.poll()) != null) {
            if (data.getTicksTracked() - record.ticksTracked > MAX_ATTACK_AGE_TICKS) continue;

            CompensationTracker tracker = Truthful.getInstance().getCompensationTracker();
            if (tracker == null) continue;

            CompensationTracker.CompensatedEntity comp = tracker.getEntityData(record.entityId);
            if (comp == null) continue;

            // One-way latency (RTT/2): that's the point in time whose hitbox the
            // client was actually seeing when it swung.
            int backtrackTicks = (int) Math.min(record.pingAtAttack / 100L, CompensationTracker.MAX_BACKTRACK_TICKS);
            int currentTick = tracker.getCurrentTick();

            SimpleHitbox historical = comp.getHitboxAt(backtrackTicks, currentTick);
            CompensationTracker.EntitySnapshot currentSnap = comp.getLatest();
            if (historical == null || currentSnap == null) continue;

            double eyeH = record.y + data.getEyeHeight(false, data.isSneaking(), data.isSwimming());
            double curEyeH = data.getY() + data.getEyeHeight(false, data.isSneaking(), data.isSwimming());
            Vector histOrigin = new Vector(record.x, eyeH, record.z);
            Vector curOrigin  = new Vector(data.getX(), curEyeH, data.getZ());

            double histDist = rayMin(historical, histOrigin, record.yaw, record.pitch);
            double currDist = rayMin(currentSnap.toHitbox(), curOrigin, data.getYaw(), data.getPitch());

            double maxReach = Truthful.getInstance().getVersionManager().getAdapter().getEntityInteractionRange(player)
                    + (data.isSprinting() ? 0.05 : 0.0)
                    + (data.hasVelocity() ? 0.10 : 0.0)
                    + (record.pingAtAttack > 50 ? Math.min(0.15, record.pingAtAttack * 0.001) : 0.0)
                    + (comp.isPlayer ? 0.08 : 0.25);

            currDist = Math.min(currDist, HARD_CEILING);

            if (currDist > maxReach + 0.15D && histDist < maxReach - BACKTRACK_TOLERANCE) {
                double severity = (currDist - maxReach) * 1.8D;
                if (buffer.increase(player, severity) > 6.0) {
                    flag(data, String.format("BacktrackAssist curr=%.3f hist=%.3f max=%.3f ping=%d",
                            currDist, histDist, maxReach, record.pingAtAttack));
                    buffer.reset(player, 3.5);
                }
                continue;
            }

            // NOTE: no standalone reach enforcement here by design - LagA
            // detects delayed-packet (backtrack) abuse patterns only. Plain
            // reach belongs to ReachA's two-stage verdict.
            buffer.decrease(player, 0.6);
        }
    }

    private double rayMin(SimpleHitbox box, Vector origin, float yaw, float pitch) {
        double ry = Math.toRadians(yaw);
        double rp = Math.toRadians(pitch);
        double cosP = Math.cos(rp);
        Vector dir = new Vector(-cosP * Math.sin(ry), -Math.sin(rp), cosP * Math.cos(ry));
        boolean hit = box.intersectsRay(origin, dir, RAY_MAX_DIST);
        double dBox = box.distance(origin);
        if (hit) return Math.min(dBox, rayEntryDistance(box, origin, dir));
        // Origin inside/on the box means the attack is a clean hit at any distance metric.
        // Previously returned -1.0 here, which the caller misread as proof of backtrack abuse.
        return box.distance(origin) > 0.03D ? box.distance(origin) : 0.0;
    }

    private double rayEntryDistance(SimpleHitbox box, Vector origin, Vector dir) {
        double invX = 1.0 / dir.getX();
        double invY = 1.0 / dir.getY();
        double invZ = 1.0 / dir.getZ();

        double tMin = Math.min((box.minX - origin.getX()) * invX, (box.maxX - origin.getX()) * invX);
        double tMax = Math.max((box.minX - origin.getX()) * invX, (box.maxX - origin.getX()) * invX);

        double tyMin = Math.min((box.minY - origin.getY()) * invY, (box.maxY - origin.getY()) * invY);
        double tyMax = Math.max((box.minY - origin.getY()) * invY, (box.maxY - origin.getY()) * invY);
        if (tMin > tyMax || tyMin > tMax) return -1.0;
        tMin = Math.max(tMin, tyMin);
        tMax = Math.min(tMax, tyMax);

        double tzMin = Math.min((box.minZ - origin.getZ()) * invZ, (box.maxZ - origin.getZ()) * invZ);
        double tzMax = Math.max((box.minZ - origin.getZ()) * invZ, (box.maxZ - origin.getZ()) * invZ);
        if (tMin > tzMax || tzMin > tMax) return -1.0;
        tMin = Math.max(tMin, tzMin);
        tMax = Math.min(tMax, tzMax);

        if (Double.isNaN(tMin)) tMin = Double.NEGATIVE_INFINITY;
        if (Double.isNaN(tMax)) tMax = Double.POSITIVE_INFINITY;
        if (tMax < 0) return -1.0;
        return Math.max(0.0, tMin);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        pendingAttacks.remove(event.getPlayer().getUniqueId());
    }
}