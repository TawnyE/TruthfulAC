package ret.tawny.truthful.compensation;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.utils.hitbox.SimpleHitbox;
import ret.tawny.truthful.utils.tick.ITickable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CompensationTracker implements ITickable {

    public int getCurrentTick() {
        return tickCounter;
    }

    private static final int HISTORY_SIZE = 30;
    public static final int MAX_BACKTRACK_TICKS = 8;
    private static final int CLEANUP_INTERVAL = 100;
    private static final int MAX_STALE_TICKS = 1200;
    private static final int DESTROY_GRACE_TICKS = 6;
    private static final double LIVE_RESYNC_DISTANCE_SQUARED = 4.0;

    /**
     * Vanilla hitbox geometry (width, height) keyed by the entity type's
     * resource key ("zombie", "iron_golem", ...). Unknown types intentionally
     * fall back to the historical 0.6x1.8 default so nothing regresses.
     */
    private static final Map<String, double[]> ENTITY_TYPE_SIZES = buildEntityTypeSizes();

    /**
     * Entity ids to their type key, learned from SPAWN_ENTITY packets by the
     * internal LOWEST-priority listener so handleSpawn can size honestly even
     * though the packet listener passes generic 0.6x1.8 hints.
     */
    private static final Map<Integer, String> SPAWNED_TYPE_KEYS = new ConcurrentHashMap<>();

    private static final double DEFAULT_WIDTH = 0.6D;
    private static final double DEFAULT_HEIGHT = 1.8D;
    private static final int SIZE_RESOLVE_MAX_ATTEMPTS = 100;

    private final Map<Integer, CompensatedEntity> compensationMap = new ConcurrentHashMap<>();
    private final Map<Integer, java.lang.ref.WeakReference<Entity>> entityCache = new ConcurrentHashMap<>();
    private final Set<Integer> pendingSizeResolve = ConcurrentHashMap.newKeySet();
    private volatile int tickCounter = 0;

    public CompensationTracker() {
        try {
            PacketEvents.getAPI().getEventManager().registerListener(new SpawnTypeListener());
        } catch (Throwable ignored) {
            // PE unavailable: spawn sizing falls back to the hints + live resync.
        }
    }

    @Override
    public void tick() {
        tickCounter++;
        if (tickCounter % CLEANUP_INTERVAL == 0) {
            int currentTick = this.tickCounter;
            compensationMap.entrySet().removeIf(entry -> {
                if (entry.getValue().isExpired(currentTick)) {
                    entityCache.remove(entry.getKey());
                    return true;
                }
                return false;
            });
            SPAWNED_TYPE_KEYS.keySet().retainAll(compensationMap.keySet());
        }
        resolvePendingSizes();
    }

    public void handleSpawn(int id, UUID uuid, double x, double y, double z, double width, double height,
                            boolean isPlayer) {
        int currentTick = this.tickCounter;
        // Spawn packets arrive with generic 0.6x1.8 hints; substitute honest
        // per-type geometry when the type is known. Non-players are still queued
        // for a main-thread live resync that reads baby/small/variant boxes the
        // table cannot know.
        boolean trustedHints = isPlayer || width != DEFAULT_WIDTH || height != DEFAULT_HEIGHT;
        double effectiveWidth = width;
        double effectiveHeight = height;
        if (!trustedHints) {
            String typeKey = SPAWNED_TYPE_KEYS.get(id);
            if (typeKey != null) {
                double[] size = ENTITY_TYPE_SIZES.get(typeKey);
                if (size != null) {
                    effectiveWidth = size[0];
                    effectiveHeight = size[1];
                }
            }
        }
        CompensatedEntity data = new CompensatedEntity(id, uuid, isPlayer, 3.0);
        data.sizeResolved = trustedHints;
        compensationMap.put(id, data);
        data.snapshot(currentTick, x, y, z, effectiveWidth, effectiveHeight);
        if (data.sizeResolved) {
            pendingSizeResolve.remove(id);
        } else {
            pendingSizeResolve.add(id);
        }
    }

    public void handleEntitySnapshot(Entity entity) {
        if (entity == null || !entity.isValid()) return;

        int currentTick = this.tickCounter;
        int entityId = entity.getEntityId();
        entityCache.put(entityId, new java.lang.ref.WeakReference<>(entity));
        EntityBox box = readEntityBox(entity);

        double baseReach = getBaseReachForType(entity.getType(), entity instanceof Player);

        CompensatedEntity data = compensationMap.computeIfAbsent(entityId,
                id -> new CompensatedEntity(id, entity.getUniqueId(), entity instanceof Player, baseReach));

        data.snapshot(currentTick, box.x, box.y, box.z, box.width, box.height);
        data.sizeResolved = true;
        pendingSizeResolve.remove(entityId);
    }

    private static double getBaseReachForType(EntityType type, boolean isPlayer) {
        if (isPlayer) return 3.0;
        if (type == null) return 3.0;
        // Name-based: since 1.21.2 boats are per-wood types (OAK_BOAT, ...) and
        // the generic BOAT enum is gone. String matching survives renames.
        if (type.name().contains("BOAT")) return 1.5;
        return switch (type) {
            case MINECART, CHEST_MINECART, HOPPER_MINECART, TNT_MINECART -> 1.0;
            case ITEM_FRAME, GLOW_ITEM_FRAME, ARMOR_STAND -> 3.0;
            case SHULKER -> 4.5;
            default -> 3.0;
        };
    }

    public void handleTeleport(int id, double x, double y, double z) {
        int currentTick = this.tickCounter;
        CompensatedEntity data = compensationMap.get(id);
        if (data != null) {
            data.snapshot(currentTick, x, y, z, data.lastWidth, data.lastHeight);
        }
    }

    public void handleRelMove(int id, double dx, double dy, double dz) {
        int currentTick = this.tickCounter;
        CompensatedEntity data = compensationMap.get(id);
        if (data != null) {
            EntitySnapshot last = data.getLatest();
            if (last != null) {
                data.snapshot(currentTick, last.x + dx, last.y + dy, last.z + dz, data.lastWidth, data.lastHeight);
            }
        }
    }

    public void handleVelocity(int id, double vx, double vy, double vz) {
        CompensatedEntity data = compensationMap.get(id);
        if (data != null) {
            data.velX = vx;
            data.velY = vy;
            data.velZ = vz;
            data.lastVelTick = this.tickCounter;
        }
    }

    public void handleDestroy(int id) {
        pendingSizeResolve.remove(id);
        CompensatedEntity data = compensationMap.get(id);
        if (data != null) {
            data.markDestroyed(this.tickCounter);
        }
    }

    public CompensatedEntity getEntityData(int entityId) {
        CompensatedEntity data = compensationMap.get(entityId);
        if (data == null)
            return null;

        int currentTick = this.tickCounter;
        if (data.isExpired(currentTick)) {
            compensationMap.remove(entityId, data);
            return null;
        }
        syncFromLiveEntityIfNeeded(entityId, data, currentTick);
        return data;
    }

    public boolean hasEntityData(int entityId) {
        return getEntityData(entityId) != null;
    }

    public static final class CompensatedEntity {
        public final int entityId;
        public final UUID uuid;
        public final boolean isPlayer;
        public final double baseReach;
        private volatile int lastSeenTick;
        private volatile int destroyedTick = -1;
        private final EntitySnapshot[] snapshots = new EntitySnapshot[HISTORY_SIZE];
        private final int[] ticks = new int[HISTORY_SIZE];
        private int head = 0;
        private int count = 0;

        volatile boolean sizeResolved;
        volatile int sizeResolveAttempts;
        volatile double velX, velY, velZ;
        volatile int lastVelTick = -1;

        // Read by netty threads without the snapshot lock: must be volatile.
        protected volatile double lastWidth = DEFAULT_WIDTH;
        protected volatile double lastHeight = DEFAULT_HEIGHT;

        public CompensatedEntity(int entityId, UUID uuid, boolean isPlayer, double baseReach) {
            this.entityId = entityId;
            this.uuid = uuid;
            this.isPlayer = isPlayer;
            this.baseReach = baseReach;
        }

        public synchronized void snapshot(int tick, double x, double y, double z, double width, double height) {
            this.lastSeenTick = tick;
            this.destroyedTick = -1;
            this.lastWidth = width;
            this.lastHeight = height;
            snapshots[head] = new EntitySnapshot(x, y, z, width, height);
            ticks[head] = tick;
            head = (head + 1) % HISTORY_SIZE;
            if (count < HISTORY_SIZE)
                count++;
        }

        public synchronized EntitySnapshot getLatest() {
            if (count == 0)
                return null;
            return snapshots[(head - 1 + HISTORY_SIZE) % HISTORY_SIZE];
        }

        public void markDestroyed(int tick) {
            this.destroyedTick = tick;
        }

        public boolean isExpired(int currentTick) {
            int destroyedAt = this.destroyedTick;
            if (destroyedAt >= 0 && currentTick - destroyedAt > DESTROY_GRACE_TICKS) {
                return true;
            }
            return currentTick - this.lastSeenTick > MAX_STALE_TICKS;
        }

        public synchronized void snapshot(int tick, Location loc, double width, double height) {
            snapshot(tick, loc.getX(), loc.getY(), loc.getZ(), width, height);
        }

        public synchronized SimpleHitbox getHitboxAt(int serverTickDelay) {
            return getHitboxAt(serverTickDelay, Truthful.getInstance().getCompensationTracker().getCurrentTick());
        }

        public synchronized SimpleHitbox getHitboxAt(int serverTickDelay, int currentTick) {
            if (serverTickDelay > MAX_BACKTRACK_TICKS)
                serverTickDelay = MAX_BACKTRACK_TICKS;
            if (serverTickDelay < 0)
                serverTickDelay = 0;

            int targetTick = currentTick - serverTickDelay;

            EntitySnapshot bestPrev = null;
            int bestPrevTick = Integer.MIN_VALUE;
            EntitySnapshot bestNext = null;
            int bestNextTick = Integer.MAX_VALUE;

            for (int i = 0; i < count; i++) {
                int tick = ticks[i];
                EntitySnapshot s = snapshots[i];

                if (tick == targetTick)
                    return s.toHitbox();

                if (tick < targetTick && tick > bestPrevTick) {
                    bestPrevTick = tick;
                    bestPrev = s;
                } else if (tick > targetTick && tick < bestNextTick) {
                    bestNextTick = tick;
                    bestNext = s;
                }
            }

            if (bestPrev != null && bestNext != null) {
                int tickDelta = bestNextTick - bestPrevTick;
                if (tickDelta <= 0)
                    return bestPrev.toHitbox();
                double progress = (double) (targetTick - bestPrevTick) / (double) tickDelta;

                // Grim models entities as two interpolation windows and takes
                // the UNION - the client renders entities lerping across
                // packet positions (3 ticks living, 10 boats). A single
                // interpolated point under-covers that range: expand the box
                // by half the bracketing snapshot distance so the lerp
                // midpoints the client can legitimately show are all inside.
                SimpleHitbox box = EntitySnapshot.interpolate(bestPrev, bestNext, progress);
                double dx = Math.abs(bestNext.x - bestPrev.x);
                double dz = Math.abs(bestNext.z - bestPrev.z);
                double dy = Math.abs(bestNext.y - bestPrev.y);
                double lerpMargin = Math.max(Math.max(dx, dz), dy) * 0.5D;
                if (lerpMargin > 0.0D && lerpMargin < 1.0D) {
                    box.expand(Math.min(lerpMargin, 0.30D));
                }
                return box;
            }

            if (count > 0) {
                int latestIdx = (head - 1 + HISTORY_SIZE) % HISTORY_SIZE;
                SimpleHitbox latest = snapshots[latestIdx].toHitbox();
                // Fresh-latest: the client may still be lerping TOWARD this
                // position from the previous one - same expansion applies.
                if (count > 1) {
                    int prevIdx = (head - 2 + HISTORY_SIZE) % HISTORY_SIZE;
                    EntitySnapshot prev = snapshots[prevIdx];
                    EntitySnapshot now = snapshots[latestIdx];
                    double dx = Math.abs(now.x - prev.x);
                    double dz = Math.abs(now.z - prev.z);
                    double dy = Math.abs(now.y - prev.y);
                    double lerpMargin = Math.max(Math.max(dx, dz), dy) * 0.5D;
                    if (lerpMargin > 0.0D && lerpMargin < 1.0D) {
                        latest.expand(Math.min(lerpMargin, 0.30D));
                    }
                }
                return latest;
            }
            return null;
        }
    }

    private void syncFromLiveEntityIfNeeded(int entityId, CompensatedEntity data, int currentTick) {
        Entity entity = findEntity(entityId);
        if (entity == null || !entity.isValid()) return;

        EntitySnapshot latest = data.getLatest();
        if (latest == null) {
            handleEntitySnapshot(entity);
            return;
        }

        EntityBox live = readEntityBox(entity);
        double dx = latest.x - live.x;
        double dy = latest.y - live.y;
        double dz = latest.z - live.z;
        double distanceSquared = dx * dx + dy * dy + dz * dz;

        if (distanceSquared > LIVE_RESYNC_DISTANCE_SQUARED || currentTick - data.lastSeenTick > MAX_STALE_TICKS) {
            data.snapshot(currentTick, live.x, live.y, live.z, live.width, live.height);
        }
    }

    private Entity findEntity(int entityId) {
        java.lang.ref.WeakReference<Entity> ref = entityCache.get(entityId);
        if (ref != null) {
            Entity e = ref.get();
            if (e != null && e.isValid()) return e;
            entityCache.remove(entityId, ref);
        }

        CompensatedEntity data = compensationMap.get(entityId);
        if (data != null && data.uuid != null) {
            try {
                Entity e = Bukkit.getEntity(data.uuid);
                if (e != null && e.isValid()) {
                    entityCache.put(entityId, new java.lang.ref.WeakReference<>(e));
                    return e;
                }
            } catch (Throwable ignored) {}
        }

        if (!ret.tawny.truthful.utils.Threading.isFolia() && Bukkit.isPrimaryThread()) {
            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    if (entity.getEntityId() == entityId) {
                        entityCache.put(entityId, new java.lang.ref.WeakReference<>(entity));
                        return entity;
                    }
                }
            }
        }
        return null;
    }

    private EntityBox readEntityBox(Entity entity) {
        try {
            BoundingBox box = entity.getBoundingBox();
            double width = Math.max(box.getMaxX() - box.getMinX(), box.getMaxZ() - box.getMinZ());
            double height = box.getMaxY() - box.getMinY();
            if (width <= 0.0D || height <= 0.0D) {
                // Degenerate live box (marker armor stands): use per-type
                // geometry instead of tracking a zero-size hitbox.
                double[] fallback = ENTITY_TYPE_SIZES.get(entityTypeKey(entity));
                double fw = fallback != null ? fallback[0] : DEFAULT_WIDTH;
                double fh = fallback != null ? fallback[1] : DEFAULT_HEIGHT;
                return new EntityBox(
                        (box.getMinX() + box.getMaxX()) * 0.5,
                        box.getMinY(),
                        (box.getMinZ() + box.getMaxZ()) * 0.5,
                        fw,
                        fh
                );
            }
            return new EntityBox(
                    (box.getMinX() + box.getMaxX()) * 0.5,
                    box.getMinY(),
                    (box.getMinZ() + box.getMaxZ()) * 0.5,
                    width,
                    height
            );
        } catch (Throwable ignored) {
            double[] fallback = ENTITY_TYPE_SIZES.get(entityTypeKey(entity));
            double fw = fallback != null ? fallback[0] : DEFAULT_WIDTH;
            double fh = fallback != null ? fallback[1] : DEFAULT_HEIGHT;
            Location location = entity.getLocation();
            return new EntityBox(location.getX(), location.getY(), location.getZ(), fw, fh);
        }
    }

    /**
     * Main-thread pass: for entities spawned with generic hints, read the true
     * live bounding box (covers baby/variant sizes the spawn packet cannot
     * convey) and re-snapshot at the last known packet position with honest
     * geometry. Additive by design: the checks take the best-case box across
     * the window, so corrected snapshots can only widen or refine it.
     */
    private void resolvePendingSizes() {
        if (pendingSizeResolve.isEmpty() || !Bukkit.isPrimaryThread()) return;
        int currentTick = this.tickCounter;
        for (Integer id : pendingSizeResolve) {
            CompensatedEntity data = compensationMap.get(id);
            if (data == null || data.sizeResolved || data.isExpired(currentTick)) {
                pendingSizeResolve.remove(id);
                continue;
            }
            if (++data.sizeResolveAttempts > SIZE_RESOLVE_MAX_ATTEMPTS) {
                pendingSizeResolve.remove(id);
                continue;
            }
            try {
                Entity entity = data.uuid == null ? null : Bukkit.getEntity(data.uuid);
                if (entity == null || !entity.isValid() || entity.getEntityId() != data.entityId) continue;
                EntityBox live = readEntityBox(entity);
                synchronized (data) {
                    EntitySnapshot latest = data.getLatest();
                    if (latest != null) {
                        data.snapshot(currentTick, latest.x, latest.y, latest.z, live.width, live.height);
                    } else {
                        data.snapshot(currentTick, live.x, live.y, live.z, live.width, live.height);
                    }
                }
                data.sizeResolved = true;
                pendingSizeResolve.remove(id);
            } catch (Throwable ignored) {
                // Cross-region lookups can fail transiently; retry until the cap.
            }
        }
    }

    private static String entityTypeKey(Entity entity) {
        try {
            EntityType type = entity.getType();
            NamespacedKey key = type.getKey();
            if (key != null) return key.getKey();
        } catch (Throwable ignored) {
        }
        try {
            return entity.getType().name().toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Map<String, double[]> buildEntityTypeSizes() {
        Map<String, double[]> m = new HashMap<>();

        double[] humanoid = {0.6D, 1.95D};
        for (String k : new String[]{
                "zombie", "zombie_villager", "husk", "drowned", "zombified_piglin", "zombie_pigman",
                "piglin", "piglin_brute", "villager", "wandering_trader", "witch", "pillager",
                "vindicator", "evoker", "illusioner"}) {
            m.put(k, humanoid);
        }
        double[] skeleton = {0.6D, 1.99D};
        m.put("skeleton", skeleton);
        m.put("stray", skeleton);
        m.put("bogged", skeleton);
        m.put("creeper", new double[]{0.6D, 1.7D});
        m.put("blaze", new double[]{DEFAULT_WIDTH, DEFAULT_HEIGHT});
        m.put("enderman", new double[]{0.6D, 2.9D});
        m.put("wither_skeleton", new double[]{0.7D, 2.4D});
        m.put("spider", new double[]{1.4D, 0.9D});
        m.put("cave_spider", new double[]{0.7D, 0.5D});
        m.put("iron_golem", new double[]{1.4D, 2.7D});
        m.put("snow_golem", new double[]{0.7D, 1.9D});
        m.put("snowman", m.get("snow_golem"));
        m.put("ghast", new double[]{4.0D, 4.0D});
        // happy_ghast true geometry varies and is not yet verified: generous
        // 2.0x2.0, never tighter than the old default in either dimension.
        m.put("happy_ghast", new double[]{2.0D, 2.0D});
        // Slime/magma/sulfur cubes come in unknowable size variants (0.51 up to
        // 2.04+): intentionally no entries here so the never-tighter default
        // holds until the main-thread live resync reads the true variant box.
        m.put("armor_stand", new double[]{0.5D, 1.975D});
        m.put("warden", new double[]{0.9D, 2.5D});
        m.put("shulker", new double[]{1.0D, 1.0D});
        m.put("ravager", new double[]{1.95D, 2.2D});
        m.put("vex", new double[]{0.4D, 0.8D});
        m.put("allay", new double[]{0.35D, 0.6D});
        m.put("axolotl", new double[]{0.75D, 0.42D});
        m.put("frog", new double[]{0.5D, 0.5D});
        m.put("tadpole", new double[]{0.4D, 0.3D});
        m.put("silverfish", new double[]{0.4D, 0.3D});
        m.put("endermite", new double[]{0.4D, 0.3D});
        m.put("bat", new double[]{0.5D, 0.9D});
        m.put("breeze", new double[]{0.6D, 1.77D});
        m.put("phantom", new double[]{0.9D, 0.5D});

        double[] minecart = {0.98D, 0.7D};
        for (String k : new String[]{"minecart", "chest_minecart", "furnace_minecart",
                "hopper_minecart", "tnt_minecart", "spawner_minecart", "command_block_minecart"}) {
            m.put(k, minecart);
        }
        double[] boat = {1.375D, 0.5625D};
        m.put("boat", boat);
        m.put("chest_boat", boat);
        for (String wood : new String[]{"oak", "spruce", "birch", "jungle", "acacia",
                "dark_oak", "mangrove", "cherry", "pale_oak"}) {
            m.put(wood + "_boat", boat);
            m.put(wood + "_chest_boat", boat);
        }
        m.put("bamboo_raft", boat);
        m.put("bamboo_chest_raft", boat);

        double[] horse = {1.39685D, 1.6D};
        for (String k : new String[]{"horse", "donkey", "mule", "skeleton_horse", "zombie_horse"}) {
            m.put(k, horse);
        }
        double[] llama = {0.9D, 1.87D};
        m.put("llama", llama);
        m.put("trader_llama", llama);
        m.put("camel", new double[]{1.7D, 2.375D});
        m.put("strider", new double[]{0.9D, 1.7D});
        m.put("hoglin", new double[]{1.39685D, 1.4D});
        m.put("zoglin", m.get("hoglin"));
        m.put("pig", new double[]{0.9D, 0.9D});
        double[] cowLike = {0.9D, 1.4D};
        m.put("cow", cowLike);
        m.put("mooshroom", cowLike);
        m.put("mushroom_cow", cowLike);
        m.put("sheep", new double[]{0.9D, 1.3D});
        m.put("goat", new double[]{0.9D, 1.3D});
        double[] smallCat = {0.6D, 0.7D};
        m.put("fox", smallCat);
        m.put("cat", smallCat);
        m.put("ocelot", smallCat);
        m.put("wolf", new double[]{0.6D, 0.85D});
        m.put("rabbit", new double[]{0.4D, 0.5D});
        m.put("chicken", new double[]{0.4D, 0.7D});
        double[] squid = {0.8D, 0.8D};
        m.put("squid", squid);
        m.put("glow_squid", squid);
        m.put("polar_bear", new double[]{1.4D, 1.4D});
        m.put("bee", new double[]{0.7D, 0.6D});
        m.put("dolphin", new double[]{0.9D, 0.6D});
        m.put("turtle", new double[]{1.2D, 0.4D});
        m.put("panda", new double[]{1.3D, 1.25D});
        m.put("parrot", new double[]{0.5D, 0.9D});
        m.put("guardian", new double[]{0.85D, 0.85D});
        m.put("elder_guardian", new double[]{1.9975D, 1.9975D});
        m.put("pufferfish", new double[]{0.7D, 0.7D});
        m.put("cod", new double[]{0.5D, 0.3D});
        m.put("salmon", new double[]{0.7D, 0.4D});
        m.put("tropical_fish", new double[]{0.5D, 0.4D});
        m.put("player", new double[]{DEFAULT_WIDTH, DEFAULT_HEIGHT});

        return Collections.unmodifiableMap(m);
    }

    /**
     * Records the PE entity type key per spawned entity id. LOWEST priority so
     * it observes the packet before the main packet listener feeds the tracker.
     */
    private static final class SpawnTypeListener extends PacketListenerAbstract {
        SpawnTypeListener() {
            super(PacketListenerPriority.LOWEST);
        }

        @Override
        public void onPacketSend(PacketSendEvent event) {
            if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY) {
                try {
                    WrapperPlayServerSpawnEntity wrapper = new WrapperPlayServerSpawnEntity(event);
                    com.github.retrooper.packetevents.protocol.entity.type.EntityType type = wrapper.getEntityType();
                    if (type != null && type.getName() != null) {
                        SPAWNED_TYPE_KEYS.put(wrapper.getEntityId(), type.getName().getKey());
                    }
                } catch (Throwable ignored) {
                }
            } else if (event.getPacketType() == PacketType.Play.Server.DESTROY_ENTITIES) {
                try {
                    WrapperPlayServerDestroyEntities wrapper = new WrapperPlayServerDestroyEntities(event);
                    for (int id : wrapper.getEntityIds()) {
                        SPAWNED_TYPE_KEYS.remove(id);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final class EntityBox {
        final double x, y, z, width, height;

        EntityBox(double x, double y, double z, double width, double height) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.width = width;
            this.height = height;
        }
    }

    public static class EntitySnapshot {
        public final double x, y, z, width, height;

        EntitySnapshot(double x, double y, double z, double width, double height) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.width = width;
            this.height = height;
        }

        public SimpleHitbox toHitbox() {
            return new SimpleHitbox(x, y, z, width, height);
        }

        static SimpleHitbox interpolate(EntitySnapshot from, EntitySnapshot to, double t) {
            return new SimpleHitbox(
                    from.x + (to.x - from.x) * t,
                    from.y + (to.y - from.y) * t,
                    from.z + (to.z - from.z) * t,
                    from.width + (to.width - from.width) * t,
                    from.height + (to.height - from.height) * t);
        }
    }
}