package ret.tawny.truthful.data;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import ret.tawny.truthful.sync.TeleportQueue;
import ret.tawny.truthful.sync.VelocityQueue;
import ret.tawny.truthful.utils.math.RollingAverage;
import ret.tawny.truthful.utils.world.WorldUtils;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;
import ret.tawny.truthful.wrapper.impl.server.position.SetPositionPacketWrapper;

public final class PlayerData {

    private final Player player;
    private boolean exempt = false;

    // --- NEW: Client Brand ---
    private String clientBrand = "Unknown"; // Default to Unknown

    private double x, lastX, deltaX, y, lastY, deltaY, lastDeltaY, z, lastZ, deltaZ, deltaXZ, lastDeltaXZ;
    private float yaw, lastYaw, deltaYaw, lastDeltaYaw, pitch, lastPitch, deltaPitch, lastDeltaPitch;
    private Location location, lastLocation, lastGroundLocation;
    private int vl, ticksTracked, ticksInAir, ticksOnGround, ticksSinceTeleport, ticksSinceAbility;
    private boolean onGround, lastGround, clientGround, lastClientGround;
    private boolean inLiquid, lastInLiquid, onClimbable, underBlock;
    private Entity lastTarget;
    private int currentSlot, lastSlot;
    private long ping;
    private long lastSlotSwitchTime, lastBlockPlaceTime;
    private int lastBlockPlaceTick;
    private int lastFireworkTick;
    private int lastGhostBlockTick;
    private int lastGlideTick;
    private int lastVehicleExitTick;

    private int lastWebTick;
    private boolean inWeb;

    private boolean nearVehicle;
    private boolean nearEntity;

    public final RollingAverage timerSpeed = new RollingAverage(20);
    private final VelocityQueue velocities = new VelocityQueue();
    private final TeleportQueue teleports = new TeleportQueue();

    public PlayerData(final Player player) {
        this.player = player;
        this.location = player.getLocation();
        this.lastLocation = player.getLocation();
        this.lastGroundLocation = player.getLocation();
        this.currentSlot = player.getInventory().getHeldItemSlot();
        this.lastSlot = player.getInventory().getHeldItemSlot();
        this.lastSlotSwitchTime = System.currentTimeMillis();
        this.lastBlockPlaceTime = -1L;
        this.lastFireworkTick = -100;
        this.lastGhostBlockTick = -100;
        this.lastGlideTick = -100;
        this.lastVehicleExitTick = -100;
        this.lastWebTick = -100;
    }

    public void update(final RelMovePacketWrapper event) {
        if (event.getPlayer() != this.player) return;

        this.ping = player.getPing();
        ++this.ticksTracked;
        ++this.ticksSinceTeleport;

        if (event.isPositionUpdate()) {
            if (teleports.match(event.getX(), event.getY(), event.getZ())) {
                this.ticksSinceTeleport = 0;
            }
        }
        if (this.ticksTracked % 100 == 0) teleports.cleanup();

        if (player.isGliding()) {
            this.lastGlideTick = this.ticksTracked;
        }

        this.inWeb = WorldUtils.isInWeb(player);
        if (this.inWeb) {
            this.lastWebTick = this.ticksTracked;
        }

        this.velocities.removeIf(velocity -> {
            velocity.tick();
            return velocity.hasReceived() && velocity.hasExpired();
        });

        this.lastLocation = this.location;
        this.lastX = this.x;
        this.lastY = this.y;
        this.lastZ = this.z;
        this.lastYaw = this.yaw;
        this.lastPitch = this.pitch;
        this.lastGround = this.onGround;
        this.lastClientGround = this.clientGround;
        this.lastDeltaY = this.deltaY;
        this.lastDeltaXZ = this.deltaXZ;
        this.lastDeltaYaw = this.deltaYaw;
        this.lastDeltaPitch = this.deltaPitch;
        this.lastInLiquid = this.inLiquid;

        this.location = new Location(player.getWorld(),
                event.isPositionUpdate() ? event.getX() : this.x,
                event.isPositionUpdate() ? event.getY() : this.y,
                event.isPositionUpdate() ? event.getZ() : this.z,
                event.isRotationUpdate() ? event.getYaw() : this.yaw,
                event.isRotationUpdate() ? event.getPitch() : this.pitch);

        this.x = this.location.getX();
        this.y = this.location.getY();
        this.z = this.location.getZ();
        this.yaw = this.location.getYaw();
        this.pitch = this.location.getPitch();

        this.deltaX = this.x - this.lastX;
        this.deltaY = this.y - this.lastY;
        this.deltaZ = this.z - this.lastZ;
        this.deltaYaw = this.yaw - this.lastYaw;
        this.deltaPitch = this.pitch - this.lastPitch;
        this.deltaXZ = Math.hypot(this.deltaX, this.deltaZ);

        this.onGround = WorldUtils.safeGround(player);
        this.clientGround = event.isGround();
        this.inLiquid = WorldUtils.isLiquid(player);
        this.onClimbable = WorldUtils.hasClimbableNearby(player);
        this.underBlock = WorldUtils.isSolid(player.getEyeLocation().clone().add(0, 0.5, 0).getBlock());

        if (this.onGround) {
            this.ticksOnGround++;
            this.ticksInAir = 0;
            this.lastGroundLocation = this.location;
        } else {
            this.ticksInAir++;
            this.ticksOnGround = 0;
        }
    }

    public void acceptTeleport(final SetPositionPacketWrapper positionPacketWrapper) {
        this.ticksSinceTeleport = 0;
        final World world = player.getWorld();
        final Location location = new Location(world,
                positionPacketWrapper.getX(),
                positionPacketWrapper.getY(),
                positionPacketWrapper.getZ());
        this.teleports.add(location);
    }

    public double getMaxVelocity() {
        double max = 0.0;
        for (VelocityQueue.Velocity v : this.velocities) {
            if (v.hasReceived() && !v.hasExpired()) {
                Vector vec = v.getVelocityVec();
                double horizontal = Math.sqrt(vec.getX() * vec.getX() + vec.getZ() * vec.getZ());
                if (horizontal > max)
                    max = horizontal;
            }
        }
        return max;
    }

    // --- GETTERS ---
    public Player getPlayer() { return this.player; }

    // Brand Getter/Setter
    public String getClientBrand() { return clientBrand; }
    public void setClientBrand(String clientBrand) { this.clientBrand = clientBrand; }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public float getYaw() { return yaw; }
    public float getLastYaw() { return lastYaw; }
    public float getPitch() { return pitch; }
    public float getLastPitch() { return lastPitch; }
    public int getVl() { return vl; }
    public int increment() { return ++vl; }
    public double getDeltaX() { return deltaX; }
    public double getDeltaY() { return deltaY; }
    public double getLastDeltaY() { return lastDeltaY; }
    public double getDeltaZ() { return deltaZ; }
    public double getDeltaXZ() { return deltaXZ; }
    public double getLastDeltaXZ() { return lastDeltaXZ; }
    public float getDeltaYaw() { return deltaYaw; }
    public float getLastDeltaYaw() { return lastDeltaYaw; }
    public float getDeltaPitch() { return deltaPitch; }
    public float getLastDeltaPitch() { return lastDeltaPitch; }
    public Location getLocation() { return location; }
    public Location getLastLocation() { return lastLocation; }
    public int getTicksInAir() { return ticksInAir; }
    public int getTicksOnGround() { return ticksOnGround; }
    public int getTicksTracked() { return ticksTracked; }
    public int getTicksSinceAbility() { return ticksSinceAbility; }
    public boolean isOnGround() { return onGround; }
    public boolean isLastGround() { return lastGround; }
    public Location getLastGroundLocation() { return lastGroundLocation; }
    public boolean isInLiquid() { return inLiquid; }
    public boolean wasInLiquid() { return lastInLiquid; }
    public boolean isUnderBlock() { return underBlock; }
    public boolean isOnClimbable() { return onClimbable; }
    public boolean isTeleportTick() { return ticksSinceTeleport <= 3; }
    public long getPing() { return ping; }
    public VelocityQueue getVelocities() { return velocities; }
    public long getLastBlockPlaceTime() { return lastBlockPlaceTime; }
    public void setLastBlockPlaceTime(long time) { this.lastBlockPlaceTime = time; }
    public int getLastBlockPlaceTick() { return lastBlockPlaceTick; }
    public void setLastBlockPlaceTick(int tick) { this.lastBlockPlaceTick = tick; }
    public long getLastSlotSwitchTime() { return lastSlotSwitchTime; }
    public void setLastSlotSwitchTime(long time) { this.lastSlotSwitchTime = time; }
    public Entity getLastTarget() { return lastTarget; }
    public void setLastTarget(Entity target) { this.lastTarget = target; }
    public int getCurrentSlot() { return currentSlot; }
    public void setCurrentSlot(int slot) { this.currentSlot = slot; }
    public int getLastSlot() { return lastSlot; }
    public void setLastSlot(int slot) { this.lastSlot = slot; }
    public int getLastFireworkTick() { return lastFireworkTick; }
    public void setLastFireworkTick(int tick) { this.lastFireworkTick = tick; }
    public int getLastGhostBlockTick() { return lastGhostBlockTick; }
    public void setLastGhostBlockTick(int tick) { this.lastGhostBlockTick = tick; }
    public int getLastGlideTick() { return lastGlideTick; }
    public boolean isExempt() { return exempt; }
    public void setExempt(boolean exempt) { this.exempt = exempt; }
    public boolean isNearVehicle() { return nearVehicle; }
    public void setNearVehicle(boolean nearVehicle) { this.nearVehicle = nearVehicle; }
    public boolean isNearEntity() { return nearEntity; }
    public void setNearEntity(boolean nearEntity) { this.nearEntity = nearEntity; }
    public int getLastVehicleExitTick() { return lastVehicleExitTick; }
    public void setLastVehicleExitTick(int tick) { this.lastVehicleExitTick = tick; }
    public int getLastWebTick() { return lastWebTick; }
    public boolean isInWeb() { return inWeb; }
    public void handleTransaction(short id) { this.velocities.confirm(id); }
}