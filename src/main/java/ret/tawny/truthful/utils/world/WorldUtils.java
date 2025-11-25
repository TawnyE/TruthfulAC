package ret.tawny.truthful.utils.world;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import java.util.EnumSet;
import java.util.Set;

public final class WorldUtils {
    private WorldUtils() {
    }

    private static final Set<Material> LOW_FRICTION = EnumSet.noneOf(Material.class);
    private static final Set<Material> PARTIAL_BLOCKS = EnumSet.noneOf(Material.class);
    private static final Set<Material> WEBS = EnumSet.noneOf(Material.class);

    static {
        for (Material mat : Material.values()) {
            String name = mat.name();
            if (name.contains("ICE") || name.contains("SLIME")) {
                LOW_FRICTION.add(mat);
            }
            if (name.contains("CARPET") || name.contains("TRAPDOOR") ||
                    name.contains("SLAB") || name.contains("STAIR") ||
                    name.contains("LILY") || name.contains("SNOW") ||
                    name.contains("SHULKER") || name.contains("BERRY")) {
                PARTIAL_BLOCKS.add(mat);
            }
            if (name.equals("WEB") || name.equals("COBWEB")) {
                WEBS.add(mat);
            }
        }
    }

    public static boolean safeGround(final Player player) {
        final Location loc = player.getLocation();

        // 1. Check Solid Block Below (Standard)
        if (checkBlock(getLocationBlock(loc.clone().add(0, -0.5, 0)))) return true;

        // 2. Check Thin Blocks at Feet Level (Lily Pads, Carpets, Snow)
        // These blocks sit at the same Y level as the player's feet.
        // The -0.5 check often misses them (hitting the water/block below).
        if (checkThinBlock(getLocationBlock(loc))) return true;

        // 3. Check Edges (Expansion)
        double expansion = 0.305;
        for (double x = -expansion; x <= expansion; x += expansion) {
            for (double z = -expansion; z <= expansion; z += expansion) {
                if (x == 0 && z == 0) continue;

                Location offset = loc.clone().add(x, 0, z);
                // Check below
                if (checkBlock(getLocationBlock(offset.clone().add(0, -0.5, 0)))) return true;
                // Check at feet
                if (checkThinBlock(getLocationBlock(offset))) return true;
            }
        }
        return false;
    }

    // Helper to detect thin blocks that players stand ON, not IN.
    private static boolean checkThinBlock(Block block) {
        if (block == null) return false;
        String name = block.getType().name();
        // LILY_PAD, CARPET, SNOW layers, etc.
        return name.contains("LILY") || name.contains("CARPET") || name.contains("SNOW") || name.contains("SLAB");
    }

    public static boolean isInWeb(Player player) {
        Location loc = player.getLocation();

        // 1. Below feet
        if (checkWeb(loc.clone().subtract(0, 0.5, 0))) return true;

        // 2. Feet Level (Corners)
        double r = 0.31;
        if (checkWeb(loc)) return true;
        if (checkWeb(loc.clone().add(r, 0, r))) return true;
        if (checkWeb(loc.clone().add(r, 0, -r))) return true;
        if (checkWeb(loc.clone().add(-r, 0, r))) return true;
        if (checkWeb(loc.clone().add(-r, 0, -r))) return true;

        // 3. Head/Torso Level
        Location head = loc.clone().add(0, 1.0, 0);
        if (checkWeb(head)) return true;
        if (checkWeb(head.clone().add(r, 0, r))) return true;
        if (checkWeb(head.clone().add(r, 0, -r))) return true;
        if (checkWeb(head.clone().add(-r, 0, r))) return true;
        if (checkWeb(head.clone().add(-r, 0, -r))) return true;

        return false;
    }

    private static boolean checkWeb(Location loc) {
        Block block = getLocationBlock(loc);
        return block != null && WEBS.contains(block.getType());
    }

    public static int getWorldTicks(World world) {
        return (int) world.getFullTime();
    }

    public static float getSlippinessMultiplier(Player player) {
        if (player.isFlying()) return 0.6f;

        Block block = getLocationBlock(player.getLocation().clone().subtract(0, 0.5, 0));
        if (block == null) return 0.6f;

        String type = block.getType().name();
        if (type.contains("ICE")) return 0.98f;
        if (type.contains("SLIME")) return 0.8f;
        return 0.6f;
    }

    public static boolean hasLowFrictionBelow(Player player) {
        Block block = getLocationBlock(player.getLocation().clone().subtract(0, 0.5, 0));
        if (block == null) return false;
        String type = block.getType().name();
        return type.contains("ICE") || type.contains("SLIME");
    }

    public static boolean checkBlock(Block block) {
        return block != null && block.getType().isSolid();
    }

    public static Block getLocationBlock(Location loc) {
        return loc.getWorld().getBlockAt(loc);
    }

    public static boolean isLiquid(Player player) {
        Block block = getLocationBlock(player.getLocation());
        return block != null && (block.getType().name().contains("WATER") || block.getType().name().contains("LAVA"));
    }

    public static boolean hasClimbableNearby(Player player) {
        Block block = getLocationBlock(player.getLocation());
        if (block == null) return false;
        String name = block.getType().name();
        return name.contains("LADDER") || name.contains("VINE") || name.contains("SCAFFOLDING");
    }

    public static boolean isSolid(Block block) {
        return block != null && block.getType().isSolid();
    }

    public static boolean nearBlock(Player player) {
        Location loc = player.getLocation();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                Block block = loc.clone().add(x, 0, z).getBlock();
                if (block != null && block.getType().isSolid())
                    return true;
                if (block != null && block.getType().name().contains("LILY"))
                    return true;
            }
        }
        return false;
    }
}