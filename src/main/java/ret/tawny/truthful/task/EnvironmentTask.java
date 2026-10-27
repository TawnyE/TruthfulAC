package ret.tawny.truthful.task;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;

import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.data.PlayerData;

/**
 * Scheduled task to handle heavy environment checks.
 * Optimized to run more frequently (5 ticks) for accurate proximity detection.
 */
public class EnvironmentTask implements Runnable {

    private int playerIndex = 0;

    @Override
    public void run() {
        try {
        // The scan feeds entity-support/push geometry to the world model and
        // the entity checks; with neither consumed (e.g. a clicks-only
        // profile) it is pure main-thread cost, so skip it entirely.
        ret.tawny.truthful.checks.registry.CheckRegistry registry =
                Truthful.getInstance().getCheckManager();
        if (registry != null && !registry.isEnvironmentScanConsumed()) return;

        Player[] players = Bukkit.getOnlinePlayers().toArray(new Player[0]);
        if (players.length == 0) return;

        int processed = 0;
        int limit = Math.min(players.length, 5);

        while (processed < limit) {
            if (playerIndex >= players.length) {
                playerIndex = 0;
            }

            Player player = players[playerIndex];
            playerIndex++;
            processed++;

            Truthful.getInstance().getServerScheduler().runRegion(player, () -> {
                PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
                if (data == null) return;

                if (Truthful.getInstance().isBedrockPlayer(player)) return;

                boolean vehicleNearby = false;
                boolean entityNearby = false;

                // Honest entity geometry for ground support + push modelling:
                // the highest entity collision-box top that can carry the feet,
                // plus a count of colliding entities for bounded push allowance.
                double supportTopY = Double.NaN;
                int collidingCount = 0;
                double feetY = player.getLocation().getY();

                // Radius 1.25 covers immediate surroundings adequately.
                // Using a smaller radius improves performance while maintaining detection.
                for (Entity entity : player.getNearbyEntities(1.25, 1.25, 1.25)) {

                    // Skip self
                    if (entity.getEntityId() == player.getEntityId()) continue;

                    // Vehicle check
                    if (entity instanceof Vehicle) {
                        vehicleNearby = true;
                    }
                    // General Entity / Boat check (Boats are sometimes not instanceof Vehicle in older APIs or weird forks)
                    else if (entity.getType().name().contains("BOAT") || entity.getType().name().contains("MINECART")) {
                        vehicleNearby = true;
                    }

                    // Living entity check (for phasing/crowding)
                    if (entity.getType().isAlive()) {
                        entityNearby = true;
                    }

                    // Geometry: entity box top at or below the feet counts as
                    // standable support; entities overlapping the body count
                    // toward the push model.
                    org.bukkit.util.BoundingBox box = entity.getBoundingBox();
                    if (box != null) {
                        if (box.getMaxY() <= feetY + 0.05D) {
                            if (Double.isNaN(supportTopY) || box.getMaxY() > supportTopY) {
                                supportTopY = box.getMaxY();
                            }
                        } else if (box.getMaxY() > feetY) {
                            collidingCount++;
                        }
                    }

                    if (vehicleNearby && entityNearby && collidingCount >= 4) {
                        break;
                    }
                }

                // Ensure vehicle state is true if they are actually riding one
                if (player.isInsideVehicle()) {
                    vehicleNearby = true;
                }

                data.setNearVehicle(vehicleNearby);
                data.setNearEntity(entityNearby);
                data.getPositionTracker().setNearEntitySupportY(supportTopY);
                data.getPositionTracker().setNearEntityCount(collidingCount);
            });
        }
        } catch (Throwable t) {
            org.bukkit.Bukkit.getLogger().warning("[Truthful] Environment scan error: " + t.getMessage());
        }
    }
}