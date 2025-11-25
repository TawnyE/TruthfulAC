package ret.tawny.truthful.checks.impl.combat.reach;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.compensation.CompensationTracker;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.world.WorldUtils;

@CheckData(order = 'A', type = CheckType.REACH)
@SuppressWarnings("unused")
public final class ReachA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @EventHandler
    public void onAttack(final EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player))
            return;

        final Player player = (Player) event.getDamager();
        final Entity target = event.getEntity();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null || player.getGameMode() == GameMode.CREATIVE)
            return;

        // --- LAG COMPENSATION ---
        Location targetLoc = target.getLocation();

        // Calculate Ping in Ticks (50ms = 1 tick)
        int pingTicks = (int) Math.ceil(data.getPing() / 50.0);
        int currentTick = WorldUtils.getWorldTicks(player.getWorld());

        // Retrieve historical location
        CompensationTracker tracker = Truthful.getInstance().getCompensationTracker();
        if (tracker != null && tracker.getCompensationMap().containsKey(target)) {
            CompensationTracker.CompensatedEntity comp = tracker.getCompensationMap().get(target);
            Location historyLoc = comp.getLocationAt(currentTick, pingTicks);
            if (historyLoc != null) {
                targetLoc = historyLoc;
            }
        }

        // --- 3D HITBOX MATH ---
        // Player eyes
        Location eyeLoc = player.getEyeLocation();

        // Target approximate hitbox center
        // Assume simplified hitbox width approx 0.4 (radius) and height 1.8
        // This calculates the closest point on the target's vertical axis to the player's eyes
        double targetY = targetLoc.getY();
        double playerY = eyeLoc.getY();

        // Clamp Y to the target's height bounds
        double clampedY = Math.max(targetY, Math.min(playerY, targetY + 1.9));

        Location closestTargetPoint = new Location(targetLoc.getWorld(), targetLoc.getX(), clampedY, targetLoc.getZ());

        // Calculate raw distance
        double distance = eyeLoc.distance(closestTargetPoint);

        // Subtract hitbox radius (approx 0.4 for players) + buffer
        double reach = distance - 0.4;

        double maxReach = 3.0; // Vanilla default

        // Dynamic adjustment
        if (data.getDeltaXZ() > 0.2) maxReach += 0.1; // Movement lag buffer
        if (player.isSprinting()) maxReach += 0.1; // Sprint buffer
        maxReach += (data.getPing() * 0.002); // Ping buffer (100ms = 0.2 blocks)

        // Hard cap
        double hardCap = 4.5;
        if (reach > maxReach && reach < 6.0) {
            // Only flag if it exceeds the calculated threshold
            if (reach > hardCap || buffer.increase(player, reach - maxReach) > 6.0) {
                flag(data, String.format("Reach (Compensated): %.2f > %.2f", reach, maxReach));
                buffer.reset(player, 3.0);
                event.setCancelled(true);
            }
        } else {
            buffer.decrease(player, 0.25);
        }
    }
}