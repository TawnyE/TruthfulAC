package ret.tawny.truthful.checks.impl.movement.spoof;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.world.WorldUtils;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@CheckData(order = 'F', type = CheckType.SPOOF)
public final class GroundSpoofF extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    // Probe throttling: at most one in-flight main-thread verification per
    // player, with a cooldown between probes. The old code scheduled a region
    // task for every failing packet (20/s per player, allocation churn,
    // verdicts on stale state, scheduler flood under a persistent false state).
    private final Set<UUID> probeInFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> lastProbeTick = new ConcurrentHashMap<>();

    @Override
    public void handleRelMove(final RelMovePacketWrapper wrapper) {
        if (!wrapper.isPositionUpdate()) return;

        final Player player = wrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null || data.isServerFrozen() || data.isTeleportTick()) return;

        if (!data.isChunkLoaded()) {
            buffer.decrease(player, 0.5);
            return;
        }

        if (data.isAllowFlight() || data.isInsideVehicle() || data.isGliding()) return;
        if (data.getAirTicks() < 4) return;

        if (wrapper.isGround()) {

            // Collider-based support probe (sneak-edge tolerant; strict
            // footprint tests called legit edge-hangers airborne).
            boolean physicalGround = WorldUtils.hasSupportBelow(data, 0.10D);

            // Shape-boundary fallback (same rule as PositionTracker): beds
            // and cache-missed partial blocks rest feet at 1/16 grid lines
            // the probe can't see. Bukkit's isSolid() is false for beds,
            // slabs and pots - the live check below uses non-air instead.
            if (!physicalGround && WorldUtils.isAtShapeBoundary(data)) {
                physicalGround = true;
            }

            if (!physicalGround && (data.isNearVehicle() || data.isNearEntity())) {
                physicalGround = true;
            }

            if (!physicalGround) {

                int ticksSincePlace = data.getTicksTracked() - data.getLastBlockPlaceTick();
                int ticksSinceGhost = data.getTicksTracked() - data.getLastGhostBlockTick();

                if (ticksSincePlace < 10 || ticksSinceGhost < 10) {
                    buffer.decrease(player, 0.5);
                    return;
                }

                if (data.getDeltaY() < -0.07 && WorldUtils.isGroundBelow(data, 1.2)) {
                    buffer.decrease(player, 0.2);
                    return;
                }

                // Main-thread block verification for Tile Entities (Spawners, Chests).
                // Throttled (see fields): one probe at a time per player, 5-tick
                // cooldown, and the world reference comes from the cached
                // server-trusted world instead of a packet-thread Bukkit call.
                final UUID uuid = player.getUniqueId();
                if (!probeInFlight.add(uuid)) return;
                final int lastProbe = lastProbeTick.getOrDefault(uuid, -100);
                if (data.getTicksTracked() - lastProbe < 5) {
                    probeInFlight.remove(uuid);
                    return;
                }
                lastProbeTick.put(uuid, data.getTicksTracked());

                Location loc = new Location(data.getWorld(), wrapper.getX(), wrapper.getY() - 0.2, wrapper.getZ());

                Truthful.getInstance().getServerScheduler().runRegion(player, () -> {
                    try {
                        if (!player.isOnline()) return;
                        org.bukkit.block.Block block = loc.getBlock();

                        // Non-air, not isSolid(): Bukkit reports beds, slabs and
                        // pots as non-solid even though players stand on them.
                        if (block.getType() != org.bukkit.Material.AIR) {
                            return;
                        }

                        if (buffer.increase(player, 0.85) > 5.0) {
                            flag(data, "Physical Ground Spoof (No Collision) " + ret.tawny.truthful.debug.DebugContext.build(data) + ret.tawny.truthful.debug.DebugContext.blocks(data));
                            buffer.reset(player, 2.0);
                        }
                    } finally {
                        probeInFlight.remove(uuid);
                    }
                });

            } else {
                buffer.decrease(player, 0.5);
            }
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        buffer.remove(event.getPlayer());
        probeInFlight.remove(uuid);
        lastProbeTick.remove(uuid);
    }
}