package ret.tawny.truthful.data.handler;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.config.api.Configuration;
import ret.tawny.truthful.data.PlayerData;

public final class SetbackHandler {

    private final PlayerData data;
    private final Player player;

    private Location lastSafeLocation;
    private boolean awaitingTeleport = false;
    private short pendingTransactionId = -1;
    private long lastSetbackTime = 0;
    private int consecutiveSetbacks = 0;

    public SetbackHandler(PlayerData data) {
        this.data = data;
        this.player = data.getPlayer();
        this.lastSafeLocation = player.getLocation();
    }

    public void updateSafeLocation(double x, double y, double z, float yaw, float pitch) {
        if (awaitingTeleport) return;
        if (data.hasVelocity() || data.isTeleportPending()) return;
        if (!data.isServerGround()) return;
        if (data.getTicksTracked() - data.getLastFlagTick() <= 12) return;

        // Primitives in, Location built only when the update is actually
        // accepted - the old call allocated a fresh Location per movement
        // packet just to throw most of them away. World comes from the
        // server-trusted cache (this runs on the packet thread).
        this.lastSafeLocation = new Location(data.getWorld(), x, y, z, yaw, pitch);
        this.consecutiveSetbacks = 0;
    }

    public Location getLastSafeLocation() {
        return lastSafeLocation;
    }

    private Location resolveSetbackTarget() {
        Configuration config = Truthful.getInstance().getConfiguration();
        double snapDistance = config.getLagbackGroundSnapDistance();

        double curX = data.getX();
        double curY = data.getY();
        double curZ = data.getZ();
        Location currentPos = new Location(player.getWorld(), curX, curY, curZ, data.getYaw(), data.getPitch());

        if (data.isServerGround()) {
            return currentPos;
        }

        Location groundSnap = findGroundBelow(curX, curY, curZ);
        if (groundSnap != null && currentPos.distanceSquared(groundSnap) <= snapDistance * snapDistance) {
            return groundSnap;
        }

        Location safe = lastSafeLocation;
        if (safe != null && safe.getWorld().equals(player.getWorld())
                && currentPos.distanceSquared(safe) <= snapDistance * snapDistance) {
            return safe;
        }

        return groundSnap != null ? groundSnap : currentPos;
    }

    private Location findGroundBelow(double x, double y, double z) {
        int startY = (int) Math.floor(y);
        int minY = data.getWorldMinHeight();

        for (int dy = 0; dy <= 10; dy++) {
            int checkY = startY - dy;
            if (checkY < minY) break;

            if (ret.tawny.truthful.utils.world.BlockPropertyRegistry.isSolid(
                    data.getWorldCache().getBlockState((int) Math.floor(x), checkY, (int) Math.floor(z)))) {
                return new Location(player.getWorld(), x, checkY + 1.0, z, data.getYaw(), data.getPitch());
            }
        }
        return null;
    }

    public boolean setback() {
        long now = System.currentTimeMillis();
        if (now - lastSetbackTime < 80L) return false;

        this.awaitingTeleport = true;
        this.lastSetbackTime = now;
        this.consecutiveSetbacks++;

        Location target = resolveSetbackTarget();

        data.getPositionTracker().reset(target.getX(), target.getY(), target.getZ(), target.getYaw(), target.getPitch());

        WrapperPlayServerPlayerPositionAndLook tpPacket = new WrapperPlayServerPlayerPositionAndLook(
                target.getX(), target.getY(), target.getZ(),
                target.getYaw(), target.getPitch(),
                (byte) 0, data.getNextTransactionId(), false
        );
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, tpPacket);

        // Paired ping confirm for the setback. Skipped when the transaction
        // ping kill-switch is off; the 150ms movement-block timeout in
        // shouldBlockMovement() covers the missing ack.
        if (Truthful.getInstance().getConfiguration().isTransactionPingEnabled()) {
            short uid = data.getNextTransactionId();
            this.pendingTransactionId = uid;
            data.recordTransactionSent(uid);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerPing(uid));
        }
        return true;
    }

    public boolean onTransaction(short id) {
        if (awaitingTeleport && id == pendingTransactionId) {
            awaitingTeleport = false;
            return true;
        }
        return false;
    }

    public boolean shouldBlockMovement() {
        if (!awaitingTeleport) return false;
        if (System.currentTimeMillis() - lastSetbackTime > 150L) {
            awaitingTeleport = false;
            return false;
        }
        return awaitingTeleport;
    }
}