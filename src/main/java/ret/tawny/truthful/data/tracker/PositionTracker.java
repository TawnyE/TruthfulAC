package ret.tawny.truthful.data.tracker;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.math.SensitivityEstimator;
import ret.tawny.truthful.utils.math.SensitivityUtil;
import ret.tawny.truthful.utils.world.BlockPropertyRegistry;
import ret.tawny.truthful.utils.world.WorldUtils;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

public class PositionTracker {

    private final PlayerData data;
    private final Player player;

    private double x, y, z;
    private double lastX, lastY, lastZ;
    private double deltaX, deltaY, deltaZ;
    private double lastDeltaX, lastDeltaY, lastDeltaZ;
    private float yaw, pitch;
    private float lastYaw, lastPitch;
    private float deltaYaw, deltaPitch;
    private float lastDeltaYaw, lastDeltaPitch;

    private boolean onGroundServer;
    private boolean onGroundClient;
    private boolean lastOnGround;
    private int airTicks;
    private int lastAirTicks;
    private int groundTicks;
    private int lastLandTick = -100;
    private int ticksTracked;

    // Cached |deltaXZ| / |lastDeltaXZ| - hypot was recomputed on every getter
    // call, and the hot path asks dozens of times per packet.
    private double deltaXZ;
    private double lastDeltaXZ;

    // Ground friction is derived from the block under the feet; several
    // consumers ask for it within the same tick at the same position, so
    // compute it once per tick and serve the cached value.
    private double cachedGroundFriction = 0.5460000417236805D; // 0.6 * 0.91 float-ish
    private int frictionCacheTick = Integer.MIN_VALUE;
    private double cachedGroundFrictionRaw = 0.6D;
    private int frictionRawCacheTick = Integer.MIN_VALUE;

    private boolean nearVehicle;
    private boolean nearEntity;
    private boolean underBlock;
    private boolean inLiquid;
    private boolean onClimbable;
    private boolean inWeb;

    private int lastWebTick = -100;
    private int lastUnderBlockTick = -100;
    private int lastSlimeTick = -100;
    private int lastIceTick = -100;
    private int lastSoulSandTick = -100;
    private int lastHoneyTick = -100;
    private int lastBounceCapableTick = -100;
    private int lastGeyserTick = -100;
    private int lastStairTick = -100;
    private int lastBubbleColumnTick = -100;
    // Big dripleaf heads tilt and drop/tilt-slide entities after a short
    // stand - vanilla movement the solid-platform model can't produce.
    private int lastDripleafTick = -100;
    // Pre-collision fall speed recorded at the landing tick on a bounce-
    // capable block; vanilla computes restitution from THIS, not from the
    // truncated landing delta.
    private volatile double bounceImpactY = Double.NaN;
    private volatile int bounceImpactTick = -100;

    // Bubble column state at the body (null = not in a column).
    private Boolean bubbleColumnDrag = null;

    // Honest entity-support geometry, stamped by the environment task:
    // highest collision-box top of a nearby entity the feet can rest on.
    private volatile double nearEntitySupportY = Double.NaN;
    private volatile int nearEntityCount;

    private final SensitivityEstimator sensitivityEstimator = new SensitivityEstimator();
    private int fastPitchSens = -1;
    private int fastPitchStreak = 0;
    private int lastFastCandidate = -1;

    public PositionTracker(PlayerData data, Player player) {
        this.data = data;
        this.player = player;
        Location loc = player.getLocation();
        this.x = loc.getX();
        this.y = loc.getY();
        this.z = loc.getZ();
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;
        this.yaw = loc.getYaw();
        this.pitch = loc.getPitch();
        this.lastYaw = yaw;
        this.lastPitch = pitch;
    }

    public void handleUpdate(RelMovePacketWrapper event) {
        this.ticksTracked++;
        this.lastYaw = this.yaw;
        this.lastPitch = this.pitch;
        this.lastDeltaYaw = this.deltaYaw;
        this.lastDeltaPitch = this.deltaPitch;

        if (event.isRotationUpdate()) {
            this.yaw = event.getYaw();
            this.pitch = event.getPitch();
            this.deltaYaw = Math.abs(this.yaw - this.lastYaw) % 360.0F;
            if (this.deltaYaw > 180.0F)
                this.deltaYaw = 360.0F - this.deltaYaw;
            this.deltaPitch = Math.abs(this.pitch - this.lastPitch);

            processSensitivity(this.deltaYaw, this.deltaPitch);
        }

        if (event.isPositionUpdate()) {
            this.lastX = this.x;
            this.lastY = this.y;
            this.lastZ = this.z;
            this.lastDeltaX = this.deltaX;
            this.lastDeltaY = this.deltaY;
            this.lastDeltaZ = this.deltaZ;
            this.lastDeltaXZ = this.deltaXZ;
            this.lastOnGround = this.onGroundServer;

            this.x = event.getX();
            this.y = event.getY();
            this.z = event.getZ();
            this.deltaX = x - this.lastX;
            this.deltaY = y - this.lastY;
            this.deltaZ = z - this.lastZ;
            this.deltaXZ = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

            this.onGroundClient = event.isGround();
            if (isWorldPipelineActive()) {
                // Ground truth is collision-derived: the client's ground
                // claim ANDed with a real downward collision probe at the
                // arrived position. The old ±0.75 support window stayed
                // "true" through entire jump arcs, corrupting airTicks,
                // wasOnGround and every gate built on them (sprint-jump
                // boost riding, wrong physics regime, false hover contexts).
                this.onGroundServer = event.isGround() && WorldUtils.hasSupportBelow(data, 0.10D);

                // Shape-boundary fallback: standing on ANY partial-height
                // block (beds 9/16, slabs 8/16, farmland 15/16...) the feet
                // rest at a 1/16 grid line. When the block cache misses the
                // cell (the known cache-miss class) the down-probe finds
                // nothing, but the client's ground claim at a shape boundary
                // with resting/landing motion is geometric truth.
                // Generalizes the old slab-only fix.
                if (event.isGround() && !this.onGroundServer
                        && WorldUtils.isAtShapeBoundary(data)) {
                    this.onGroundServer = true;
                }

                // Poisoned-snapshot self-heal: the client claims ground but
                // the cached world reads AIR in every cell around the feet -
                // that is an empty-snapshot build race, not a flying player.
                // Purge the chunk and request a live rebuild instead of
                // judging.
                if (event.isGround() && !this.onGroundServer && WorldUtils.isAreaAllAirAround(data)) {
                    int cx = (int) Math.floor(data.getX()) >> 4;
                    int cz = (int) Math.floor(data.getZ()) >> 4;
                    Truthful.getInstance().getGlobalWorldCache().removeChunk(data.getWorldCache().getWorldUid(), cx, cz);
                    ret.tawny.truthful.listener.TruthfulPacketListener.requestLiveChunkBuild(data, cx, cz);
                    // Do not grant onGroundServer = true when standing in air (prevents air-jump ghost-block fly bypasses)
                }

                checkEnvironment();
            } else {
                // No enabled check consumes the world model: the probe, the
                // environment scans and the cache self-heal are pure overhead
                // on this profile. The client's ground claim is the best
                // available truth for the non-world checks that remain
                // (clicks, timers, packet order...), which never touch block
                // data anyway.
                this.onGroundServer = event.isGround();
            }

            this.lastAirTicks = this.airTicks;
            if (this.onGroundServer) {
                // Record pre-collision impact for bounce restitution when
                // landing on a bounce-capable block.
                if (this.airTicks > 0
                        && data.getTicksTracked() - this.lastBounceCapableTick <= 3
                        && this.lastDeltaY < -0.08D) {
                    this.bounceImpactY = this.lastDeltaY;
                    this.bounceImpactTick = this.ticksTracked;
                }
                this.groundTicks++;
                if (this.airTicks > 0) {
                    this.lastLandTick = this.ticksTracked;
                }
                this.airTicks = 0;
            } else {
                this.airTicks++;
                this.groundTicks = 0;
            }
        } else {
            // Rotation-only / flying-only packets also carry the client ground bit,
            // and checks reading deltas on these packets must not see a stale copy
            // of the last position tick (double-processing + frozen ground flags).
            // NOTE: while riding, the vehicle's motion continues between
            // VEHICLE_MOVE packets - zeroing the deltas here broke the boat
            // momentum model (pred=[0,0,0] while the boat cruised).
            this.onGroundClient = event.isGround();
            if (!data.isInsideVehicle()) {
                this.deltaX = 0.0D;
                this.deltaY = 0.0D;
                this.deltaZ = 0.0D;
                this.deltaXZ = 0.0D;
            }
        }
    }

    /** True while at least one enabled check consumes the compensated world model. */
    private boolean isWorldPipelineActive() {
        ret.tawny.truthful.checks.registry.CheckRegistry registry =
                Truthful.getInstance().getCheckManager();
        return registry == null || registry.isWorldConsumed();
    }

    /**
     * Align this tracker with PlayerData's tick counter after resetTransientState().
     * All lastXTick stamps stored here are in THIS counter's epoch; if PlayerData
     * restarts at 0 and this doesn't, every "ticks since X" comparison breaks.
     */
    public void syncTickCounter(int ticks) {
        this.ticksTracked = ticks;
        this.frictionCacheTick = Integer.MIN_VALUE;
        this.frictionRawCacheTick = Integer.MIN_VALUE;
        this.lastSlimeTick = -100;
        this.lastIceTick = -100;
        this.lastSoulSandTick = -100;
        this.lastHoneyTick = -100;
        this.lastWebTick = -100;
        this.lastUnderBlockTick = -100;
        this.lastLandTick = -100;
        this.lastBounceCapableTick = -100;
        this.lastGeyserTick = -100;
        this.lastDripleafTick = -100;
    }

    public void reset(double x, double y, double z, float yaw, float pitch) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;

        this.deltaX = 0;
        this.deltaY = 0;
        this.deltaZ = 0;
        this.lastDeltaX = 0;
        this.lastDeltaY = 0;
        this.lastDeltaZ = 0;
        this.deltaXZ = 0;
        this.lastDeltaXZ = 0;
        this.frictionCacheTick = Integer.MIN_VALUE;
        this.frictionRawCacheTick = Integer.MIN_VALUE;

        this.yaw = yaw;
        this.pitch = pitch;
        this.lastYaw = yaw;
        this.lastPitch = pitch;
        this.deltaYaw = 0;
        this.deltaPitch = 0;

        // Collision-derived ground (see handleUpdate). Teleports carry no
        // client ground claim, so the probe alone decides - and only runs
        // while the world model is actually consumed.
        this.onGroundServer = isWorldPipelineActive() && WorldUtils.hasSupportBelow(data, 0.10D);
        this.onGroundClient = this.onGroundServer;
        this.groundTicks = this.onGroundServer ? 1 : 0;
        this.airTicks = this.onGroundServer ? 0 : 1;

        this.lastAirTicks = 0;
        this.lastOnGround = this.onGroundServer;
        this.lastLandTick = -100;
        this.lastSlimeTick = -100;
        this.lastIceTick = -100;
        this.lastSoulSandTick = -100;
        this.lastHoneyTick = -100;
        this.lastBounceCapableTick = -100;
        this.lastGeyserTick = -100;
        this.bubbleColumnDrag = null;
        this.lastStairTick = -100;
        this.lastBubbleColumnTick = -100;
        this.lastDripleafTick = -100;
        this.sensitivityEstimator.reset();
        this.fastPitchSens = -1;
        this.fastPitchStreak = 0;
        this.lastFastCandidate = -1;
    }

    private void checkEnvironment() {
        this.inLiquid = WorldUtils.isLiquid(data);
        this.onClimbable = WorldUtils.hasClimbableNearby(data);
        this.inWeb = WorldUtils.isInWeb(data);
        if (this.inWeb)
            this.lastWebTick = this.ticksTracked;
        this.underBlock = WorldUtils.isHeadHitter(data);
        if (this.underBlock)
            this.lastUnderBlockTick = this.ticksTracked;

        if (WorldUtils.isNearMaterial(data, Material.SLIME_BLOCK))
            this.lastSlimeTick = this.ticksTracked;
        if (WorldUtils.isNearIce(data))
            this.lastIceTick = this.ticksTracked;
        // Soul sand slowdown applies only when the feet are sunk INTO the
        // block (its top is 1/16 short) - standing cleanly on top is full
        // speed. Vanilla 1.15+ probes floor(y - 0.500001) for the speed
        // factor, which means the slowdown bleeds THROUGH a carpet or
        // other partial block stacked on soul sand (the classic elevator
        // tower), but NOT through a full block covering it.
        {
            int fX = (int) Math.floor(data.getX());
            int fY = (int) Math.floor(data.getY());
            int fZ = (int) Math.floor(data.getZ());
            String feetCell = BlockPropertyRegistry.getNameFast(data.getWorldCache().getBlockId(fX, fY, fZ)).toUpperCase();
            if (feetCell.contains("SOUL_SAND") || feetCell.contains("SOUL_SOIL")) {
                this.lastSoulSandTick = this.ticksTracked;
            } else {
                // Probe depth: feet cell clean (carpet/air/other) -> vanilla
                // reads the block half a block below for the speed factor.
                int pY = (int) Math.floor(data.getY() - 0.500001D);
                String probeCell = BlockPropertyRegistry.getNameFast(data.getWorldCache().getBlockId(fX, pY, fZ)).toUpperCase();
                if (probeCell.contains("SOUL_SAND") || probeCell.contains("SOUL_SOIL")) {
                    this.lastSoulSandTick = this.ticksTracked;
                }
            }
            if (feetCell.contains("HONEY_BLOCK")) {
                this.lastHoneyTick = this.ticksTracked;
            }
        }
        if (WorldUtils.isNearMaterial(data, Material.HONEY_BLOCK) == false) {
            // Honey contact requires the block at the feet or directly below
            // (vanilla applies honey slowdown on contact, not proximity).
            int fX = (int) Math.floor(data.getX());
            int fZ = (int) Math.floor(data.getZ());
            int fY = (int) Math.floor(data.getY());
            String cell = BlockPropertyRegistry.getNameFast(data.getWorldCache().getBlockId(fX, fY, fZ)).toUpperCase();
            String under = BlockPropertyRegistry.getNameFast(data.getWorldCache().getBlockId(fX, fY - 1, fZ)).toUpperCase();
            if (cell.contains("HONEY_BLOCK") || under.contains("HONEY_BLOCK")) {
                this.lastHoneyTick = this.ticksTracked;
            }
        }
        if (data.getMovementContext().isHoney() || data.getTicksTracked() - this.lastHoneyTick < 8) {
            this.lastHoneyTick = Math.max(this.lastHoneyTick, -100);
        }

        // Bounce-capable ground (beds, slime, shelf mushrooms, straw beds):
        // stamps the tick so bounce takeoffs read as legal candidates.
        if (WorldUtils.isBouncyBelow(data)) {
            this.lastBounceCapableTick = this.ticksTracked;
        }

        // Bubble column presence + direction (soul sand = drag false = up, magma = drag true = down).
        this.bubbleColumnDrag = WorldUtils.bubbleColumnDragAt(data);
        if (this.bubbleColumnDrag != null) {
            this.lastBubbleColumnTick = this.ticksTracked;
        }

        // Powder snow slows and sinks entities; stamp it so the engine's
        // powder candidates engage and the exemption context is real.
        if (WorldUtils.isNearMaterial(data, org.bukkit.Material.POWDER_SNOW)) {
            this.data.setExemption(ret.tawny.truthful.data.ExemptionType.POWDER_SNOW, 2);
        }

        // 26.2+ potent sulfur geysers under a water column apply periodic
        // upward impulses - honest engines exempt while above one.
        if (ret.tawny.truthful.Truthful.getInstance().getVersionManager().getAdapter().supportsGeysers()
                && WorldUtils.isGeyserColumnBelow(data)) {
            this.lastGeyserTick = this.ticksTracked;
        }

        // Stair/slab proximity: corner stair variants are modeled as straight,
        // so a small vertical allowance window is stamped near them.
        if (WorldUtils.isNearStairOrSlab(data)) {
            this.lastStairTick = this.ticksTracked;
        }

        // Big dripleaf head under the feet: the platform tilts and slides the
        // player off after a short stand. Stamp it so the engine can add a
        // bounded allowance instead of fighting the tilt.
        {
            int fX = (int) Math.floor(data.getX());
            int fZ = (int) Math.floor(data.getZ());
            int underY = (int) Math.floor(data.getY() - 0.500001D);
            String under = BlockPropertyRegistry.getNameFast(
                    data.getWorldCache().getBlockId(fX, underY, fZ)).toUpperCase();
            if (under.contains("BIG_DRIPLEAF")) {
                this.lastDripleafTick = this.ticksTracked;
            }
        }
    }

    private void processSensitivity(float dYaw, float dPitch) {
        if (dPitch > 0.001f && dPitch < 35.0f) {
            sensitivityEstimator.accept(dPitch);

            int candidate = SensitivityUtil.getSensitivityFromPitchGCD(dPitch);
            if (candidate >= 0 && candidate <= 200) {
                if (candidate == lastFastCandidate) {
                    fastPitchStreak++;
                    if (fastPitchStreak >= 3) {
                        fastPitchSens = candidate;
                    }
                } else {
                    lastFastCandidate = candidate;
                    fastPitchStreak = 1;
                }
            }
        }

        if (dYaw > 0.001f && dYaw < 35.0f) {
            sensitivityEstimator.accept(dYaw);
        }
    }

    public Location getLocation() {
        return new Location(data.getWorld(), x, y, z, yaw, pitch);
    }

    public Location getLastLocation() {
        return new Location(data.getWorld(), lastX, lastY, lastZ, lastYaw, lastPitch);
    }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public double getDeltaX() { return deltaX; }
    public double getDeltaY() { return deltaY; }
    public double getDeltaZ() { return deltaZ; }
    public double getLastDeltaX() { return lastDeltaX; }
    public double getLastDeltaY() { return lastDeltaY; }
    public double getLastDeltaZ() { return lastDeltaZ; }
    public double getDeltaXZ() { return deltaXZ; }
    public double getLastDeltaXZ() { return lastDeltaXZ; }

    /**
     * Ground friction (blockFriction * 0.91) for the current tick, computed
     * once and shared by every consumer. Position is fixed within a tick, so
     * the value cannot change between asks; the old path re-read nine blocks
     * and decoded states up to five times per packet.
     */
    public double groundFrictionForTick() {
        if (frictionCacheTick == ticksTracked) return cachedGroundFriction;
        cachedGroundFriction = ret.tawny.truthful.checks.impl.movement.MovementCheckSupport
                .computeGroundFrictionUncached(data);
        frictionCacheTick = ticksTracked;
        return cachedGroundFriction;
    }

    /** Raw max block friction (no *0.91) - vanilla's accel formula input. */
    public double groundFrictionRawForTick() {
        if (frictionRawCacheTick == ticksTracked) return cachedGroundFrictionRaw;
        cachedGroundFrictionRaw = ret.tawny.truthful.checks.impl.movement.MovementCheckSupport
                .computeGroundFrictionRawUncached(data);
        frictionRawCacheTick = ticksTracked;
        return cachedGroundFrictionRaw;
    }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
    public float getLastYaw() { return lastYaw; }
    public float getLastPitch() { return lastPitch; }
    public float getDeltaYaw() { return deltaYaw; }
    public float getDeltaPitch() { return deltaPitch; }
    public float getLastDeltaYaw() { return lastDeltaYaw; }
    public float getLastDeltaPitch() { return lastDeltaPitch; }
    public boolean isOnGround() { return onGroundServer; }
    public boolean isServerGround() { return onGroundServer; }
    public boolean isClientGround() { return onGroundClient; }
    public boolean isLastGround() { return lastOnGround; }
    public int getAirTicks() { return airTicks; }
    public int getLastAirTicks() { return lastAirTicks; }
    public int getGroundTicks() { return groundTicks; }
    public int getLastLandTick() { return lastLandTick; }
    public int getTicksTracked() { return ticksTracked; }
    public boolean isInLiquid() { return inLiquid; }
    public boolean isOnClimbable() { return onClimbable; }
    public boolean isInWeb() { return inWeb; }
    public boolean isUnderBlock() { return underBlock; }
    public boolean isNearVehicle() { return nearVehicle; }
    public void setNearVehicle(boolean b) { this.nearVehicle = b; }
    public boolean isNearEntity() { return nearEntity; }
    public void setNearEntity(boolean b) { this.nearEntity = b; }
    public int getLastWebTick() { return lastWebTick; }
    public int getLastUnderBlockTick() { return lastUnderBlockTick; }
    public int getLastBounceCapableTick() { return lastBounceCapableTick; }
    public int getLastGeyserTick() { return lastGeyserTick; }
    public int getLastStairTick() { return lastStairTick; }
    public int getLastBubbleColumnTick() { return lastBubbleColumnTick; }
    public int getLastDripleafTick() { return lastDripleafTick; }
    public double getBounceImpactY() { return bounceImpactY; }
    public int getBounceImpactTick() { return bounceImpactTick; }

    /** True if inside a bubble column; drag=true pushes down (magma), false pushes up (soul sand). */
    public Boolean getBubbleColumnDrag() { return bubbleColumnDrag; }

    /** Highest nearby entity box-top the feet can rest on; NaN when none. */
    public double getNearEntitySupportY() { return nearEntitySupportY; }
    public void setNearEntitySupportY(double y) { this.nearEntitySupportY = y; }

    /** Colliding entity count within the environment scan radius. */
    public int getNearEntityCount() { return nearEntityCount; }
    public void setNearEntityCount(int count) { this.nearEntityCount = count; }

    public int getSensitivityPercent() {
        SensitivityEstimator.SensitivityEstimate est = sensitivityEstimator.getEstimate();
        if (est.isResolved()) {
            return est.percent();
        }
        if (fastPitchSens >= 0) {
            return fastPitchSens;
        }
        return -1;
    }

    public double getSensitivityStep() { return sensitivityEstimator.getEstimate().step(); }
    public double getSensitivityConfidence() { return sensitivityEstimator.getEstimate().confidence(); }
    public int getSensitivitySampleCount() { return sensitivityEstimator.getSampleCount(); }
    /** True only when the real (grid-search) estimator resolved - not the fast fallback. */
    public boolean isSensitivityResolved() { return sensitivityEstimator.getEstimate().isResolved(); }
    public double getLastX() { return lastX; }
    public double getLastY() { return lastY; }
    public double getLastZ() { return lastZ; }
    public int getLastSlimeTick() { return lastSlimeTick; }
    public int getLastIceTick() { return lastIceTick; }
    public int getLastSoulSandTick() { return lastSoulSandTick; }
    public int getLastHoneyTick() { return lastHoneyTick; }
    public float getRotationDeviation(boolean pitch) { return pitch ? deltaPitch : deltaYaw; }

    public double getEyeHeight(boolean legacy, boolean sneaking, boolean swimming) {
        // Use the cached glide state - player.isGliding() here ran on packet
        // threads (Folia-illegal, racy on Paper).
        if (swimming || data.isGliding())
            return 0.4;
        if (sneaking)
            return 1.27;
        return PlayerData.EYE_HEIGHT_STANDING;
    }
}
