package ret.tawny.truthful.data;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.attributes.AttributeEngine;
import ret.tawny.truthful.data.handler.SetbackHandler;
import ret.tawny.truthful.data.tracker.ActionTracker;
import ret.tawny.truthful.data.tracker.PositionTracker;
import ret.tawny.truthful.data.world.CompensatedWorld;
import ret.tawny.truthful.sync.TeleportQueue;
import ret.tawny.truthful.sync.VelocityQueue;
import ret.tawny.truthful.utils.Threading;
import ret.tawny.truthful.utils.elytra.FireworkBoostTracker;
import ret.tawny.truthful.wrapper.impl.client.action.PlayerBlockPlacePacketWrapper;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;
import ret.tawny.truthful.wrapper.impl.server.position.SetPositionPacketWrapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class PlayerData {

    public static final double EYE_HEIGHT_STANDING = 1.62;

    private final Player player;
    private final UUID playerId;

    private final VelocityQueue velocityQueue;
    private final TeleportQueue teleportQueue;
    private final FireworkBoostTracker fireworkTracker;
    private final CompensatedWorld worldCache;
    private final MovementState movementState;
    private final MovementContext movementContext;

    private final SetbackHandler setbackHandler;

    private final PositionTracker positionTracker;
    private final ActionTracker actionTracker;
    private final AttributeEngine attributeEngine;
    private final ret.tawny.truthful.predictionengine.PredictionState predictionState =
            new ret.tawny.truthful.predictionengine.PredictionState();

    private volatile int ticksTracked;
    private volatile int lastGlideTick = -100;
    private volatile int lastBlockPlaceTick;
    private volatile int lastGhostBlockTick;
    private volatile int lastHitTick;
    private volatile int lastAttackPacketTick = -100;
    private volatile int lastDamageTick;
    private volatile int lastRiptideTick = -100;
    private volatile int lastFireworkTick = -100;
    private volatile int lastVehicleExitTick = -100;
    private volatile int lastVehicleTick = -100;
    private volatile int lastVelocityTick = -100;
    private volatile int lastFlagTick = -100;
    private volatile boolean lastSprinting;

    private long lastBlockPlaceTime;
    private long lastSlotSwitchTime;

    private final Map<Short, TransactionEntry> transactionsSent = new ConcurrentHashMap<>();
    private final Queue<Short> transactionOrder = new ConcurrentLinkedQueue<>();
    private volatile long playerClockAtLeast = System.nanoTime();
    private volatile long timerBalanceRealTime = System.nanoTime();
    private final long clockDrift = 150_000_000L;

    private final Map<ExemptionType, Integer> exemptions = new ConcurrentHashMap<>();
    private boolean alertsEnabled = true;
    private boolean permanentlyExempt = false;
    private String clientBrand = "Unknown";
    private volatile int totalViolations;

    private volatile long lastTransactionTime;
    private volatile long transactionPing;

    private volatile long highPingStartTime = 0;
    private volatile long lastExplosionPacketTime = 0;

    private Entity lastTarget;
    private int lastTargetId = -1;
    private double preExitVehicleSpeed;
    private PlayerBlockPlacePacketWrapper currentBlockPlacement;
    private double baritoneTrust = 100.0;

    // Piston pushes: direction-bounded impulse window. Vanilla pistons move
    // clients without any velocity packet; the engine adds a bounded allowance
    // along the recorded direction for a few ticks after a push.
    private volatile int lastPistonTick = -100;
    private volatile double pistonDirX, pistonDirY, pistonDirZ;

    private boolean banning = false;
    private long banStartTime = 0;

    // Server-trusted world + gamemode snapshots (refreshed on the main/region
    // thread). Packet threads must never call player.getWorld()/getGameMode().
    private volatile org.bukkit.World cachedWorld;
    private volatile org.bukkit.GameMode gameModeCache = org.bukkit.GameMode.SURVIVAL;

    // Spear lunge arming uses a token bucket so spam cannot keep the movement
    // exemption window permanently open (the old refresh-on-every-click farm).
    // Volatile: armed from main-thread interact/attack events AND from the
    // netty release-use-item path.
    private volatile int spearTokens = SPEAR_TOKEN_CAPACITY;
    private volatile int spearTokenWindowStart = Integer.MIN_VALUE;
    private volatile double lungeDirX, lungeDirY, lungeDirZ;
    private volatile boolean lungeFresh;
    /** Exact impulse strength for known impulses (riptide); 0 = bounded search. */
    private volatile double impulseStrength;

    // Ridden-vehicle link + rotation, tracked from server packets
    // (SET_PASSENGERS + the vehicle entity's rotation packets). Boat physics
    // steers along the BOAT's yaw, which is not the rider's look.
    private volatile int vehicleEntityId = -1;
    private volatile float vehicleYaw;
    private volatile float vehiclePitch;
    private static final int SPEAR_TOKEN_CAPACITY = 60;
    private static final int SPEAR_TOKEN_WINDOW_TICKS = 120;

    private volatile Map<PotionEffectType, Integer> potionCache = new HashMap<>();
    private final Map<String, Integer> enchantmentCache = new ConcurrentHashMap<>();
    private volatile double walkSpeedCache = 0.1;
    private volatile double gravityCache = 0.08;
    private volatile double jumpStrengthCache = 0.42;
    private volatile double stepHeightCache = 0.6;
    private volatile double movementEfficiencyCache = 0.0;
    private volatile double waterMovementEfficiencyCache = 0.0;
    private volatile double sneakingSpeedCache = 0.3;
    private volatile double scaleCache = 1.0;
    private volatile boolean swimmingCache = false;
    private volatile double knockbackResistanceCache = 0.0;
    // 26.2 "Chaos Cubed" movement modifier attributes (1.0 / 0.0 defaults on older servers).
    private volatile double airDragModifierCache = 1.0;
    private volatile double frictionModifierCache = 1.0;
    private volatile double bouncinessCache = 0.0;
    private volatile int foodLevelCache = 20;
    private volatile boolean allowFlightCache = false;
    private volatile boolean flyingCache = false;
    private volatile boolean glidingCache = false;
    private volatile boolean insideVehicleCache = false;
    private volatile String vehicleTypeNameCache = "";
    private volatile int worldMinHeightCache = -64;
    private volatile int worldMaxHeightCache = 320;
    private volatile boolean holdingBlockCache = false;
    private volatile boolean wearingElytraCache = false;
    private volatile boolean holdingCrystalCache = false;
    private volatile boolean spearCache = false;
    private volatile boolean slowItemCache = false;
    private volatile boolean glowstoneCache = false;
    private volatile boolean tridentCache = false;
    private volatile boolean leatherBootsCache = false;
    private volatile int lastCacheUpdate = -100;

    public enum TeleportTag { NONE, NORMAL, LAGBACK }
    private TeleportTag outgoingTeleportTag = TeleportTag.NONE;
    private TeleportTag incomingTeleportTag = TeleportTag.NONE;
    // Teleport-confirmation anchoring: a confirm no longer makes the NEXT
    // position claim a teleport unconditionally - the claim must also land
    // on (or very near) the confirmed teleport's target. A withheld confirm
    // used to be a free pass to call ANY position a teleport.
    private volatile boolean hasConfirmTarget = false;
    private volatile double confirmTargetX, confirmTargetY, confirmTargetZ;
    // Tick of the last server-sent teleport. Any large position claim within
    // this window is explained by that teleport, not an impossible packet
    // (plugin /spawn, /tp, portals... teleport arbitrary distances).
    private volatile int lastServerTeleportTick = -1000;
    private volatile int lastTeleportConfirmTick = -1000;
    private volatile int invalidTeleportConfirms = 0;

    public PlayerData(final Player player, int queueMaxEntries, long queueTtlMillis) {
        this.player = player;
        this.playerId = player.getUniqueId();
        this.cachedWorld = player.getWorld();
        this.velocityQueue = new VelocityQueue(queueMaxEntries, queueTtlMillis);
        this.teleportQueue = new TeleportQueue(queueMaxEntries, queueTtlMillis);
        this.fireworkTracker = new FireworkBoostTracker();
        this.worldCache = new CompensatedWorld(player.getWorld().getUID());
        this.movementState = new MovementState();
        this.movementContext = new MovementContext();

        this.setbackHandler = new SetbackHandler(this);
        this.positionTracker = new PositionTracker(this, player);
        this.actionTracker = new ActionTracker();
        this.attributeEngine = new AttributeEngine(this);

        this.setExemption(ExemptionType.JOIN, 40);

        updateStateCache();
    }

    public void update(final RelMovePacketWrapper event) {
        this.ticksTracked++;

        if (ticksTracked - lastCacheUpdate > 20) {
            updateStateCache();
        }

        if (getTicksSinceTeleport() > 10 && !isTeleportPending()) {
            double tps = Truthful.getInstance().getTps();
            if (tps <= 0) tps = 20.0;
            long tickIntervalNs = (long) ((1000.0 / tps) * 1_000_000.0);
            this.timerBalanceRealTime += tickIntervalNs;
        } else {
            this.timerBalanceRealTime = System.nanoTime();
        }

        Player p = getPlayer();
        if (p == null) return;

        // Note: allowFlight/flying/gliding/insideVehicle caches are refreshed by
        // updateStateCache() on the main thread. Re-reading live Bukkit state here
        // on the packet thread was racy (and illegal on Folia), so the cache is
        // the single source of truth; BadPacketA-style checks accept cache lag.

        this.positionTracker.handleUpdate(event);
        this.movementState.update(this);
        this.movementContext.update(this);

        if (event.isPositionUpdate()) {
            boolean isTeleport = this.incomingTeleportTag != TeleportTag.NONE;

            if (isTeleport) {
                this.incomingTeleportTag = TeleportTag.NONE;
                // The confirm tag alone is not proof: the claim must land on
                // the confirmed teleport's target. A cheat that withheld a
                // confirm used to launder an arbitrary position claim as a
                // teleport (free unjudged teleports + exemption churn).
                if (!matchesConfirmedTeleportTarget(event.getX(), event.getY(), event.getZ())) {
                    isTeleport = false;
                }
                consumeConfirmTarget();
            }
            if (!isTeleport && teleportQueue.getPendingCount() > 0) {
                TeleportQueue.Teleport matched = teleportQueue.match(event.getX(), event.getY(), event.getZ());
                if (matched != null) {
                    isTeleport = true;
                    this.incomingTeleportTag = matched.isLagback ? TeleportTag.LAGBACK : TeleportTag.NORMAL;
                    // Arm the same positional anchor for the follow-up packet
                    // the tag grants, so a second claim cannot be laundered.
                    this.confirmTargetX = matched.loc.getX();
                    this.confirmTargetY = matched.loc.getY();
                    this.confirmTargetZ = matched.loc.getZ();
                    this.hasConfirmTarget = true;
                }
            }

            if (isTeleport) {
                // This permanently patches the 40-tick (2s) Disabler exploit loop!
                // Flood guard: spamming far positions forces endless server
                // corrections, and every correction used to hand out a free
                // exemption (the full disabler). Past flood pace there is no
                // grace and no state reset - the movement just gets judged.
                boolean flooding = false;
                try {
                    flooding = teleportQueue.isFlooding();
                } catch (Throwable ignored) {}
                if (!flooding) {
                    this.setExemption(ExemptionType.TELEPORT, 2);
                    this.positionTracker.reset(event.getX(), event.getY(), event.getZ(), event.getYaw(), event.getPitch());
                    this.predictionState.reset();
                    this.timerBalanceRealTime = System.nanoTime();
                }
                this.incomingTeleportTag = TeleportTag.NONE;
            }

            setbackHandler.updateSafeLocation(
                    positionTracker.getX(), positionTracker.getY(), positionTracker.getZ(),
                    positionTracker.getYaw(), positionTracker.getPitch());
            // NOTE: the legacy single-path/multi-input prediction pass that used
            // to run here had no consumers since the Simulation engine runs its
            // own emulation - it is gone. Movement bookkeeping (teleports,
            // resets, transaction state) lives in the dedicated components.
        }

        if (glidingCache) {
            this.lastGlideTick = this.ticksTracked;
            double speed = getDeltaXZ();
            double accel = speed - getLastDeltaXZ();
            if (fireworkTracker.update(ticksTracked, speed, accel)) {
                this.setExemption(ExemptionType.ELYTRA_BOOST, 1);
            }
        } else {
            fireworkTracker.reset();
        }

        if (actionTracker.isUsingRiptide()) {
            actionTracker.incrementPendingRiptideTicks();
            double speed = getDeltaXZ() + Math.abs(getDeltaY());
            if (speed > 1.0 || actionTracker.getPendingRiptideTicks() > 5) {
                actionTracker.setUsingRiptide(false);
            }
        }

        actionTracker.handleTick();
        // Exemptions are decremented once per SERVER TICK from the global
        // timer (see Truthful), not once per movement packet. Packet-counted
        // windows let a fake-lag client stretch any exemption in wall time.
        this.teleportQueue.cleanup();
    }

    public void updateStateCache() {
        if (!Bukkit.isPrimaryThread()) {
            Threading.runOnMain(this::updateStateCache);
            return;
        }

        Player p = getPlayer();
        if (p == null || !p.isOnline()) return;

        Map<PotionEffectType, Integer> newPotionCache = new HashMap<>();
        for (PotionEffect effect : p.getActivePotionEffects()) {
            newPotionCache.put(effect.getType(), effect.getAmplifier() + 1);
        }
        this.potionCache = newPotionCache;

        enchantmentCache.clear();
        updateEnchantment(p.getInventory().getBoots(), "soul_speed", org.bukkit.enchantments.Enchantment.SOUL_SPEED);
        updateEnchantment(p.getInventory().getBoots(), "depth_strider", org.bukkit.enchantments.Enchantment.DEPTH_STRIDER);
        updateEnchantment(p.getInventory().getLeggings(), "swift_sneak", org.bukkit.enchantments.Enchantment.SWIFT_SNEAK);
        updateEnchantment(p.getInventory().getItemInMainHand(), "efficiency", org.bukkit.enchantments.Enchantment.EFFICIENCY);

        walkSpeedCache = getAttributeValue(p, "GENERIC_MOVEMENT_SPEED", walkSpeedCache);

        gravityCache = getAttributeValue(p, "GENERIC_GRAVITY", 0.08D);
        jumpStrengthCache = getAttributeValue(p, "GENERIC_JUMP_STRENGTH", 0.42D);
        stepHeightCache = getAttributeValue(p, "GENERIC_STEP_HEIGHT", 0.6D);
        movementEfficiencyCache = getAttributeValue(p, "GENERIC_MOVEMENT_EFFICIENCY", 0.0D);
        waterMovementEfficiencyCache = getAttributeValue(p, "GENERIC_WATER_MOVEMENT_EFFICIENCY", 0.0D);
        sneakingSpeedCache = getAttributeValue(p, "PLAYER_SNEAKING_SPEED", 0.3D);
        // Scale attribute (1.20.5+). Clamped to vanilla bounds so a bad read can never
        // blow up hitbox / ceiling math. On 1.20-1.20.4 this falls back to 1.0.
        scaleCache = Math.max(0.0625D, Math.min(16.0D, getAttributeValue(p, "GENERIC_SCALE", 1.0D)));
        // Netherite is +0.1 per piece (0.4 full set). Read for staff debug
        // context only: resistance is already baked into the velocity packet
        // server-side, so verdicts must NOT scale by it again.
        knockbackResistanceCache = Math.max(0.0D, Math.min(1.0D, getAttributeValue(p, "GENERIC_KNOCKBACK_RESISTANCE", 0.0D)));

        // 26.2+ movement modifiers. On older servers these attributes do not
        // exist and the reads fall back to the vanilla defaults. The engine
        // consumes non-default values as bounded, physically-scaled allowance
        // (we do not pretend to know Mojang's exact application formula).
        if (ret.tawny.truthful.Truthful.getInstance().getVersionManager().getAdapter().supportsMovementModifierAttributes()) {
            airDragModifierCache = Math.max(0.0D, Math.min(2048.0D, getAttributeValue(p, "GENERIC_AIR_DRAG_MODIFIER", 1.0D)));
            frictionModifierCache = Math.max(0.0D, Math.min(2048.0D, getAttributeValue(p, "GENERIC_FRICTION_MODIFIER", 1.0D)));
            bouncinessCache = Math.max(0.0D, Math.min(1.0D, getAttributeValue(p, "GENERIC_BOUNCINESS", 0.0D)));
        } else {
            airDragModifierCache = 1.0D;
            frictionModifierCache = 1.0D;
            bouncinessCache = 0.0D;
        }
        try { swimmingCache = p.isSwimming(); } catch (Throwable ignored) { swimmingCache = false; }

        if (attributeEngine != null) {
            attributeEngine.record(
                    walkSpeedCache, gravityCache, jumpStrengthCache, stepHeightCache,
                    movementEfficiencyCache, waterMovementEfficiencyCache, sneakingSpeedCache,
                    scaleCache,
                    newPotionCache.getOrDefault(PotionEffectType.SPEED, 0),
                    newPotionCache.getOrDefault(PotionEffectType.SLOWNESS, 0),
                    newPotionCache.getOrDefault(PotionEffectType.JUMP_BOOST, 0)
            );
        }

        foodLevelCache = p.getFoodLevel();
        allowFlightCache = p.getAllowFlight();
        flyingCache = p.isFlying();
        glidingCache = p.isGliding();
        insideVehicleCache = p.isInsideVehicle();
        gameModeCache = p.getGameMode();
        cachedWorld = p.getWorld();
        try {
            org.bukkit.entity.Entity vehicle = p.getVehicle();
            vehicleTypeNameCache = vehicle != null ? vehicle.getType().name().toUpperCase() : "";
        } catch (Throwable ignored) {
            vehicleTypeNameCache = "";
        }
        worldMinHeightCache = p.getWorld().getMinHeight();
        worldMaxHeightCache = p.getWorld().getMaxHeight();

        org.bukkit.inventory.ItemStack hand = p.getInventory().getItemInMainHand();
        holdingBlockCache = (hand != null && hand.getType().isBlock() && hand.getType() != org.bukkit.Material.AIR);

        org.bukkit.inventory.ItemStack chest = p.getInventory().getChestplate();
        wearingElytraCache = (chest != null && chest.getType() == org.bukkit.Material.ELYTRA);

        org.bukkit.inventory.ItemStack boots = p.getInventory().getBoots();
        leatherBootsCache = (boots != null && boots.getType() == org.bukkit.Material.LEATHER_BOOTS);

        holdingCrystalCache = (hand != null && hand.getType().name().contains("END_CRYSTAL"));

        org.bukkit.inventory.ItemStack off = p.getInventory().getItemInOffHand();

        // Spear-family held item (main or off hand), OR any item enchanted
        // with a "lunge" enchantment (the dash impulse is enchant-driven on
        // some versions; a material-name check alone missed it and the engine
        // never offered the impulse, causing Simulation + Velocity(A) flags).
        spearCache = isLungeItem(hand) || isLungeItem(off);

        slowItemCache = ret.tawny.truthful.utils.world.PhysicsUtils.shouldSlowDown(hand, p) ||
                ret.tawny.truthful.utils.world.PhysicsUtils.shouldSlowDown(off, p);

        tridentCache = (hand != null && hand.getType() == org.bukkit.Material.TRIDENT) ||
                (off != null && off.getType() == org.bukkit.Material.TRIDENT);

        glowstoneCache = (hand != null && hand.getType() == org.bukkit.Material.GLOWSTONE) ||
                (off != null && off.getType() == org.bukkit.Material.GLOWSTONE);

        this.lastSprinting = isSprinting();
        lastCacheUpdate = ticksTracked;
    }

    public void handleTeleportConfirm(int teleportId) {
        TeleportQueue.Teleport tp = teleportQueue.confirm(teleportId);
        if (tp != null) {
            this.lastTeleportConfirmTick = this.ticksTracked;
            this.incomingTeleportTag = tp.isLagback ? TeleportTag.LAGBACK : TeleportTag.NORMAL;
            this.confirmTargetX = tp.loc.getX();
            this.confirmTargetY = tp.loc.getY();
            this.confirmTargetZ = tp.loc.getZ();
            this.hasConfirmTarget = true;
            this.invalidTeleportConfirms = 0;
        } else {
            this.invalidTeleportConfirms++;
        }
    }

    public void acceptTeleport(SetPositionPacketWrapper wrapper) {
        TeleportTag tag = this.outgoingTeleportTag;
        boolean lagback = tag == TeleportTag.LAGBACK;
        this.lastServerTeleportTick = this.ticksTracked;
        teleportQueue.add(wrapper.getTeleportId(), new Location(player.getWorld(), wrapper.getX(), wrapper.getY(), wrapper.getZ()), lagback);

        // NOTE: no exemption here. The old unconditional grant armed a
        // disabler: every far position packet forces the server to send a
        // correction teleport, and each correction bought 2 free exempt
        // ticks (plus a lastTeleportTick refresh that kept the timer
        // balance frozen). Exemptions are granted when the player's packet
        // MATCHES the teleport - see update() - which real teleports do
        // and correction churn does only at a rate the flood guard handles.
        this.outgoingTeleportTag = TeleportTag.NONE;
    }

    /**
     * Position-sanity gate for incoming position packets: a claim that is
     * non-finite, outside the coordinate space, or a teleport-distance jump
     * that no pending teleport explains is IMPOSSIBLE traffic (the
     * "far position packet" disabler). The caller must drop it before the
     * server or any tracker sees it.
     */
    public boolean isImpossiblePositionClaim(double x, double y, double z) {
        // A server-sent teleport explains any position for a window: plugins
        // (/spawn, /tp), portals, pearls, respawns teleport arbitrary
        // distances, and the client's claim for the landing can arrive in an
        // order that misses the pending queue match.
        int sinceServerTeleport = ticksTracked - lastServerTeleportTick;
        if (sinceServerTeleport >= 0 && sinceServerTeleport <= 20) {
            return false;
        }
        UUID currentWorldUid = cachedWorld != null ? cachedWorld.getUID() : (player != null && player.isOnline() ? player.getWorld().getUID() : null);
        boolean nearPending = teleportQueue.isNearPending(x, y, z, currentWorldUid,
                ret.tawny.truthful.utils.math.PositionSanity.TELEPORT_MATCH_RADIUS_SQ);
        return ret.tawny.truthful.utils.math.PositionSanity.isImpossibleClaim(
                x, y, z,
                positionTracker.getX(), positionTracker.getY(), positionTracker.getZ(),
                nearPending);
    }

    /** True when the claimed position lands on the confirmed teleport target. */
    public boolean matchesConfirmedTeleportTarget(double x, double y, double z) {
        if (!hasConfirmTarget) return true; // no anchor: fall back to the old tag behavior
        return ret.tawny.truthful.utils.math.PositionSanity.isNearTeleportTarget(
                x, y, z, confirmTargetX, confirmTargetY, confirmTargetZ);
    }

    private void consumeConfirmTarget() {
        this.hasConfirmTarget = false;
    }

    public void forceLagback() {
        if (this.velocityQueue.hasExplosionVelocity() || isInExplosionGraceWindow(1500L)) {
            return;
        }
        // Only arm the LAGBACK tag when the setback actually goes out; otherwise
        // the tag would attach to the next unrelated server teleport (tag stealing).
        if (this.setbackHandler.setback()) {
            this.outgoingTeleportTag = TeleportTag.LAGBACK;
        }
        if (!this.velocityQueue.hasExplosionVelocity()) {
            this.velocityQueue.clear();
        }
    }

    public void setLastFlagTick(int tick) { this.lastFlagTick = tick; }
    public int getLastFlagTick() { return lastFlagTick; }

    public boolean isSlowItem() { return slowItemCache; }
    public boolean isHoldingGlowstone() { return glowstoneCache; }
    public boolean isTrident() { return tridentCache; }
    public boolean isHoldingCrystal() { return holdingCrystalCache; }
    /** True while a spear-family item is held (main/off hand, main-thread cache). */
    public boolean isHoldingSpear() { return spearCache; }
    public boolean isHoldingBlock() { return holdingBlockCache; }
    public boolean isWearingElytra() { return wearingElytraCache; }

    public boolean isSwimming() {
        // Main-thread cache; the live player.isSwimming() read was illegal on
        // Folia packet threads and racy on Paper.
        return swimmingCache;
    }

    /** Server-trusted gamemode snapshot (main/region-thread refreshed). */
    public org.bukkit.GameMode getGameMode() { return gameModeCache; }

    /** Server-trusted world reference; safe to read from packet threads. */
    public org.bukkit.World getWorld() { return cachedWorld; }

    public void resetTotalViolations() { this.totalViolations = 0; }
    public boolean isRiptiding() { return isUsingRiptide(); }
    public int getTickFreezeGraceTicks() { return 0; }
    public float getRotationDeviation(boolean pitch) { return positionTracker.getRotationDeviation(pitch); }
    public float getLastDeltaYaw() { return positionTracker.getLastDeltaYaw(); }
    public float getLastDeltaPitch() { return positionTracker.getLastDeltaPitch(); }
    public boolean hasAttributeTransition() { return attributeEngine != null && attributeEngine.hasAttributeTransition(); }
    public int getUsingItemTicks() { return actionTracker.getUsingItemTicks(); }
    public long getTimerBalanceRealTime() { return timerBalanceRealTime; }
    public void addTimerBalance(long nanos) { this.timerBalanceRealTime += nanos; }

    private static boolean isLungeItem(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType() == org.bukkit.Material.AIR) return false;
        if (item.getType().name().contains("SPEAR")) return true;
        try {
            for (org.bukkit.enchantments.Enchantment ench : item.getEnchantments().keySet()) {
                String key = ench.getKey().getKey();
                if (key.contains("lunge")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void updateEnchantment(org.bukkit.inventory.ItemStack item, String key, org.bukkit.enchantments.Enchantment enchant) {        if (item != null && item.getType() != org.bukkit.Material.AIR) {
            int level = item.getEnchantmentLevel(enchant);
            if (level > 0) enchantmentCache.put(key, level);
        }
    }

    /**
     * Version-proof attribute read. Bukkit renamed the GENERIC_ and PLAYER_
     * prefixed enum constants in 1.21.3+ (old names gone by 1.21.11), so the
     * stripped modern name is tried first, then the legacy one, then the
     * fallback. Never throws, never returns junk.
     */
    public static double getAttributeValue(Player p, String enumName, double fallback) {
        if (p == null || enumName == null) return fallback;
        String stripped = enumName;
        if (stripped.startsWith("GENERIC_")) stripped = stripped.substring("GENERIC_".length());
        else if (stripped.startsWith("PLAYER_")) stripped = stripped.substring("PLAYER_".length());
        String[] candidates = stripped.equals(enumName)
                ? new String[]{ enumName }
                : new String[]{ stripped, enumName };
        for (String name : candidates) {
            try {
                Attribute attribute = Attribute.valueOf(name);
                AttributeInstance instance = p.getAttribute(attribute);
                if (instance != null) return instance.getValue();
            } catch (IllegalArgumentException | NullPointerException | NoSuchFieldError ignored) {
            }
        }
        return fallback;
    }

    public int getEnchantLevel(String key) { return enchantmentCache.getOrDefault(key, 0); }
    public int getFoodLevel() { return foodLevelCache; }
    public boolean isAllowFlight() { return allowFlightCache; }
    public boolean isFlying() { return flyingCache; }
    public boolean isGliding() { return glidingCache; }
    public void setGliding(boolean gliding) {
        this.glidingCache = gliding;
        if (gliding) {
            this.lastGlideTick = this.ticksTracked;
        }
    }

    public boolean canGlide() {
        if (player == null || !player.isOnline()) return false;
        try {
            org.bukkit.inventory.ItemStack chest = player.getInventory().getChestplate();
            return chest != null && chest.getType() == org.bukkit.Material.ELYTRA && chest.getDurability() < 431;
        } catch (Throwable t) {
            return false;
        }
    }

    public int getInvalidTeleportConfirms() { return invalidTeleportConfirms; }
    public void resetInvalidTeleportConfirms() { this.invalidTeleportConfirms = 0; }

    public boolean isWearingLeatherBoots() { return leatherBootsCache; }    public boolean isInsideVehicle() { return insideVehicleCache || (ticksTracked - lastVehicleTick < 10); }
    public void setInsideVehicleCache(boolean b) { this.insideVehicleCache = b; }
    public int getWorldMinHeight() { return worldMinHeightCache; }
    public int getWorldMaxHeight() { return worldMaxHeightCache; }
    public int getPotionLevel(PotionEffectType type) {
        if (type == PotionEffectType.SPEED && attributeEngine != null) return attributeEngine.getMaxSpeedLevel();
        if (type == PotionEffectType.SLOWNESS && attributeEngine != null) return attributeEngine.getMinSlownessLevel();
        if (type == PotionEffectType.JUMP_BOOST && attributeEngine != null) return attributeEngine.getMaxJumpBoostLevel();
        return potionCache.getOrDefault(type, 0);
    }
    public boolean hasPotionEffect(PotionEffectType type) { return potionCache.containsKey(type); }
    public double getWalkSpeed() { return attributeEngine != null ? attributeEngine.getMaxWalkSpeed() : walkSpeedCache; }
    public double getWalkSpeedCacheRaw() { return walkSpeedCache; }
    public double getGravity() { return attributeEngine != null ? attributeEngine.getMinGravity() : gravityCache; }
    public double getJumpStrength() { return attributeEngine != null ? attributeEngine.getMaxJumpStrength() : jumpStrengthCache; }
    public double getStepHeight() { return attributeEngine != null ? attributeEngine.getMaxStepHeight() : stepHeightCache; }
    public double getMovementEfficiency() { return attributeEngine != null ? attributeEngine.getMaxMovementEfficiency() : movementEfficiencyCache; }
    public double getWaterMovementEfficiency() { return attributeEngine != null ? attributeEngine.getMaxWaterMovementEfficiency() : waterMovementEfficiencyCache; }
    public double getSneakingSpeed() { return attributeEngine != null ? attributeEngine.getMaxSneakingSpeed() : sneakingSpeedCache; }
    public String getAttributeDebugTags() { return attributeEngine != null ? attributeEngine.getDebugTags() : "ATTR_DISABLED"; }

    public boolean isChunkLoaded() {
        int chunkX = (int) Math.floor(getX()) >> 4;
        int chunkZ = (int) Math.floor(getZ()) >> 4;
        boolean loaded = worldCache.isChunkLoaded(chunkX, chunkZ);
        if (!loaded) {
            // PlayerData-bound request: no Bukkit world reads from this
            // (netty) thread, and the request path is rate-limited per
            // player so rotating ghost chunks cannot task-spam the server.
            ret.tawny.truthful.listener.TruthfulPacketListener
                    .requestLiveChunkBuild(this, chunkX, chunkZ);
        }
        return loaded;
    }

    public boolean shouldSkipChecks() {
        double tps = Truthful.getInstance().getTps();
        double minTps = Truthful.getInstance().getConfiguration().getMinTps();
        if (tps < minTps) return true;
        if (isServerFrozen()) return true;

        // Unloaded/uncached chunk = NEVER judge (an empty world view reads
        // every block as air, which fabricates hover, air-speed and
        // ground-spoof violations out of thin air). Keep the live build
        // request looping until the chunk exists - but only while a
        // world-consuming check is actually enabled AND the claimed position
        // is REACHABLE from the last accepted one: the skip used to key on
        // the claimed position alone, so one ghost "far position" packet
        // flipped every check on the profile off and fed the teleport-churn
        // disabler. Implausible claims get judged, not excused.
        if (Truthful.getInstance().getCheckManager().isWorldConsumed() && !isChunkLoaded()) {
            double deltaXZ = getDeltaXZ();
            return deltaXZ <= ret.tawny.truthful.utils.math.PositionSanity.MAX_JUMP
                    && Math.abs(getDeltaY()) <= ret.tawny.truthful.utils.math.PositionSanity.MAX_JUMP;
        }
        return false;
    }

    public boolean isServerFrozen() { return setbackHandler.shouldBlockMovement(); }

    public boolean isExempt() {
        // Deliberately NARROW: this gate applies to every check, so a broad
        // condition here is a switch that turns the whole anticheat off.
        // TPS/chunk/unloaded status is handled per-check by
        // Check.canCheck() + requiresStableSimulation, and flight status is
        // handled by each movement check's own predicate. Only permanent
        // staff exemption and the join grace silence everything.
        return permanentlyExempt || isExempt(ExemptionType.JOIN);
    }

    public void setExemption(ExemptionType type, int ticks) { exemptions.put(type, ticks); }
    public void setVelocityExemption(int ticks) { setExemption(ExemptionType.VELOCITY, ticks); }
    public boolean isExempt(ExemptionType type) { return permanentlyExempt || exemptions.containsKey(type); }

    public void setExempt(boolean exempt) { this.permanentlyExempt = exempt; }
    public boolean isJoinExempt() { return isExempt(ExemptionType.JOIN); }
    public boolean isRotationExempt() { return isExempt(ExemptionType.JOIN) || isInsideVehicle() || (ticksTracked - lastVehicleExitTick < 10); }
    public boolean isMovementExempt() { return isExempt(ExemptionType.JOIN) || isAllowFlight() || isInsideVehicle() || (ticksTracked - lastVehicleExitTick < 10); }
    public boolean isDamageExempt() { return isExempt(ExemptionType.DAMAGE); }

    public void tickExemptions() {
        // Called once per server tick from the global timer. CHM
        // computeIfPresent is atomic, so this can run off the packet thread.
        exemptions.keySet().forEach(k -> exemptions.computeIfPresent(k, (key, val) -> val - 1 <= 0 ? null : val - 1));
    }

    /**
     * Arms the spear-lunge exemption through a token bucket: sustained
     * right-click spam can no longer keep the movement window permanently
     * open. A legitimate lunge (quick use, hit-triggered, or charged release)
     * is unaffected while tokens remain; a macro burns through the budget and
     * gets judged for the rest of the window.
     */
    public void armSpearLunge(int ticks) {
        int now = ticksTracked;
        if (now - spearTokenWindowStart >= SPEAR_TOKEN_WINDOW_TICKS || now < spearTokenWindowStart) {
            spearTokenWindowStart = now;
            spearTokens = SPEAR_TOKEN_CAPACITY;
        }
        if (spearTokens <= 0) return;
        int grant = Math.min(ticks, spearTokens);
        spearTokens -= grant;
        // Capture the lunge direction at arming so the simulation can offer it
        // as a bounded impulse candidate instead of blanket-exempting the
        // window (the bypass Polar-style simulators leave open: fly, stop,
        // re-lunge, repeat).
        double yaw = Math.toRadians(getYaw());
        double pitch = Math.toRadians(getPitch());
        double cosP = Math.cos(pitch);
        this.lungeDirX = -Math.sin(yaw) * cosP;
        this.lungeDirY = -Math.sin(pitch);
        this.lungeDirZ = Math.cos(yaw) * cosP;
        this.impulseStrength = 0.0D;
        this.lungeFresh = true;
        setExemption(ExemptionType.SPEAR_LUNGE, grant);
    }

    /**
     * Riptide launch: vanilla velocity = look * (3 * ((1 + level) / 4)),
     * applied client-side (no velocity packet). Modeled exactly instead of
     * exempting the spin window.
     */
    public void armRiptideImpulse(double strength) {
        double yaw = Math.toRadians(getYaw());
        double pitch = Math.toRadians(getPitch());
        double cosP = Math.cos(pitch);
        this.lungeDirX = -Math.sin(yaw) * cosP;
        this.lungeDirY = -Math.sin(pitch);
        this.lungeDirZ = Math.cos(yaw) * cosP;
        this.impulseStrength = strength;
        this.lungeFresh = true;
    }

    public double getImpulseStrength() { return impulseStrength; }

    /** Consumes the one-shot "offer the impulse seed this tick" flag. */
    public boolean consumeLungeFresh() {
        if (!lungeFresh) return false;
        lungeFresh = false;
        return true;
    }

    public double getLungeDirX() { return lungeDirX; }
    public double getLungeDirY() { return lungeDirY; }
    public double getLungeDirZ() { return lungeDirZ; }

    public ret.tawny.truthful.predictionengine.PredictionState getPredictionState() { return predictionState; }

    public int getVehicleEntityId() { return vehicleEntityId; }

    public void setVehicleEntityId(int id) {
        this.vehicleEntityId = id;
        if (id == -1) {
            this.vehicleYaw = getYaw();
            this.vehiclePitch = getPitch();
        }
    }

    public float getVehicleYaw() { return vehicleEntityId == -1 ? getYaw() : vehicleYaw; }
    public float getVehiclePitch() { return vehicleEntityId == -1 ? getPitch() : vehiclePitch; }

    public void setVehicleRotation(float yaw, float pitch) {
        this.vehicleYaw = yaw;
        this.vehiclePitch = pitch;
    }

    public void setVehicleHeadYaw(float yaw) { this.vehicleYaw = yaw; }

    public Player getPlayer() { return player; }
    public UUID getPlayerId() { return playerId; }

    public void teardown() {
        velocityQueue.clear();
        teleportQueue.clear();
        transactionsSent.clear();
        transactionOrder.clear();
        exemptions.clear();
        actionTracker.reset();
    }

    public VelocityQueue getVelocities() { return velocityQueue; }
    public TeleportQueue getTeleportQueue() { return teleportQueue; }
    public PositionTracker getPositionTracker() { return positionTracker; }
    public ActionTracker getActionTracker() { return actionTracker; }
    public SetbackHandler getSetbackHandler() { return setbackHandler; }
    public MovementState getMovementState() { return movementState; }
    public MovementContext getMovementContext() { return movementContext; }

    public double getDeltaXZ() { return positionTracker.getDeltaXZ(); }
    public double getLastDeltaXZ() { return positionTracker.getLastDeltaXZ(); }
    public double getX() { return positionTracker.getX(); }
    public double getY() { return positionTracker.getY(); }
    public double getZ() { return positionTracker.getZ(); }
    public double getDeltaX() { return positionTracker.getDeltaX(); }
    public double getDeltaY() { return positionTracker.getDeltaY(); }
    public double getDeltaZ() { return positionTracker.getDeltaZ(); }
    public double getLastDeltaX() { return positionTracker.getLastDeltaX(); }
    public double getLastDeltaY() { return positionTracker.getLastDeltaY(); }
    public double getLastDeltaZ() { return positionTracker.getLastDeltaZ(); }
    public float getYaw() { return positionTracker.getYaw(); }
    public float getPitch() { return positionTracker.getPitch(); }
    public float getLastYaw() { return positionTracker.getLastYaw(); }
    public float getLastPitch() { return positionTracker.getLastPitch(); }
    public float getDeltaYaw() { return positionTracker.getDeltaYaw(); }
    public float getDeltaPitch() { return positionTracker.getDeltaPitch(); }
    public int getSensitivityPercent() { return positionTracker.getSensitivityPercent(); }
    public double getSensitivityStep() { return positionTracker.getSensitivityStep(); }
    public double getSensitivityConfidence() { return positionTracker.getSensitivityConfidence(); }
    public int getSensitivitySampleCount() { return positionTracker.getSensitivitySampleCount(); }
    /** True only when the grid-search estimator resolved (excludes the fast fallback). */
    public boolean isSensitivityResolved() { return positionTracker.isSensitivityResolved(); }

    public boolean isServerGround() { return positionTracker.isServerGround(); }
    public boolean isClientGround() { return positionTracker.isClientGround(); }
    public boolean isLastGround() { return positionTracker.isLastGround(); }
    public boolean isOnGround() { return positionTracker.isOnGround(); }

    public int getAirTicks() { return positionTracker.getAirTicks(); }
    public int getGroundTicks() { return positionTracker.getGroundTicks(); }
    public int getTicksTracked() { return ticksTracked; }
    public Location getLocation() { return positionTracker.getLocation(); }
    public Location getLastLocation() { return positionTracker.getLastLocation(); }

    public boolean isInventoryOpen() { return actionTracker.isInventoryOpen(); }
    public void setInventoryOpen(boolean b) { actionTracker.setInventoryOpen(b, ticksTracked); }
    public int getInventoryOpenTick() { return actionTracker.getInventoryOpenTick(); }
    public long getLastInventoryClose() { return actionTracker.getLastInventoryClose(); }
    public long getLastWindowClick() { return actionTracker.getLastWindowClick(); }
    public void setLastWindowClick(long t) { actionTracker.setLastWindowClick(t); }

    public boolean isInLiquid() { return positionTracker.isInLiquid(); }
    public boolean isOnClimbable() { return positionTracker.isOnClimbable(); }
    public boolean isInWeb() { return positionTracker.isInWeb(); }
    public boolean isNearVehicle() { return positionTracker.isNearVehicle(); }
    public void setNearVehicle(boolean b) { positionTracker.setNearVehicle(b); }
    public boolean isNearEntity() { return positionTracker.isNearEntity(); }
    public void setNearEntity(boolean b) { positionTracker.setNearEntity(b); }
    public boolean isUnderBlock() { return positionTracker.isUnderBlock(); }

    public boolean isSprinting() { return actionTracker.isSprinting(); }
    public void setSprinting(boolean b) { actionTracker.setSprinting(b); }
    public boolean isLastSprinting() { return lastSprinting; }
    public void finishMovementTick() {
        this.lastSprinting = isSprinting();
        // Placement context is only valid until the next movement packet;
        // leaving it stale let checks (e.g. ScaffoldH) reuse a placement
        // from minutes ago.
        this.currentBlockPlacement = null;
    }
    /** Advances queued velocity after the current movement packet has been checked. */
    public void advanceVelocityState(boolean positionUpdate) {
        // On the ground, leftover knockback decays by slipperiness*0.91 (â‰ˆ0.546
        // on stone), NOT flat 0.91. Air-only decay badly over-predicts how long
        // ground KB lives.
        boolean onGround = isServerGround();
        velocityQueue.tick(positionUpdate,
                onGround ? ret.tawny.truthful.checks.impl.movement.MovementCheckSupport.computeGroundFriction(this) : 0.91D);
    }

    public boolean isSneaking() { return actionTracker.isSneaking(); }
    public void setSneaking(boolean b) { actionTracker.setSneaking(b); }
    public boolean isDigging() { return actionTracker.isDigging(); }
    public void setDigging(boolean b) { actionTracker.setDigging(b); }
    public boolean isUsingItem() { return actionTracker.isUsingItem(); }
    public void setUsingItem(boolean b, int ticksTracked) { actionTracker.setUsingItem(b, ticksTracked); }
    public void setUsingItem(boolean b) { actionTracker.setUsingItem(b, ticksTracked); }
    public int getLastUseItemTick() { return actionTracker.getLastUseItemTick(); }

    public boolean isUsingRiptide() { return actionTracker.isUsingRiptide(); }
    public void setUsingRiptide(boolean b) { actionTracker.setUsingRiptide(b); }

    public int getCurrentSlot() { return actionTracker.getCurrentSlot(); }
    public void setCurrentSlot(int s) { actionTracker.setCurrentSlot(s); }
    public void setLastSlot(int s) { actionTracker.setLastSlot(s); }
    public int getLastSlot() { return actionTracker.getLastSlot(); }
    public void setLastSlotSwitchTime(long t) { this.lastSlotSwitchTime = t; }
    public long getLastSlotSwitchTime() { return lastSlotSwitchTime; }
    public void setLastBlockPlaceTime(long t) { this.lastBlockPlaceTime = t; }
    public long getLastBlockPlaceTime() { return lastBlockPlaceTime; }
    public void setLastBlockPlaceTick(int t) { this.lastBlockPlaceTick = t; }
    public int getLastBlockPlaceTick() { return lastBlockPlaceTick; }
    public void setLastGhostBlockTick(int t) { this.lastGhostBlockTick = t; }
    public int getLastGhostBlockTick() { return lastGhostBlockTick; }

    public int getLastHitTick() { return lastHitTick; }
    public void setLastHitTick(int t) { this.lastHitTick = t; }
    public int getLastAttackPacketTick() { return lastAttackPacketTick; }
    public void setLastAttackPacketTick(int t) { this.lastAttackPacketTick = t; }
    public int getLastDamageTick() { return lastDamageTick; }
    public void setLastDamageTick(int t) { this.lastDamageTick = t; }
    public int getLastRiptideTick() { return lastRiptideTick; }
    public void setLastRiptideTick(int t) { this.lastRiptideTick = t; }
    public int getLastFireworkTick() { return lastFireworkTick; }
    public void setLastFireworkTick(int t) { this.lastFireworkTick = t; }
    public int getLastGlideTick() { return lastGlideTick; }

    public int getLastWebTick() { return positionTracker.getLastWebTick(); }
    public int getLastIceTick() { return positionTracker.getLastIceTick(); }
    public int getLastSlimeTick() { return positionTracker.getLastSlimeTick(); }
    public int getLastSoulSandTick() { return positionTracker.getLastSoulSandTick(); }
    public int getLastHoneyTick() { return positionTracker.getLastHoneyTick(); }

    public void setLastVehicleExitTick(int t) { this.lastVehicleExitTick = t; }
    public int getLastVehicleExitTick() { return lastVehicleExitTick; }
    public void setLastVehicleTick(int t) { this.lastVehicleTick = t; }
    public int getLastVehicleTick() { return lastVehicleTick; }
    public int getLastUnderBlockTick() { return positionTracker.getLastUnderBlockTick(); }

    public PlayerBlockPlacePacketWrapper getCurrentBlockPlacement() { return currentBlockPlacement; }
    public void setCurrentBlockPlacement(PlayerBlockPlacePacketWrapper p) { this.currentBlockPlacement = p; }
    public void setLastTarget(Entity e) {
        this.lastTarget = e;
        if (e != null) this.lastTargetId = e.getEntityId();
        else this.lastTargetId = -1;
    }
    public Entity getLastTarget() { return lastTarget; }
    public int getLastTargetId() { return lastTargetId; }

    public void setPreExitVehicleSpeed(double s) { this.preExitVehicleSpeed = s; }
    public double getBaritoneTrust() { return baritoneTrust; }
    public void lowerBaritoneTrust(double amount) { this.baritoneTrust = Math.max(0, baritoneTrust - amount); }

    public void onFireworkInteraction() { fireworkTracker.onFireworkInteraction(ticksTracked, getDeltaXZ(), player.isGliding()); }

    public boolean hasVelocity() { return velocityQueue.hasActiveVelocity(); }
    public int getLastVelocityTick() { return lastVelocityTick; }
    public void setLastVelocityTick(int t) { this.lastVelocityTick = t; }

    /** Called when a piston near this player extends/retracts (main thread). */
    public void onPistonPush(double dirX, double dirY, double dirZ) {
        this.lastPistonTick = this.ticksTracked;
        this.pistonDirX = dirX;
        this.pistonDirY = dirY;
        this.pistonDirZ = dirZ;
        this.setExemption(ExemptionType.PISTON, 4);
    }

    public int getLastPistonTick() { return lastPistonTick; }
    public double getPistonDirX() { return pistonDirX; }
    public double getPistonDirY() { return pistonDirY; }
    public double getPistonDirZ() { return pistonDirZ; }

    public void resetTransientState() {
        this.velocityQueue.clear();
        this.exemptions.clear();
        this.transactionsSent.clear();
        this.timerBalanceRealTime = System.nanoTime();
        this.predictionState.reset();

        if (player.isOnline()) {
            Location loc = player.getLocation();
            this.positionTracker.reset(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        }

        this.setExemption(ExemptionType.RESPAWN, 60);
        this.setExemption(ExemptionType.JOIN, 60);

        // DO NOT reset ticksTracked to 0! Monotonic clock must be preserved
        // so that (ticksTracked - lastTick) never evaluates to negative.
        // Instead, re-seed all prior event timestamps to a safe past sentinel (-1000).
        this.lastGlideTick = -1000;
        this.lastDamageTick = -1000;
        this.lastVehicleTick = -1000;
        this.lastVelocityTick = -1000;
        this.lastPistonTick = -1000;
        this.lastCacheUpdate = -1000;
        this.lastServerTeleportTick = -1000;
        this.lastTeleportConfirmTick = -1000;
        this.invalidTeleportConfirms = 0;
        this.actionTracker.reset();

        this.totalViolations = Math.max(0, this.totalViolations - 5);
        this.banning = false;
        this.highPingStartTime = 0;

        updateStateCache();
    }

    public void handleWorldChange() {
        this.cachedWorld = player.getWorld();
        this.worldCache.setWorldUid(player.getWorld().getUID());
        this.teleportQueue.clear();
        this.incomingTeleportTag = TeleportTag.NONE;
        this.hasConfirmTarget = false;
        this.confirmTargetX = 0;
        this.confirmTargetY = 0;
        this.confirmTargetZ = 0;
        resetTransientState();
        this.setExemption(ExemptionType.JOIN, 100);
    }

    public double getEyeHeight(boolean legacy, boolean sneaking, boolean swimming) {
        return positionTracker.getEyeHeight(legacy, sneaking, swimming);
    }

    public double getScale() { return attributeEngine != null ? attributeEngine.getLatestScale() : scaleCache; }
    public double getScaleCacheRaw() { return scaleCache; }

    /**
     * Knockback resistance 0.0-1.0 (netherite +0.1/piece). Debug context ONLY.
     * The server applies A=v*(1-r) before sending the velocity packet, so the
     * packet already reflects resistance. Scaling expectations by it again
     * would double-count and false flag every netherite user.
     */
    public double getKnockbackResistance() { return knockbackResistanceCache; }
    public boolean isSwimmingCached() { return swimmingCache; }

    /** Cached vehicle type name (main-thread refresh) - netty threads must not call Bukkit. */
    public String getVehicleTypeName() { return vehicleTypeNameCache; }

    /** 26.2 air_drag_modifier (default 1.0). */
    public double getAirDragModifier() { return airDragModifierCache; }
    /** 26.2 friction_modifier (default 1.0). */
    public double getFrictionModifier() { return frictionModifierCache; }
    /** 26.2 bounciness (default 0.0). On landing, the higher of block/entity bounciness applies. */
    public double getEntityBounciness() { return bouncinessCache; }

    /** Vanilla player width (0.6) scaled. Used for wall/ceiling radius checks. */
    public double getPlayerWidth() { return 0.6D * Math.max(0.0625D, Math.min(16.0D, getScale())); }

    /**
     * Vanilla player height by pose, scaled.
     * Standing 1.8, sneaking 1.5, swimming/gliding 0.6 (also used for crawling).
     */
    public double getPlayerHeight() {
        double scale = Math.max(0.0625D, Math.min(16.0D, getScale()));
        boolean swimLike = swimmingCache || isGliding();
        if (swimLike) return 0.6D * scale;
        if (isSneaking()) return 1.5D * scale;
        return 1.8D * scale;
    }

    /** Scale-aware eye height. Base eyes 1.62 / 1.27 / 0.4 multiplied by scale. */
    public double getEyeHeightScaled() {
        double scale = Math.max(0.0625D, Math.min(16.0D, getScale()));
        boolean swimLike = swimmingCache || isGliding();
        if (swimLike) return 0.4D * scale;
        if (isSneaking()) return 1.27D * scale;
        return 1.62D * scale;
    }

    public int getTicksSinceTeleport() { return this.ticksTracked - this.movementState.getLastTeleportTick(); }
    public boolean isTeleportTick() { return isExempt(ExemptionType.TELEPORT); }

    public CompensatedWorld getWorldCache() { return worldCache; }
    public long getPing() { return transactionPing; }
    public void setPing(long ping) { this.transactionPing = ping; }
    public String getClientBrand() { return clientBrand; }
    public void setClientBrand(String brand) { this.clientBrand = brand; }
    public int getVl() { return totalViolations; }
    public void addVl(int amount) { this.totalViolations += amount; }
    public boolean isAlertsEnabled() { return alertsEnabled; }
    public void setAlertsEnabled(boolean enabled) { this.alertsEnabled = enabled; }

    // Called from netty threads AND the async ping timer â€” must be atomic.
    private final java.util.concurrent.atomic.AtomicInteger transactionIdCounter =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public short getNextTransactionId() {
        // The old wrap check compared the MASKED id against the raw counter
        // (compareAndSet(id + 1, 0) where id had already been & 0x7FFF), so
        // the reset almost never matched and stale IDs could ack unrelated
        // entries. Compare against the actual pre-increment value.
        int raw = transactionIdCounter.getAndIncrement();
        if ((raw & 0x7FFF) >= 32000) {
            transactionIdCounter.compareAndSet(raw + 1, 0);
        }
        return (short) (raw & 0x7FFF);
    }

    public void handleTransaction(short id) {
        TransactionEntry entry = transactionsSent.remove(id);
        if (entry != null) {
            this.playerClockAtLeast = Math.max(this.playerClockAtLeast, entry.timestamp);
            long nowNanos = System.nanoTime();
            long diffNanos = nowNanos - entry.timestamp;
            this.transactionPing = (this.transactionPing * 3 + (diffNanos / 1_000_000L)) / 4;
            this.timerBalanceRealTime = Math.max(this.timerBalanceRealTime, this.playerClockAtLeast - clockDrift);
        }

        this.velocityQueue.confirm(id);

        // Reveal block changes this client has now processed (latency view).
        this.worldCache.onTransactionAck(id);

        if (this.setbackHandler.onTransaction(id)) {
                this.timerBalanceRealTime = System.nanoTime();
        }
    }

    public void recordTransactionSent(short id) {
        this.transactionsSent.put(id, new TransactionEntry(id, System.nanoTime()));
        this.transactionOrder.add(id);
        while (transactionOrder.size() > 100) {
            Short oldestId = transactionOrder.poll();
            if (oldestId != null) this.transactionsSent.remove(oldestId);
        }
    }

    public void markExplosionPacket() { this.lastExplosionPacketTime = System.currentTimeMillis(); }
    public boolean isInExplosionGraceWindow(long millis) { return System.currentTimeMillis() - this.lastExplosionPacketTime <= millis; }
    public boolean isTeleportPending() { return setbackHandler.shouldBlockMovement(); }
    public boolean isBanning() { return banning; }

    public long getHighPingStartTime() { return highPingStartTime; }
    public void setHighPingStartTime(long t) { this.highPingStartTime = t; }


    public void setBanning(boolean banning) {
        this.banning = banning;
        if (banning) this.banStartTime = System.currentTimeMillis();
    }

    public long getBanStartTime() { return banStartTime; }
    public double getTransactionTimerBalance() { return (double) (timerBalanceRealTime - System.nanoTime()) / 1_000_000.0; }

    private static class TransactionEntry {
        final short id;
        final long timestamp;
        TransactionEntry(short id, long timestamp) {
            this.id = id;
            this.timestamp = timestamp;
        }
    }
}
