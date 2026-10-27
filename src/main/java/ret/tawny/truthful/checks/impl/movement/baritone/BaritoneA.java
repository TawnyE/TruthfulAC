package ret.tawny.truthful.checks.impl.movement.baritone;

import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@CheckData(order = 'A', type = CheckType.BARITONE)
public final class BaritoneA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private final Map<UUID, SampleData> samples = new ConcurrentHashMap<>();

    @Override
    public void handleRelMove(final RelMovePacketWrapper wrapper) {
        if (!wrapper.isRotationUpdate()) return;

        final PlayerData data = wrapper.getPlayerData();
        if (data == null || data.isRotationExempt() || data.isMovementExempt()) return;

        final float dyaw = Math.abs(data.getDeltaYaw());

        if (dyaw < 5.0F) return;

        final float normalized = ((data.getYaw() % 360.0F) + 360.0F) % 360.0F;
        final float rem45 = normalized % 45.0F;

        final boolean snap = rem45 < 0.5F || rem45 > 44.5F;

        final SampleData sample = samples.computeIfAbsent(wrapper.getPlayer().getUniqueId(), $ -> new SampleData());
        sample.add(snap);

        if (sample.total >= 40) {
            double density = sample.snapDensity();
            if (density > 0.85D && buffer.increase(wrapper.getPlayer(), density > 0.90D ? 1.0D : 0.5D) > 8.0D) {
                data.lowerBaritoneTrust(5.0D + ((density - 0.85D) * 20.0D));
                flag(data, String.format("Rotational snap density=%.2f trust=%.1f", density, data.getBaritoneTrust()));
                buffer.reset(wrapper.getPlayer(), 2.0D);
            } else {
                buffer.decrease(wrapper.getPlayer(), 0.25D);
            }
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        samples.remove(event.getPlayer().getUniqueId());
    }

    private static final class SampleData {
        private int total;
        private int snaps;

        private void add(boolean snap) {
            total = Math.min(total + 1, 100);
            if (snap) snaps = Math.min(snaps + 1, 100);
            else snaps = Math.max(0, snaps - 1);
        }

        private double snapDensity() {
            return total == 0 ? 0.0D : (double) snaps / (double) total;
        }
    }
}