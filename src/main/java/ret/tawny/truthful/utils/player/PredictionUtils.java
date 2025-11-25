package ret.tawny.truthful.utils.player;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class PredictionUtils {

    public static int getJumpBoostAmplifier(Player player) {
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType().equals(PotionEffectType.JUMP_BOOST)) {
                return effect.getAmplifier() + 1;
            }
        }
        return 0;
    }

    public static int getSpeedAmplifier(Player player) {
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType().equals(PotionEffectType.SPEED)) {
                return effect.getAmplifier() + 1;
            }
        }
        return 0;
    }

    public static int getSlownessAmplifier(Player player) {
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType().equals(PotionEffectType.SLOWNESS)) {
                return effect.getAmplifier() + 1;
            }
        }
        return 0;
    }

    /**
     * Calculates the Base Movement Speed attribute of the player.
     * Default is roughly 0.28 for sprinting.
     */
    public static double getBaseSpeed(Player player) {
        // Start with base walk speed (approx 0.22ish in practice for checks)
        // We use 0.23 to be safe.
        double base = 0.23;

        if (player.isSprinting()) {
            base = 0.281; // Vanilla Sprint Speed
        }

        // Apply Speed Potion (20% per level)
        int speed = getSpeedAmplifier(player);
        if (speed > 0) {
            base *= 1.0 + (0.2 * speed);
        }

        // Apply Slowness Potion (15% per level)
        int slow = getSlownessAmplifier(player);
        if (slow > 0) {
            base *= Math.max(0.0, 1.0 - (0.15 * slow));
        }

        return base;
    }

    public static float getBlockFriction(World world, Location location) {
        // Check block under player
        Location down = location.clone().subtract(0, 1, 0);
        Block block = world.getBlockAt(down);

        // If that block is air (e.g. standing on a slab or carpet), check one lower
        if (block.getType() == Material.AIR) {
            block = world.getBlockAt(down.subtract(0, 1, 0));
        }

        String type = block.getType().name();
        // Ice variants
        if (type.contains("ICE")) return 0.98F;
        // Slime
        if (type.contains("SLIME")) return 0.8F;
        // Soul Sand (slower) - We don't lower friction for checks to prevent falses,
        // we only care about 'slippery' blocks that allow speed gain.

        // Standard block friction
        return 0.6F;
    }
}