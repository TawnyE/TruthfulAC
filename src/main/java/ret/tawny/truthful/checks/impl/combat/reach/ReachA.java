package ret.tawny.truthful.checks.impl.combat.reach;

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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ReachA - two stages, because the attack packet doesn't carry the
 * rotation the client swung with (that arrives one packet later).
 * Stage A: store where the attacker stood. Stage B: ray-cast with the
 * real look against the box history across the whole latency window,
 * taking the best of every legal view. Tolerance beyond vanilla is only
 * the physically-honest box margin - no arbitrary padding to farm.
 */
@CheckData(order = 'A', type = CheckType.REACH)
public final class ReachA extends Check {

    private static final double BOX_MARGIN = 0.03D;        // tick-skip + quantization slack
    private static final double THRESHOLD = 0.0005D;        // float precision only (Grim parity)
    private static final double RAY_LENGTH_PAD = 3.0D;      // ray runs reach+3 like vanilla
    private static final int MAX_QUEUED_ATTACKS = 10;

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private final Map<UUID, Deque<QueuedAttack>> queues = new ConcurrentHashMap<>();

    private record QueuedAttack(int entityId, double x, double y, double z) {}

    @Override
    public void handlePacketPlayerReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;
        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;

        if (!(event.getPlayer() instanceof Player player)) return;
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isExempt()
                || data.getGameMode() == GameMode.CREATIVE
                || data.getGameMode() == GameMode.SPECTATOR
                || Truthful.getInstance().isBedrockPlayer(player)) return;

        // Stage A: store the attacker position at attack time. Judging waits
        // for the movement packet that carries the rotation actually used.
        Deque<QueuedAttack> queue = queues.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        if (queue.size() < MAX_QUEUED_ATTACKS) {
            queue.add(new QueuedAttack(wrapper.getEntityId(),
                    data.getX(), data.getY(), data.getZ()));
        }
    }

    @Override
    public void handleRelMove(RelMovePacketWrapper wrapper) {
        final PlayerData data = wrapper.getPlayerData();
        if (data == null) return;
        final Player player = data.getPlayer();
        Deque<QueuedAttack> queue = queues.get(player.getUniqueId());
        if (queue == null || queue.isEmpty()) return;

        // Mid-setback/teleport: positions unreliable - drop, never flag.
        // NOTE: no velocity gate here (Grim doesn't gate reach on velocity).
        // During PvP every hit sends a velocity packet; a velocity gate
        // silences the check for 5+ ticks after every exchange, which is
        // why real reach cheats went undetected during fights.
        int ticksNow = data.getTicksTracked();
        if (data.isServerFrozen() || data.isTeleportPending()
                || data.isInsideVehicle() || data.isGliding()
                || data.getTicksSinceTeleport() < 3) {
            queue.clear();
            return;
        }

        CompensationTracker tracker = Truthful.getInstance().getCompensationTracker();
        if (tracker == null) {
            queue.clear();
            return;
        }

        double baseReach;
        try {
            baseReach = Truthful.getInstance().getVersionManager().getAdapter().getEntityInteractionRange(player);
        } catch (Throwable t) {
            baseReach = 3.0D;
        }

        // Stage B: the rotation used for this attack is now known.
        float[][] looks = {
                {data.getYaw(), data.getPitch()},
                {data.getLastYaw(), data.getPitch()},
                {data.getLastYaw(), data.getLastPitch()},
        };

        // Pose may lag the attack by a tick; the client raycasts from its
        // actual eye - try every plausible eye height (Grim parity).
        double[] eyeHeights = { data.getEyeHeightScaled(), 1.62D, 1.27D, 0.4D };

        long ping = data.getPing();
        int window = (int) Math.min(CompensationTracker.MAX_BACKTRACK_TICKS,
                Math.ceil(ping / 50.0D) + 3);
        int currentTick = tracker.getCurrentTick();

        QueuedAttack attack;
        while ((attack = queue.poll()) != null) {
            judge(data, tracker, attack, looks, eyeHeights, baseReach, window, currentTick, ping);
        }
    }

    private void judge(PlayerData data, CompensationTracker tracker, QueuedAttack attack,
                       float[][] looks, double[] eyeHeights, double baseReach,
                       int window, int currentTick, long ping) {
        CompensationTracker.CompensatedEntity comp = tracker.getEntityData(attack.entityId());
        if (comp == null) {
            // Untracked entity: can't verify, skip silently (a loud flag
            // here would punish every NPC plugin in existence).
            buffer.decrease(data.getPlayer(), 0.25D);
            return;
        }

        double minHit = Double.MAX_VALUE;
        double closestMiss = Double.MAX_VALUE;
        boolean anyBox = false;

        // Debug capture: winning combination + per-rewind box distances so
        // a single flag shows whether the BOX is at the wrong place or the
        // RAY is looking the wrong way.
        int bestRewind = -1;
        double bestEyeH = 0;
        double[] variantDists = new double[looks.length];
        java.util.Arrays.fill(variantDists, Double.MAX_VALUE);
        StringBuilder rewindDists = new StringBuilder(64);
        String boxDebug = "n/a";

        for (int rewind = 0; rewind <= window; rewind++) {
            SimpleHitbox box = comp.getHitboxAt(rewind, currentTick);
            if (box == null) continue;
            anyBox = true;
            SimpleHitbox expanded = box.cloneExpand(BOX_MARGIN);

            double rewindBest = Double.MAX_VALUE;
            double rayLen = baseReach + RAY_LENGTH_PAD;
            for (double eyeH : eyeHeights) {
                Vector eye = new Vector(attack.x(), attack.y() + eyeH, attack.z());
                double boxDist = expanded.distance(eye);
                if (boxDist < closestMiss) closestMiss = boxDist;
                if (expanded.isVecInside(eye)) {
                    minHit = 0.0D;
                    bestRewind = rewind;
                    bestEyeH = eyeH;
                    break;
                }
                for (int li = 0; li < looks.length; li++) {
                    Vector dir = getLookVector(looks[li][0], looks[li][1]);
                    Double d = rayInterceptDistance(expanded, eye, dir, rayLen);
                    if (d != null && d < minHit) {
                        minHit = d;
                        bestRewind = rewind;
                        bestEyeH = eyeH;
                        boxDebug = String.format("(%.2f,%.2f,%.2f)",
                                expanded.minX, expanded.minY, expanded.minZ);
                    }
                    if (d != null && d < variantDists[li]) variantDists[li] = d;
                    if (d != null && d < rewindBest) rewindBest = d;
                }
                if (minHit == 0.0D) break;
            }
            if (rewindDists.length() > 0) rewindDists.append(',');
            rewindDists.append(rewindBest == Double.MAX_VALUE ? "-" : String.format("%.2f", rewindBest));
            if (minHit == 0.0D) break;
        }

        if (!anyBox) return; // World state unknown - never flag the unknown.

        String entityDebug = String.format("ent=(id:%d,pl:%b,uuid:%s)",
                attack.entityId(), comp.isPlayer, comp.uuid != null ? "y" : "n");

        String debug = String.format("%s miss=%.2f rw=[%s]",
                entityDebug, closestMiss, rewindDists);

        // DESYNC SANITY (Grim's principle: never flag what you can't verify):
        // a miss beyond ~3 blocks is a TRACKING failure, not a cheat. For
        // non-player entities the bound is even tighter - NPC plugins move
        // entities through NMS paths the packet tracker can't always follow.
        if (closestMiss > 3.0D || (!comp.isPlayer && closestMiss > 1.5D)) {
            resyncEntityFromLive(data, comp);
            buffer.decrease(data.getPlayer(), 0.5D);
            return;
        }

        if (minHit == Double.MAX_VALUE) {
            // Ray missed every legal view of the target: HitboxA owns the
            // out-of-sight verdict (Grim's ResultType.HITBOX routing).
            ret.tawny.truthful.checks.impl.combat.hitbox.HitboxA.routeMiss(
                    data, closestMiss, ping, window, debug, comp.isPlayer);
            buffer.decrease(data.getPlayer(), 0.25);
            return;
        }

        // Non-player entities: their packet positions are unreliable (NPC
        // plugin NMS movement, render offsets). Reach on them is
        // BLATANT-only: the resync-and-skip path handles desyncs, and only
        // egregious overage (aura on mobs) can flag.
        if (!comp.isPlayer && minHit > baseReach + 1.5D) {
            flag(data, String.format("Reach dist=%.3f max=%.3f ping=%d window=%d %s",
                    minHit, baseReach, ping, window, debug));
            buffer.reset(data.getPlayer(), 1.0D);
            return;
        }
        if (!comp.isPlayer) {
            buffer.decrease(data.getPlayer(), 0.5);
            return;
        }

        // The box margin already covers tick-skip and quantization; the only
        // extra is float precision. No arbitrary threshold on top.
        double maxReach = baseReach + THRESHOLD + (comp.isPlayer ? 0.0D : 0.25D);

        // Fake-entity discipline (Grim's blacklist philosophy): non-player
        // entities with a UUID (mannequin/training NPCs spawned via
        // SPAWN_ENTITY) commonly render ~0.5 blocks away from their packet
        // position. Strict verdicts on them are guesswork about the NPC
        // plugin, not the player - enforce BLATANT-only for non-players.
        boolean fakeEntity = !comp.isPlayer;
        double nonPlayerOver = fakeEntity ? 0.75D : 0.0D;

        if (minHit > maxReach + nonPlayerOver) {
            double over = minHit - maxReach;
            // Blatant (>0.05 over) flags fast; subtle reaches buffer briefly.
            if (over > 0.05D) {
                flag(data, String.format("Reach dist=%.3f max=%.3f ping=%d window=%d %s",
                        minHit, baseReach, ping, window, debug));
                buffer.reset(data.getPlayer(), 1.0D);
            } else {
                double severity = 1.0D + over * 4.0D;
                if (buffer.increase(data.getPlayer(), severity) > 3.5D) {
                    flag(data, String.format("Reach dist=%.3f max=%.3f ping=%d window=%d %s",
                            minHit, baseReach, ping, window, debug));
                    buffer.reset(data.getPlayer(), 1.5D);
                }
            }
        } else {
            buffer.decrease(data.getPlayer(), 0.5);
        }
    }

    /**
     * Main-thread entity resync for desync cases: reads the live entity
     * position and re-snapshots the tracker so subsequent hits judge against
     * the true position. NPC plugins that move entities outside the normal
     * packet pipeline cause this; the resync heals the tracker.
     */
    private void resyncEntityFromLive(PlayerData data, CompensationTracker.CompensatedEntity comp) {
        if (comp.uuid == null) return;
        Truthful.getInstance().getServerScheduler().runRegion(data.getPlayer(), () -> {
            try {
                org.bukkit.entity.Entity entity = org.bukkit.Bukkit.getEntity(comp.uuid);
                if (entity == null || !entity.isValid()) return;
                org.bukkit.util.BoundingBox box = entity.getBoundingBox();
                double x = (box.getMinX() + box.getMaxX()) * 0.5D;
                double y = box.getMinY();
                double z = (box.getMinZ() + box.getMaxZ()) * 0.5D;
                int tick = Truthful.getInstance().getCompensationTracker().getCurrentTick();
                double w = Math.max(box.getMaxX() - box.getMinX(), box.getMaxZ() - box.getMinZ());
                double h = box.getMaxY() - box.getMinY();
                if (w > 0 && h > 0) {
                    comp.snapshot(tick, x, y, z, w, h);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private static Double rayInterceptDistance(SimpleHitbox box, Vector origin, Vector dir, double maxDist) {
        double dx = Math.abs(dir.getX()) < 1e-9 ? 1e-9 : dir.getX();
        double dy = Math.abs(dir.getY()) < 1e-9 ? 1e-9 : dir.getY();
        double dz = Math.abs(dir.getZ()) < 1e-9 ? 1e-9 : dir.getZ();
        double invX = 1.0 / dx;
        double invY = 1.0 / dy;
        double invZ = 1.0 / dz;

        double t1 = (box.minX - origin.getX()) * invX;
        double t2 = (box.maxX - origin.getX()) * invX;
        double tMin = Math.min(t1, t2);
        double tMax = Math.max(t1, t2);

        double ty1 = (box.minY - origin.getY()) * invY;
        double ty2 = (box.maxY - origin.getY()) * invY;
        tMin = Math.max(tMin, Math.min(ty1, ty2));
        tMax = Math.min(tMax, Math.max(ty1, ty2));

        double tz1 = (box.minZ - origin.getZ()) * invZ;
        double tz2 = (box.maxZ - origin.getZ()) * invZ;
        tMin = Math.max(tMin, Math.min(tz1, tz2));
        tMax = Math.min(tMax, Math.max(tz1, tz2));

        if (Double.isNaN(tMin) || Double.isNaN(tMax)) return 0.0D;
        if (tMax >= Math.max(0.0, tMin) && tMin <= maxDist) {
            return Math.max(0.0, tMin);
        }
        return null;
    }

    private static Vector getLookVector(float yaw, float pitch) {
        double ry = Math.toRadians(yaw);
        double rp = Math.toRadians(pitch);
        double cosP = Math.cos(rp);
        return new Vector(-cosP * Math.sin(ry), -Math.sin(rp), cosP * Math.cos(ry));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        queues.remove(event.getPlayer().getUniqueId());
    }
}
