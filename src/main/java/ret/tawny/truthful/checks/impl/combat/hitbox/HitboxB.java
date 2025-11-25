package ret.tawny.truthful.checks.impl.combat.hitbox;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;

@CheckData(order = 'B', type = CheckType.HITBOX)
@SuppressWarnings("unused")
public final class HitboxB extends Check {

    private final CheckBuffer buffer = new CheckBuffer(5.0);

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(final EntityDamageByEntityEvent event) {
        if (!isEnabled()) return;
        if (!(event.getDamager() instanceof Player player)) return;

        if (Truthful.getInstance().isBedrockPlayer(player)) return;

        final PlayerData playerData = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (playerData == null) return;

        final Entity target = event.getEntity();

        // 1. Calculate Vectors
        // Vector from Player Eye to Target Center
        Location origin = player.getEyeLocation();
        Location targetLoc = target.getLocation().add(0, target.getHeight() / 2.0, 0); // Aim at center of body

        Vector toTarget = targetLoc.toVector().subtract(origin.toVector());
        Vector lookDir = player.getLocation().getDirection();

        // 2. Angle Calculation
        double angle = lookDir.angle(toTarget);
        double angleDegrees = Math.toDegrees(angle);

        // 3. Thresholds
        // Vanilla FOV is usually 70-90.
        // A generous limit is 60 degrees from cursor center (120 FOV total).
        double limit = 60.0;

        // FIX: Increased Close-Range Exemption from 0.8 to 1.5
        // When backing into a player, your hitboxes intersect. The angle calculation
        // becomes erratic (often 180 degrees). We must trust hits that are this close.
        if (origin.distance(targetLoc) < 1.5) {
            return;
        }

        if (angleDegrees > limit) {
            if (buffer.increase(player, 1.0) > 5.0) {
                flag(playerData, String.format("Hit outside FOV. Angle: %.1f, Limit: %.1f", angleDegrees, limit));
            }
        } else {
            buffer.decrease(player, 0.1);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}