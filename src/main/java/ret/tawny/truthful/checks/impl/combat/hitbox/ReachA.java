package ret.tawny.truthful.checks.impl.combat.hitbox;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.compensation.CompensationTracker;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.world.WorldUtils; // Ensure this import exists

@CheckData(order = 'A', type = CheckType.HITBOX)
@SuppressWarnings("unused")
public final class ReachA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(5.0);

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(final EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (!(event.getDamager() instanceof Player player)) return;

        if (Truthful.getInstance().isBedrockPlayer(player)) return;

        final PlayerData playerData = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (playerData == null) return;

        final Entity target = event.getEntity();

        // --- FIX: LAG COMPENSATION ---
        Location targetLoc = target.getLocation();

        // Calculate Ping in Ticks
        int pingTicks = (int) Math.ceil(playerData.getPing() / 50.0);
        int currentTick = WorldUtils.getWorldTicks(player.getWorld()); // Use WorldUtils wrapper

        // Retrieve historical location
        CompensationTracker tracker = Truthful.getInstance().getCompensationTracker();
        if (tracker != null && tracker.getCompensationMap().containsKey(target)) {
            CompensationTracker.CompensatedEntity comp = tracker.getCompensationMap().get(target);
            Location historyLoc = comp.getLocationAt(currentTick, pingTicks);
            if (historyLoc != null) {
                targetLoc = historyLoc;
            }
        }

        // Calculate Distance to Compensated Location
        final double deltaX = playerData.getX() - targetLoc.getX();
        final double deltaZ = playerData.getZ() - targetLoc.getZ();
        final double horizontal = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        double maxReach = 3.05D;

        if (player.getGameMode().name().contains("CREATIVE")) maxReach = 4.5;

        // Sprinting reach expansion
        if (player.isSprinting()) maxReach += 0.2D;

        // Static buffer for latency jitter
        maxReach += 0.25;

        if (horizontal > maxReach) {
            if (buffer.increase(player, horizontal - maxReach) > 5.0) {
                flag(playerData, String.format("Reach %.2f > %.2f (Ping: %d)", horizontal, maxReach, playerData.getPing()));
                buffer.reset(player, 3.0);
                event.setCancelled(true); // Cancel hit if confirmed reach
            }
        } else {
            buffer.decrease(player, 0.5);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}