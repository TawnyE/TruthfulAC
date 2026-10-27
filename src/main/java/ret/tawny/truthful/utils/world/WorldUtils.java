package ret.tawny.truthful.utils.world;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.data.world.CompensatedWorld;


public final class WorldUtils {
    private WorldUtils() {
    }

    public static boolean safeGround(final Location loc, final PlayerData data) {
        if (data == null) return true;
        return (loc == null) ? safeGround(data.getX(), data.getY(), data.getZ(), data)
                : safeGround(loc.getX(), loc.getY(), loc.getZ(), data);
    }

    public static boolean safeGround(double x, double y, double z, final PlayerData data) {
        if (data == null) return true;
        if (safeGround(x, y, z, data.getWorldCache())) return true;
        // Honest entity support: vanilla lets players stand on boats and (briefly)
        // on other entities. Instead of fabricating ground for the entire 1.25
        // block scan radius, the environment task records the highest entity
        // box top under the player; ground exists only if the feet actually
        // rest within a small band of it.
        double supportY = data.getPositionTracker().getNearEntitySupportY();
        if (!Double.isNaN(supportY) && y >= supportY - 0.15D && y <= supportY + 0.35D) {
            return true;
        }
        return false;
    }

    public static boolean isGroundBelow(final PlayerData data, double distance) {
        CompensatedWorld cache = data.getWorldCache();
        double x = data.getX();
        double y = data.getY();
        double z = data.getZ();

        final double px0 = x - 0.3, px1 = x + 0.3;
        final double pz0 = z - 0.3, pz1 = z + 0.3;
        final int cx = (int) Math.floor(x), cz = (int) Math.floor(z);

        for (double dy = 0.0; dy <= distance; dy += 0.2) {
            int by = (int) Math.floor(y - dy);
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    int id = cache.getBlockId(cx + ox, by, cz + oz);
                    if (id == 0) continue;
                    for (CollisionShapes.Box box : CollisionShapes.of(id)) {
                        if (box.supportsAt(cx + ox, by, cz + oz, px0, pz0, px1, pz1, y - dy)) return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean safeGround(final Location loc, final CompensatedWorld cache) {
        if (loc == null) return true;
        return safeGround(loc.getX(), loc.getY(), loc.getZ(), cache);
    }

    public static boolean safeGround(double x, double y, double z, final CompensatedWorld cache) {
        return isSupported(cache, x, y, z);
    }

    /**
     * True ground support: player AABB (0.6 wide) versus the actual collision
     * boxes of the blocks under/around the feet â€” partial shapes included.
     * Replaces the old "is this cell a solid block" test, which lied for pots,
     * chains, anvils, composters, hoppers, end rods, bells, open shulkers, ...
     */
    public static boolean isSupported(final CompensatedWorld cache, double x, double y, double z) {
        final double px0 = x - 0.3, px1 = x + 0.3;
        final double pz0 = z - 0.3, pz1 = z + 0.3;

        final int bx0 = (int) Math.floor(px0), bx1 = (int) Math.floor(px1);
        final int bz0 = (int) Math.floor(pz0), bz1 = (int) Math.floor(pz1);
        final int by = (int) Math.floor(y);

        for (int cx = bx0; cx <= bx1; cx++) {
            for (int cz = bz0; cz <= bz1; cz++) {
                for (int cy = by; cy >= by - 1; cy--) {
                    int id = cache.getBlockId(cx, cy, cz);
                    if (id == 0) continue;
                    for (CollisionShapes.Box box : CollisionShapes.of(id)) {
                        if (box.supportsAt(cx, cy, cz, px0, pz0, px1, pz1, y)) return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Collision-derived ground truth: a downward probe from the arrived
     * feet position truncated by real collision boxes means genuine support.
     * This is how Grim determines onGround (verticalCollision + moving down)
     * - distance windows around blocks lie during jumps, bounces and slope
     * runs; the collider cannot.
     */
    public static boolean hasSupportBelow(PlayerData data, double probe) {
        double px = data.getX(), py = data.getY(), pz = data.getZ();
        // The footprint is expanded slightly: vanilla's sneak-edge clamp lets
        // players hang on a block with an arbitrarily tiny box overlap (often
        // under a float ulp), so strict overlap tests call legit edge-hangers
        // airborne and farm false ground-spoof flags.
        double width = Math.max(0.1D, data.getPlayerWidth()) + 0.10D;
        double height = Math.max(0.1D, data.getPlayerHeight());
        Collider.Session session = Collider.prepare(data.getWorldCache(), px, py, pz, width, height, 0.0D, probe, 0.0D);
        Collider.Result result = new Collider.Result();
        session.resolve(px, py, pz, width, height, 0.0D, -probe, 0.0D, false, 0.0D, result);
        if (result.collidedDown) return true;

        // Flush-landing tolerance: standing on 9/16 surfaces (bed tops) the
        // arrived Y can round to a hair BELOW the exact box top, and the
        // down-clip then resolves to a tiny POSITIVE value (+0.0025) which
        // fails the collidedDown (<= 0) test - the support probe called bed
        // standing "airborne" and every bed bounce read as an air jump.
        if (Math.abs(result.y) < 0.01D && result.y > 0.0D) return true;

        // Feet sunk INTO a block (soul sand / honey inset tops, and any box
        // whose top is above the feet): the down-probe can't clip it, but a
        // horizontal micro-probe collides immediately.
        session.resolve(px, py, pz, width, height, 0.001D, 0.0D, 0.0D, false, 0.0D, result);
        if (result.collidedX || result.collidedZ) return true;

        // Feet sunk INTO a partial-height block (soul sand, fences, trapdoor
        // rims): the feet CELL being solid means the box top is at/above
        // the feet - genuine support, impossible to "stand inside" otherwise.
        int cellX = (int) Math.floor(px);
        int cellY = (int) Math.floor(py);
        int cellZ = (int) Math.floor(pz);
        if (BlockPropertyRegistry.isSolid(data.getWorldCache().getBlockId(cellX, cellY, cellZ))) {
            return true;
        }

        // Scaffold column support: standing inside a scaffold column the
        // client rests at the cell top even though the collision box is 0.875.
        int colX = (int) Math.floor(px);
        int colZ = (int) Math.floor(pz);
        int colY = (int) Math.floor(py) - 1;
        if (py <= (colY + 1.13D) && BlockPropertyRegistry.getNameFast(
                data.getWorldCache().getBlockId(colX, colY, colZ)).toUpperCase().contains("SCAFFOLDING")) {
            return true;
        }

        // Entity support (boats etc.): the recorded entity box-top under
        // the feet is genuine vanilla ground for riders.
        double supportY = data.getPositionTracker().getNearEntitySupportY();
        if (!Double.isNaN(supportY) && py >= supportY - 0.15D && py <= supportY + 0.35D) return true;

        // Self-placed blocks: the client applies its own placement instantly
        // (Grim prediction-confirmation / Intave client speculation) while
        // our latency view still pins the old state until the ack. Within a
        // short window after placing, consult the shared (un-pinned) cache.
        if (data.getTicksTracked() - data.getLastBlockPlaceTick() <= 4) {
            int bx = (int) Math.floor(px);
            int bz = (int) Math.floor(pz);
            int by = (int) Math.floor(py - 0.5D);
            if (BlockPropertyRegistry.isSolid(data.getWorldCache().getSharedBlockId(bx, by, bz))) {
                return true;
            }
        }
        return false;
    }

    /** True when the feet cell, the cell below, and the 4 horizontal neighbours all read AIR. */
    public static boolean isAreaAllAirAround(PlayerData data) {
        CompensatedWorld cache = data.getWorldCache();
        int fX = (int) Math.floor(data.getX());
        int fY = (int) Math.floor(data.getY());
        int fZ = (int) Math.floor(data.getZ());
        if (cache.getBlockId(fX, fY, fZ) != 0) return false;
        if (cache.getBlockId(fX, fY - 1, fZ) != 0) return false;
        return cache.getBlockId(fX, fY, fZ - 1) == 0
                && cache.getBlockId(fX, fY, fZ + 1) == 0
                && cache.getBlockId(fX + 1, fY, fZ) == 0
                && cache.getBlockId(fX - 1, fY, fZ) == 0;
    }

    /** True when a step-family block (slab/stair) is at the feet cell or a horizontal neighbour. */
    /**
     * Fluid height at the player (modern double-precise model):
     * (8 - level)/9 per vanilla; a same-fluid block above = full 1.0.
     */
    public static double fluidHeightAt(PlayerData data, boolean lava) {
        try {
            CompensatedWorld cache = data.getWorldCache();
            int bX = (int) Math.floor(data.getX());
            int bZ = (int) Math.floor(data.getZ());
            double height = 0.0D;
            String fluidName = lava ? "LAVA" : "WATER";
            for (int bY = (int) Math.floor(data.getY() - 0.2D);
                 bY <= (int) Math.floor(data.getY() + 1.7D); bY++) {
                int id = cache.getBlockId(bX, bY, bZ);
                if (id == 0) continue;
                String name = BlockPropertyRegistry.getNameFast(id).toUpperCase();
                if (name.contains(fluidName)) {
                    com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState state =
                            cache.getBlockState(bX, bY, bZ);
                    int level = 0;
                    try { level = state.getLevel(); } catch (Throwable ignored) {}
                    double h = (8 - Math.min(7, Math.max(0, level))) / 9.0D;
                    int above = cache.getBlockId(bX, bY + 1, bZ);
                    if (above != 0 && BlockPropertyRegistry.getNameFast(above).toUpperCase().contains(fluidName)) {
                        h = 1.0D;
                    }
                    height = Math.max(height, h);
                }
            }
            return height;
        } catch (Throwable t) {
            return 0.5D;
        }
    }

    /** True when a step-family block (slab/stair) is at the feet cell or a horizontal neighbour. */
    public static boolean isStepBlockNear(PlayerData data) {
        CompensatedWorld cache = data.getWorldCache();
        int x = (int) Math.floor(data.getX());
        int y = (int) Math.floor(data.getY() - 0.5D);
        int z = (int) Math.floor(data.getZ());
        int[][] cells = { {x, z}, {x + 1, z}, {x - 1, z}, {x, z + 1}, {x, z - 1} };
        for (int[] c : cells) {
            String name = BlockPropertyRegistry.getNameFast(cache.getBlockId(c[0], y, c[1])).toUpperCase();
            // Beds are 9/16 tall - steppable partial blocks like slabs.
            if (name.contains("SLAB") || name.contains("STAIR") || name.contains("STEP")
                    || (name.contains("BED") && !name.contains("BEDROCK"))) return true;
        }
        return false;
    }

    /**
     * True when the player's feet sit at a Minecraft shape boundary (a
     * multiple of 1/16, where all collision tops live: slabs 8/16, beds
     * 9/16, farmland 15/16, full blocks 16/16) while claiming ground with
     * resting or landing motion. This is the "the cache can't see the
     * block but the geometry says you're on a surface" fallback that
     * keeps beds and cache-missed partial blocks from mass-flagging.
     */
    public static boolean isAtShapeBoundary(PlayerData data) {
        if (!data.isClientGround() || data.isTeleportTick()) return false;
        double y = data.getY();
        if (Math.abs(y * 16.0D - Math.round(y * 16.0D)) > 0.35D) return false;
        double dY = data.getDeltaY();
        return Math.abs(dY) < 0.005D || dY < 0.0D;
    }

    public static boolean isLiquid(Player player) {
        PlayerData data = ret.tawny.truthful.Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null) return false;
        return isLiquid(data);
    }

    /**
     * PlayerData-based variant: same sampling (feet cell, +0.5, -0.2, -0.4)
     * without the Location allocation and the player->data map probe.
     */
    public static boolean isLiquid(PlayerData data) {
        if (data == null) return false;
        CompensatedWorld cache = data.getWorldCache();
        int bX = (int) Math.floor(data.getX());
        int bZ = (int) Math.floor(data.getZ());
        int feetY = (int) Math.floor(data.getY());
        if (BlockPropertyRegistry.isLiquid(cache.getBlockId(bX, feetY, bZ))) return true;
        if (BlockPropertyRegistry.isLiquid(cache.getBlockId(bX, (int) Math.floor(data.getY() + 0.5), bZ))) return true;
        return BlockPropertyRegistry.isLiquid(cache.getBlockId(bX, (int) Math.floor(data.getY() - 0.2), bZ))
                || BlockPropertyRegistry.isLiquid(cache.getBlockId(bX, (int) Math.floor(data.getY() - 0.4), bZ));
    }

    public static boolean isLiquid(Location loc, PlayerData data) {
        if (data == null) return false;
        CompensatedWorld cache = data.getWorldCache();
        if (BlockPropertyRegistry.isLiquid(cache.getBlockId(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()))) return true;
        if (BlockPropertyRegistry.isLiquid(cache.getBlockId(loc.getBlockX(), (int) Math.floor(loc.getY() + 0.5), loc.getBlockZ()))) return true;
        return BlockPropertyRegistry.isLiquid(cache.getBlockId(loc.getBlockX(), (int) Math.floor(loc.getY() - 0.2), loc.getBlockZ()))
                || BlockPropertyRegistry.isLiquid(cache.getBlockId(loc.getBlockX(), (int) Math.floor(loc.getY() - 0.4), loc.getBlockZ()));
    }

    public static boolean isNearLiquid(Player player) {
        PlayerData data = ret.tawny.truthful.Truthful.getInstance().getDataManager().getPlayerData(player);
        return isNearLiquid(data);
    }

    /** PlayerData-based variant: no Location allocation, no map probe. */
    public static boolean isNearLiquid(PlayerData data) {
        if (data == null) return false;
        CompensatedWorld cache = data.getWorldCache();
        int bX = (int) Math.floor(data.getX());
        int bY = (int) Math.floor(data.getY());
        int bZ = (int) Math.floor(data.getZ());

        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (BlockPropertyRegistry.isLiquid(cache.getBlockId(bX + x, bY + y, bZ + z))) return true;
                }
            }
        }
        return false;
    }

    public static boolean hasClimbableNearby(Player player) {
        PlayerData data = ret.tawny.truthful.Truthful.getInstance().getDataManager().getPlayerData(player);
        return hasClimbableNearby(data);
    }

    /** PlayerData-based variant: no map probe (callers on the movement path hold data). */
    public static boolean hasClimbableNearby(PlayerData data) {
        if (data == null) return false;
        CompensatedWorld cache = data.getWorldCache();
        // Vanilla: climbing requires the climbable to intersect the player's
        // bounding box - check every column the 0.6-wide box overlaps (up to
        // 2x2 cells), at feet and feet+1. A box-edge touch on a neighboring
        // scaffold/ladder column counts; a one-block radius does not.
        int[] xs = { (int) Math.floor(data.getX() - 0.3D), (int) Math.floor(data.getX() + 0.3D) };
        int[] zs = { (int) Math.floor(data.getZ() - 0.3D), (int) Math.floor(data.getZ() + 0.3D) };
        for (int cx : xs) {
            for (int cz : zs) {
                for (int dy = 0; dy <= 1; dy++) {
                    int bY = (int) Math.floor(data.getY()) + dy;
                    if (BlockPropertyRegistry.isClimbable(cache.getBlockId(cx, bY, cz))) return true;
                }
            }
        }
        return false;
    }

    public static boolean isScaffoldingNearby(Player player) {
        PlayerData data = ret.tawny.truthful.Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null) return false;
        CompensatedWorld cache = data.getWorldCache();
        int bX = (int) Math.floor(data.getX());
        int bY = (int) Math.floor(data.getY());
        int bZ = (int) Math.floor(data.getZ());
        if (BlockPropertyRegistry.getName(cache.getBlockId(bX, bY, bZ)).contains("SCAFFOLDING")) return true;
        if (BlockPropertyRegistry.getName(cache.getBlockId(bX, bY - 1, bZ)).contains("SCAFFOLDING")) return true;
        return false;
    }

    public static boolean isInWeb(Player player) {
        PlayerData data = ret.tawny.truthful.Truthful.getInstance().getDataManager().getPlayerData(player);
        return isInWeb(data);
    }

    /** PlayerData-based variant: no map probe. */
    public static boolean isInWeb(PlayerData data) {
        if (data == null) return false;
        CompensatedWorld cache = data.getWorldCache();
        int bX = (int) Math.floor(data.getX());
        int bY = (int) Math.floor(data.getY());
        int bZ = (int) Math.floor(data.getZ());
        if (BlockPropertyRegistry.getName(cache.getBlockId(bX, bY, bZ)).contains("COBWEB")) return true;
        if (BlockPropertyRegistry.getName(cache.getBlockId(bX, bY + 1, bZ)).contains("COBWEB")) return true;
        return BlockPropertyRegistry.getName(cache.getBlockId(bX, bY - 1, bZ)).contains("COBWEB");
    }

    /**
     * FIX: Edge Head-Hit Detection
     * Now checks the 4 corners of the player's 0.6x0.6 bounding box at head height.
     */
    public static boolean isHeadHitter(Player player) {
        PlayerData data = ret.tawny.truthful.Truthful.getInstance().getDataManager().getPlayerData(player);
        return isHeadHitter(data);
    }

    /** PlayerData-based variant: no map probe. */
    public static boolean isHeadHitter(PlayerData data) {
        if (data == null) return false;
        CompensatedWorld cache = data.getWorldCache();

        double x = data.getX();
        double z = data.getZ();

        // Scale + pose aware head height. Uses the lenient max-scale so a player
        // who just scaled up still counts a partial clip as a head-hit
        // instead of a vertical fly flag.
        double height;
        double halfWidth;
        try {
            double scale = Math.max(data.getScale(), data.getScaleCacheRaw());
            scale = Math.max(0.0625D, Math.min(16.0D, scale));
            boolean swimLike = data.isSwimmingCached() || data.isGliding();
            if (swimLike) height = 0.6D * scale;
            else if (data.isSneaking()) height = 1.5D * scale;
            else height = 1.8D * scale;
            halfWidth = (0.6D * scale) / 2.0D + 0.05D;
        } catch (Throwable ignored) {
            height = 1.8D;
            halfWidth = 0.35D;
        }

        // Check head plane and slightly below it so edge clips (tiny bit of
        // hitbox still under a block while jumping up through a gap) count.
        double[] headYs = { data.getY() + height, data.getY() + height - 0.25D };
        for (double y : headYs) {
            int floorY = (int) Math.floor(y);
            if (BlockPropertyRegistry.isSolid(cache.getBlockId((int) Math.floor(x - halfWidth), floorY, (int) Math.floor(z - halfWidth)))) return true;
            if (BlockPropertyRegistry.isSolid(cache.getBlockId((int) Math.floor(x + halfWidth), floorY, (int) Math.floor(z - halfWidth)))) return true;
            if (BlockPropertyRegistry.isSolid(cache.getBlockId((int) Math.floor(x - halfWidth), floorY, (int) Math.floor(z + halfWidth)))) return true;
            if (BlockPropertyRegistry.isSolid(cache.getBlockId((int) Math.floor(x + halfWidth), floorY, (int) Math.floor(z + halfWidth)))) return true;
            // Scaffolding never head-blocks jumps (vanilla lets players pass
        // through it from below/inside).
        String blockName = BlockPropertyRegistry.getName(
                data.getWorldCache().getBlockId((int) Math.floor(x), floorY, (int) Math.floor(z)));
        if (blockName.contains("SCAFFOLDING")) return false;
        if (BlockPropertyRegistry.isSolid(cache.getBlockId((int) Math.floor(x), floorY, (int) Math.floor(z)))) return true;
        }

        return false;
    }

    public static boolean isNearIce(Player player) {
        PlayerData data = ret.tawny.truthful.Truthful.getInstance().getDataManager().getPlayerData(player);
        return isNearIce(data);
    }

    /** PlayerData-based variant: no Location allocation, no map probe. */
    public static boolean isNearIce(PlayerData data) {
        if (data == null) return false;
        CompensatedWorld cache = data.getWorldCache();
        return BlockPropertyRegistry.isIce(cache.getBlockId((int) Math.floor(data.getX()), (int) Math.floor(data.getY() - 0.5), (int) Math.floor(data.getZ())));
    }

    /**
     * FIX: Wide ice detection for vehicles.
     * Boats are 1.375 blocks wide and can straddle ice/non-ice boundaries,
     * causing isNearIce() to return false while the boat is still on ice.
     * This checks a wider area below and around the player.
     */
    public static boolean isNearIceWide(Player player) {
        PlayerData data = ret.tawny.truthful.Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null) return false;
        CompensatedWorld cache = data.getWorldCache();
        int bX = (int) Math.floor(data.getX());
        int bZ = (int) Math.floor(data.getZ());
        for (int dy = -1; dy <= 0; dy++) {
            int bY = (int) Math.floor(data.getY() + dy);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (BlockPropertyRegistry.isIce(cache.getBlockId(bX + dx, bY, bZ + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * FIX: Detect blue ice specifically for vehicle speed calculations.
     * Blue ice has friction 0.989 vs regular ice at 0.98, which allows boats
     * to reach significantly higher speeds (~8-9 blocks/tick vs ~4 on regular ice).
     */
    public static boolean isNearBlueIce(Player player) {
        PlayerData data = ret.tawny.truthful.Truthful.getInstance().getDataManager().getPlayerData(player);
        return isNearBlueIce(data);
    }

    /** PlayerData-based variant: no Location allocation, no map probe. */
    public static boolean isNearBlueIce(PlayerData data) {
        if (data == null) return false;
        CompensatedWorld cache = data.getWorldCache();
        int bX = (int) Math.floor(data.getX());
        int bZ = (int) Math.floor(data.getZ());
        for (int dy = -1; dy <= 0; dy++) {
            int bY = (int) Math.floor(data.getY() + dy);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int id = cache.getBlockId(bX + dx, bY, bZ + dz);
                    if (id != 0 && BlockPropertyRegistry.getNameFast(id).contains("BLUE_ICE")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean isBouncy(Player player) {
        PlayerData data = ret.tawny.truthful.Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null) return false;
        return isBouncyBelow(data);
    }

    /**
     * True when the block the player is standing on / just above has a
     * restitution impulse: slime, beds (incl. straw beds, 26.3) and shelf
     * mushrooms (26.3). Honey is deliberately excluded - it slides, never
     * bounces.
     */
    public static boolean isBouncyBelow(PlayerData data) {
        CompensatedWorld cache = data.getWorldCache();
        double px = data.getX(), py = data.getY(), pz = data.getZ();
        double half = Math.max(0.1D, data.getPlayerWidth()) / 2.0D + 0.05D;
        int minX = (int) Math.floor(px - half);
        int maxX = (int) Math.floor(px + half);
        int minZ = (int) Math.floor(pz - half);
        int maxZ = (int) Math.floor(pz + half);
        int bY = (int) Math.floor(py - 0.1D);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int dy = 0; dy <= 1; dy++) {
                    int id = cache.getBlockId(x, bY - dy, z);
                    if (id == 0) continue;
                    String name = BlockPropertyRegistry.getNameFast(id).toUpperCase();
                    if (name.contains("SLIME_BLOCK")) return true;
                    if (name.contains("BED") && !name.contains("BEDROCK")) return true;
                    if (name.contains("SHELF_MUSHROOM")) return true;
                }
            }
        }
        return false;
    }

    /**
     * Bubble column state at the player's body: Boolean.TRUE (magma/down),
     * Boolean.FALSE (soul sand/up) or null when not in a column.
     */
    public static Boolean bubbleColumnDragAt(PlayerData data) {
        CompensatedWorld cache = data.getWorldCache();
        int bX = (int) Math.floor(data.getX());
        int bZ = (int) Math.floor(data.getZ());
        int[] ys = { (int) Math.floor(data.getY()), (int) Math.floor(data.getY() + 1.0),
                (int) Math.floor(data.getY() + 1.6) };
        for (int bY : ys) {
            int id = cache.getBlockId(bX, bY, bZ);
            if (id == 0) continue;
            if (BlockPropertyRegistry.getName(id).contains("BUBBLE_COLUMN")) {
                try {
                    return cache.getBlockState(bX, bY, bZ).isDrag();
                } catch (Throwable ignored) {
                    // Property unavailable (exotic mapping): treat as upward
                    // column - the engine's envelope absorbs the direction.
                    return Boolean.FALSE;
                }
            }
        }
        return null;
    }

    /**
     * 26.2+: potent sulfur under a water column erupts periodically and
     * launches entities above it. True when potent sulfur sits within six
     * blocks below the player in the same water column.
     */
    public static boolean isGeyserColumnBelow(PlayerData data) {
        CompensatedWorld cache = data.getWorldCache();
        int bX = (int) Math.floor(data.getX());
        int bZ = (int) Math.floor(data.getZ());
        int startY = (int) Math.floor(data.getY());
        int minY = data.getWorldMinHeight();
        for (int dy = 1; dy <= 6; dy++) {
            int bY = startY - dy;
            if (bY < minY) break;
            int id = cache.getBlockId(bX, bY, bZ);
            if (id == 0) continue;
            String name = BlockPropertyRegistry.getNameFast(id).toUpperCase();
            if (name.contains("POTENT_SULFUR")) return true;
            // A solid block breaks the column; plain water keeps it going.
            if (name.contains("WATER")) continue;
            if (BlockPropertyRegistry.isSolid(id)) break;
        }
        return false;
    }

    public static boolean isNearMaterial(Player player, Material material) {
        PlayerData data = ret.tawny.truthful.Truthful.getInstance().getDataManager().getPlayerData(player);
        return isNearMaterial(data, material);
    }

    /** PlayerData-based variant: no map probe. */
    public static boolean isNearMaterial(PlayerData data, Material material) {
        if (data == null) return false;
        CompensatedWorld cache = data.getWorldCache();
        int bX = (int) Math.floor(data.getX());
        int bY = (int) Math.floor(data.getY());
        int bZ = (int) Math.floor(data.getZ());
        String matName = material.name();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = -1; y <= 1; y++) {
                    int id = cache.getBlockId(bX + x, bY + y, bZ + z);
                    if (id != 0 && BlockPropertyRegistry.getNameFast(id).equalsIgnoreCase(matName)) return true;
                }
            }
        }
        return false;
    }

    public static boolean isNearStairOrSlab(Player player) {
        PlayerData data = ret.tawny.truthful.Truthful.getInstance().getDataManager().getPlayerData(player);
        return isNearStairOrSlab(data);
    }

    /** PlayerData-based variant: no map probe. */
    public static boolean isNearStairOrSlab(PlayerData data) {
        if (data == null) return false;
        CompensatedWorld cache = data.getWorldCache();
        int bX = (int) Math.floor(data.getX());
        int bY = (int) Math.floor(data.getY());
        int bZ = (int) Math.floor(data.getZ());
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = -1; y <= 1; y++) {
                    String name = BlockPropertyRegistry.getNameFast(cache.getBlockId(bX + x, bY + y, bZ + z));
                    if (name.contains("STAIR") || name.contains("SLAB")) return true;
                }
            }
        }
        return false;
    }

    public static double getBlockHeight(Block block) {
        if (block == null || block.getType().isAir()) return 0.0;
        if (!Bukkit.isPrimaryThread()) return 1.0;

        try {
            VoxelShape shape = block.getCollisionShape();
            if (shape.getBoundingBoxes().isEmpty()) return 0.0;
            double maxY = 0.0;
            for (BoundingBox box : shape.getBoundingBoxes()) {
                if (box.getMaxY() > maxY) maxY = box.getMaxY();
            }
            return maxY;
        } catch (Throwable ignored) {
            Material type = block.getType();
            if (Tag.SLABS.isTagged(type) || Tag.STAIRS.isTagged(type)) return 0.5;
            if (Tag.FENCES.isTagged(type) || Tag.WALLS.isTagged(type)) return 1.5;
            return block.getType().isSolid() ? 1.0 : 0.0;
        }
    }
}