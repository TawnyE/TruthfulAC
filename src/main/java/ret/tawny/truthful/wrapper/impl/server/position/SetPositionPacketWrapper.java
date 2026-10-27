package ret.tawny.truthful.wrapper.impl.server.position;

import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.util.Vector3d;
import org.bukkit.entity.Player;
import ret.tawny.truthful.wrapper.api.PacketWrapper;

public final class SetPositionPacketWrapper extends PacketWrapper {
    private final double x, y, z;
    private final float yaw, pitch;
    private final int teleportId;

    public SetPositionPacketWrapper(Object wrapper, Player player, PacketType.Play.Server type,
                                    double baseX, double baseY, double baseZ) {
        super(wrapper, player, type);

        if (wrapper instanceof WrapperPlayServerPlayerPositionAndLook) {
            WrapperPlayServerPlayerPositionAndLook posLook = (WrapperPlayServerPlayerPositionAndLook) wrapper;
            Vector3d position = posLook.getPosition();
            // Relative components (RA running, vehicles, some plugin teleports) are
            // RAW deltas, not absolute coords. Resolve them against the last known
            // client position or the teleport queue gets garbage destinations.
            int relativeMask = posLook.getRelativeMask();
            boolean relX = (relativeMask & 0x01) != 0;
            boolean relY = (relativeMask & 0x02) != 0;
            boolean relZ = (relativeMask & 0x04) != 0;
            this.x = relX ? baseX + position.x : position.x;
            this.y = relY ? baseY + position.y : position.y;
            this.z = relZ ? baseZ + position.z : position.z;
            this.yaw = posLook.getYaw();
            this.pitch = posLook.getPitch();
            // Teleport ID is only available in 1.9+, default to -1 for older versions
            this.teleportId = posLook.getTeleportId();
        } else {
            this.x = 0;
            this.y = 0;
            this.z = 0;
            this.yaw = 0;
            this.pitch = 0;
            this.teleportId = -1;
        }
    }

    /**
     * @return X Coordinate
     */
    public double getX() {
        return x;
    }

    /**
     * @return Y Coordinate
     */
    public double getY() {
        return y;
    }

    /**
     * @return Z Coordinate
     */
    public double getZ() {
        return z;
    }

    /**
     * @return Yaw Rotation
     */
    public float getYaw() {
        return yaw;
    }

    /**
     * @return Pitch Rotation
     */
    public float getPitch() {
        return pitch;
    }

    /**
     * @return Teleport ID
     */
    public int getTeleportId() {
        return teleportId;
    }

    @Override
    public String toString() {
        return String.format("SetPositionPacket[X=%.2f, Y=%.2f, Z=%.2f, Yaw=%.2f, Pitch=%.2f]",
                this.x, this.y, this.z, this.yaw, this.pitch);
    }
}