package ret.tawny.truthful.checks.impl.raycast;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.checks.impl.world.WorldCheckSupport;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.world.BlockPropertyRegistry;

@CheckData(order = 'A', type = CheckType.RAYCAST)
public final class RaycastA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (WorldCheckSupport.skipBasic(data, player) || Truthful.getInstance().isBedrockPlayer(player)) return;

        Vector3i pos = null;
        Vector hit = null;
        String action = null;

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            WrapperPlayClientPlayerBlockPlacement p = new WrapperPlayClientPlayerBlockPlacement(event);
            pos = p.getBlockPosition();
            Vector3f c = p.getCursorPosition();
            hit = new Vector(pos.getX() + c.x, pos.getY() + c.y, pos.getZ() + c.z);
            action = "place";
        } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging d = new WrapperPlayClientPlayerDigging(event);
            if (d.getAction() == DiggingAction.START_DIGGING) {
                pos = d.getBlockPosition();
                action = "break";
            }
        }

        if (pos == null) return;

        WrappedBlockState state = data.getWorldCache().getBlockState(pos.getX(), pos.getY(), pos.getZ());
        if (state.getType().isAir() || BlockPropertyRegistry.isLiquid(state)) return;

        Vector eye = new Vector(data.getX(), data.getY() + data.getEyeHeight(false, data.isSneaking(), data.isSwimming()), data.getZ());
        Vector target = hit != null ? hit : new Vector(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        double dist = eye.distance(target);

        double maxReach = Truthful.getInstance().getVersionManager().getAdapter().getBlockInteractionRange(player) + 1.0D;
        if (data.isInsideVehicle()) maxReach += 1.0D;

        if (dist > maxReach) {
            if (buffer.increase(player, 2.0D) > 5.0D) {
                flag(data, String.format("%s reach dist=%.2f max=%.2f", action, dist, maxReach));
                buffer.reset(player, 0.0D);
            }
            return;
        }

        if (!hasLineOfSight(data, eye, target, pos)) {
            if (buffer.increase(player, 1.2D) > 12.0D) {
                flag(data, String.format("%s through wall", action));
                buffer.reset(player, 0.0D);
            }
        } else {
            buffer.decrease(player, 0.1D);
        }
    }

    private boolean hasLineOfSight(PlayerData data, Vector from, Vector to, Vector3i target) {
        Vector dir = to.clone().subtract(from);
        double dist = dir.length();
        if (dist < 1.0E-5) return true;
        dir.multiply(1.0D / dist);

        for (double d = 0.5D; d < dist - 0.5D; d += 0.5D) {
            Vector p = from.clone().add(dir.clone().multiply(d));
            int x = floor(p.getX());
            int y = floor(p.getY());
            int z = floor(p.getZ());

            if (x == target.getX() && y == target.getY() && z == target.getZ()) continue;
            WrappedBlockState block = data.getWorldCache().getBlockState(x, y, z);
            if (!block.getType().isAir() && BlockPropertyRegistry.isSolid(block)) {
                String name = block.getType().getName().toLowerCase();
                if (!name.contains("glass") && !name.contains("fence") && !name.contains("wall")) {
                    return false;
                }
            }
        }
        return true;
    }

    private int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}
