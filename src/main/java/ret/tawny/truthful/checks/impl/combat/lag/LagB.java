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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@CheckData(order = 'B', type = CheckType.LAG)
public final class LagB extends Check {

    private static final double MIN_REACH_TO_TRIGGER = 3.1D;
    private static final int WINDOW_MS = 8000;
    private static final int MIN_DESYNC_HITS = 3;

    private final CheckBuffer buffer = new CheckBuffer(8.0);
    private final Map<UUID, Deque<RecentHit>> recentHits = new ConcurrentHashMap<>();

    private record RecentHit(double dist, long time, int entityId) {}

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

        int entityId = wrapper.getEntityId();
        CompensationTracker tracker = Truthful.getInstance().getCompensationTracker();
        if (tracker == null) return;

        CompensationTracker.CompensatedEntity comp = tracker.getEntityData(entityId);
        if (comp == null) return;

        long ping = data.getPing();
        // One-way latency (RTT/2), not full RTT: full RTT rewinds 2x too far.
        int tickDelay = (int) Math.min(CompensationTracker.MAX_BACKTRACK_TICKS, Math.max(0, Math.ceil(ping / 100.0)));
        int currentTick = tracker.getCurrentTick();

        // FIXED: Check against backtracked hitbox window to avoid false flagging during knockback interpolation
        SimpleHitbox serverBox = comp.getHitboxAt(tickDelay, currentTick);
        if (serverBox == null) return;

        serverBox.expand(0.08);

        double eyeX = data.getX();
        double eyeY = data.getY() + data.getEyeHeight(false, data.isSneaking(), data.isSwimming());
        double eyeZ = data.getZ();

        double ry = Math.toRadians(data.getYaw());
        double rp = Math.toRadians(data.getPitch());
        double cosP = Math.cos(rp);
        Vector look = new Vector(-cosP * Math.sin(ry), -Math.sin(rp), cosP * Math.cos(ry));

        double rayDist = rayHitDistance(serverBox, new Vector(eyeX, eyeY, eyeZ), look);
        if (rayDist < 0) rayDist = serverBox.distance(new Vector(eyeX, eyeY, eyeZ));

        double maxReach = Truthful.getInstance().getVersionManager().getAdapter().getEntityInteractionRange(player)
                + (data.isSprinting() ? 0.05 : 0)
                + (ping > 50 ? Math.min(0.15, ping * 0.001) : 0.0);

        if (rayDist < MIN_REACH_TO_TRIGGER) return;

        double normalizedReach = rayDist - maxReach;
        if (normalizedReach <= 0.02D) return;

        boolean lookDesync = rayDist > maxReach + 0.8;
        boolean speedDesync = data.hasVelocity() || data.getDeltaXZ() > 0.35D;

        if (!lookDesync || !speedDesync) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        Deque<RecentHit> deque = recentHits.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        deque.addLast(new RecentHit(rayDist, now, entityId));
        long cutoff = now - WINDOW_MS;
        while (!deque.isEmpty() && deque.peekFirst().time < cutoff) deque.removeFirst();
        if (deque.isEmpty()) recentHits.remove(uuid);

        int desyncHitCount = 0;
        for (RecentHit hit : deque) {
            if (hit.dist > maxReach + 0.5D) desyncHitCount++;
        }

        if (desyncHitCount >= MIN_DESYNC_HITS) {
            if (buffer.increase(player, 0.8) > 5.5) {
                flag(data, String.format("EntityDesync hits=%d maxReach=%.2f bestReach=%.3f",
                        desyncHitCount, maxReach, rayDist));
                buffer.reset(player, 3.5);
            }
        }
    }

    private double rayHitDistance(SimpleHitbox box, Vector origin, Vector look) {
        double invX = 1.0 / look.getX();
        double invY = 1.0 / look.getY();
        double invZ = 1.0 / look.getZ();

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
        recentHits.remove(event.getPlayer().getUniqueId());
    }
}