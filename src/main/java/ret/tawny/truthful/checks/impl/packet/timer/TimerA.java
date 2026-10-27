package ret.tawny.truthful.checks.impl.packet.timer;

import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.ExemptionType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@CheckData(order = 'A', type = CheckType.TIMER)
public final class TimerA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(15.0);
    private static final long MAX_GRACE_BALANCE_NANOS = 250_000_000L;
    // Single-tick jumps bigger than this are lag bursts (catch-up packet
    // clumps), not timer. Timer accrues gradually, bursts arrive all at once.
    private static final long BURST_JUMP_NANOS = 250_000_000L;

    private final Map<UUID, Long> lastAhead = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> burstStreaks = new ConcurrentHashMap<>();

    @Override
    public void handleRelMove(final RelMovePacketWrapper wrapper) {
        if (!wrapper.isPositionUpdate()) return;

        final PlayerData data = wrapper.getPlayerData();
        if (data == null) return;

        // FIXED: Added data.isInsideVehicle() (I may be retrat)
        // Vehicles (especially boats on ice) send extra VEHICLE_MOVE packets to correct collisions,
        // which completely inflates the packet count and false flags Timer.
        if (data.isInsideVehicle() || data.isMovementExempt()) {
            buffer.decrease(wrapper.getPlayer(), 0.5D);
            clampAheadBalance(data);
            return;
        }

        if (data.isTeleportTick() || data.isServerFrozen() || data.isExempt(ExemptionType.RIPTIDE)) {
            buffer.decrease(wrapper.getPlayer(), 0.5D);
            clampAheadBalance(data);
            burstStreaks.put(wrapper.getPlayer().getUniqueId(), 0);
            lastAhead.put(wrapper.getPlayer().getUniqueId(), data.getTimerBalanceRealTime() - System.nanoTime());
            return;
        }

        double tps = ret.tawny.truthful.Truthful.getInstance().getTps();
        if (tps < 19.0D || data.shouldSkipChecks()) {
            buffer.decrease(wrapper.getPlayer(), 1.0D);
            clampAheadBalance(data);
            lastAhead.put(wrapper.getPlayer().getUniqueId(), data.getTimerBalanceRealTime() - System.nanoTime());
            return;
        }

        long now = System.nanoTime();
        long aheadNanos = data.getTimerBalanceRealTime() - now;

        // Lag burst forgiveness, replaces the old blanket ping>250 exemption
        // that turned the whole check off for anyone laggy. High ping delays
        // packets (shrinks balance), it doesn't fast-forward them - but when
        // the clump arrives it spikes in one tick. Forgive the spike, keep
        // watching the trend.
        UUID id = wrapper.getPlayer().getUniqueId();
        long prevAhead = lastAhead.getOrDefault(id, aheadNanos);
        lastAhead.put(id, aheadNanos);
        if (aheadNanos - prevAhead > BURST_JUMP_NANOS) {
            // Forgiveness budget: laggy players clump legitimately, but a client
            // and a clump-hiding timer are indistinguishable forever. Cap how
            // many bursts we absorb in a row before we stop believing them.
            int bursts = burstStreaks.merge(id, 1, Integer::sum);
            if (bursts <= 25) {
                clampAheadBalance(data);
                buffer.decrease(wrapper.getPlayer(), 1.0D);
                return;
            }
            // Beyond the budget: judge the spike like everything else.
        } else if (aheadNanos < prevAhead) {
            burstStreaks.put(id, 0);
        }

        // Grim-model balance: once the aheadness exceeds the drift (150ms), the
        // flag comes fast — transaction-anchored clock means ping is irrelevant.
        if (aheadNanos > 250_000_000L) {
            long aheadMs = aheadNanos / 1_000_000L;
            double severity = aheadMs > 600L ? 2.0D : 1.0D;

            if (buffer.increase(wrapper.getPlayer(), severity) > 5.0D) {
                flag(data, String.format("Timer speed balance=+%dms", aheadMs));
                data.addTimerBalance(-100_000_000L);
                buffer.reset(wrapper.getPlayer(), 2.0D);
            }
        } else {
            buffer.decrease(wrapper.getPlayer(), 0.15D);
        }
    }

    private void clampAheadBalance(PlayerData data) {
        long now = System.nanoTime();
        long ahead = data.getTimerBalanceRealTime() - now;
        if (ahead > MAX_GRACE_BALANCE_NANOS) {
            data.addTimerBalance(MAX_GRACE_BALANCE_NANOS - ahead);
        }
    }

    @Override
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        lastAhead.remove(event.getPlayer().getUniqueId());
        burstStreaks.remove(event.getPlayer().getUniqueId());
    }
}
