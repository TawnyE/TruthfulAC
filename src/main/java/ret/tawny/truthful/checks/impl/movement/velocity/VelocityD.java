package ret.tawny.truthful.checks.impl.movement.velocity;

import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.sync.VelocityQueue;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'D', type = CheckType.VELOCITY)
public final class VelocityD extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0D);

    @Override
    public void handleRelMove(final RelMovePacketWrapper wrapper) {
        if (!wrapper.isPositionUpdate()) return;
        final PlayerData data = wrapper.getPlayerData();
        if (data == null) return;

        // Transaction probes are what acknowledge velocity entries. With them
        // disabled nothing ever acks and every entry reads as "withheld", so
        // this check only runs while the probe system is on (the old behavior
        // flooded flags on ordinary movement with it off).
        if (!ret.tawny.truthful.Truthful.getInstance().getConfiguration().isTransactionPingEnabled()) {
            buffer.decrease(data.getPlayer(), 0.25D);
            return;
        }

        if (data.isServerFrozen() || data.isTeleportTick() || data.isMovementExempt()) {
            buffer.decrease(data.getPlayer(), 0.25D);
            return;
        }

        VelocityQueue queue = data.getVelocities();
        if (queue.isEmpty()) return;
        if (data.isInLiquid() || data.isInWeb() || data.isOnClimbable()
                || data.isNearVehicle() || data.isNearEntity() || data.isUnderBlock()) {
            buffer.decrease(data.getPlayer(), 0.25D);
            return;
        }

        double deltaXZ = data.getDeltaXZ();
        boolean flagged = false;
        double severity = 0;
        String flagReason = null;

        for (VelocityQueue.VelocityEntry entry : queue) {
            if (!entry.isAcked() && !entry.isExplosion()) {
                int ticksPending = entry.getAgeTick();
                int allowedPendingTicks = 6 + (int) Math.ceil(Math.min(300L, data.getPing()) / 50.0D);
                // Flag moving players who withhold velocity transaction ACKs
                if (ticksPending > allowedPendingTicks && deltaXZ > 0.15D) {
                    flagged = true;
                    severity += (deltaXZ - 0.15D) * 10.0D + 0.5D;
                    flagReason = String.format("Moving while withholding velocity ACKs (pendingTicks=%d allowed=%d dist=%.4f)",
                            ticksPending, allowedPendingTicks, deltaXZ);
                }
            }
        }

        if (flagged) {
            if (buffer.increase(data.getPlayer(), severity) > 8.0D) {
                flag(data, flagReason, Math.max(1.0D, severity));
                buffer.reset(data.getPlayer(), 2.0D);
            }
        } else {
            buffer.decrease(data.getPlayer(), 0.2D);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}
