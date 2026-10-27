package ret.tawny.truthful.checks.impl.world.phase;

import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.checks.impl.world.WorldCheckSupport;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.world.BlockPropertyRegistry;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

 // Alt F for this mf

@CheckData(order = 'A', type = CheckType.PHASE)
public final class PhaseA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(6.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper wrapper) {
        if (!wrapper.isPositionUpdate()) return;

        Player player = wrapper.getPlayer();
        PlayerData data = wrapper.getPlayerData();
        if (WorldCheckSupport.skipBasic(data, player)) return;

        if (data.getTicksSinceTeleport() < 20 || data.getTicksTracked() < 40) {
            buffer.decrease(player, 0.4D);
            return;
        }

        if (Math.abs(data.getDeltaY()) < 0.06D && data.getDeltaXZ() < 0.08D) return;

        double minX = data.getX() - 0.3D, maxX = data.getX() + 0.3D;
        double minY = data.getY(), maxY = data.getY() + 1.8D;
        double minZ = data.getZ() - 0.3D, maxZ = data.getZ() + 0.3D;

        boolean collision = false;
        for (int x = floor(minX); x <= floor(maxX); x++) {
            for (int y = floor(minY); y <= floor(maxY); y++) {
                for (int z = floor(minZ); z <= floor(maxZ); z++) {
                    WrappedBlockState state = data.getWorldCache().getBlockState(x, y, z);
                    if (!state.getType().isAir() && BlockPropertyRegistry.isSolid(state) && isHardCollision(state)) {
                        collision = true;
                        break;
                    }
                }
                if (collision) break;
            }
            if (collision) break;
        }

        if (collision) {
            if (buffer.increase(player, 1.0D) > 3.0D) {
                flag(data, "Phase inside solid block");
                buffer.reset(player, 1.0D);
            }
        } else buffer.decrease(player, 0.2D);
    }

    private boolean isHardCollision(WrappedBlockState state) {
        String n = state.getType().getName().toLowerCase();
        return !(n.contains("water") || n.contains("lava") || n.contains("web") || n.contains("vine") || n.contains("ladder") || n.contains("scaffolding") || n.contains("carpet"));
    }

    private int floor(double v) { int i = (int) v; return v < i ? i - 1 : i; }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}
