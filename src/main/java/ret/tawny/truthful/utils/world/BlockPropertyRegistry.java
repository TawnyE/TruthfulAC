package ret.tawny.truthful.utils.world;

import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Block physical properties: friction, solidity, liquid/climbable/ice/thin
 * classification. Cached per global state id because solidity is
 * state-dependent (an open door is not solid; a closed one is).
 */
public final class BlockPropertyRegistry {

    private BlockPropertyRegistry() {}

    private static final BlockProperties DEFAULT = new BlockProperties(0.6f, false, false, false, false, false);
    private static final BlockProperties DEFAULT_SOLID = new BlockProperties(0.6f, true, false, false, false, false);

    /** globalId -> properties (bounded by total block state count). */
    private static final Map<Integer, BlockProperties> PROPERTIES = new ConcurrentHashMap<>();

    public static BlockProperties get(WrappedBlockState state) {
        if (state == null || state.getType() == null) return DEFAULT;
        return PROPERTIES.computeIfAbsent(state.getGlobalId(), id -> compute(state));
    }

    private static BlockProperties compute(WrappedBlockState state) {
        String name = state.getType().getName().toUpperCase();

        float friction = 0.6f;
        if (name.contains("BLUE_ICE")) friction = 0.989f;
        else if (name.contains("ICE")) friction = 0.98f;
        else if (name.contains("SLIME_BLOCK")) friction = 0.8f;
        else if (name.contains("HONEY_BLOCK") || name.contains("SOUL_SAND") || name.contains("SOUL_SOIL")) friction = 0.4f;

        // Solid = "has collision boxes", resolved by the same shape system the
        // engine uses so ground/head-hitter logic can never disagree with
        // collision resolution. Air/torches/flowers/signs are honestly
        // non-solid; beds/slabs/closed doors are solid because they have boxes.
        boolean solid = !CollisionShapes.of(state).isEmpty();

        boolean liquid = name.contains("WATER") || name.contains("LAVA") || name.contains("BUBBLE_COLUMN");
        boolean climbable = name.contains("LADDER") || name.contains("VINE") || name.contains("SCAFFOLDING") ||
                           name.contains("TWISTING_VINES") || name.contains("WEEPING_VINES") || name.contains("GLOW_BERRIES");
        boolean ice = name.contains("ICE");
        boolean thin = name.contains("CARPET") || name.contains("SNOW") || name.contains("PLATE") || name.contains("LILY_PAD");

        return new BlockProperties(friction, solid, liquid, climbable, ice, thin);
    }

    public static float getFriction(WrappedBlockState state) {
        return get(state).friction;
    }

    public static boolean isSolid(WrappedBlockState state) {
        return get(state).solid;
    }

    public static boolean isLiquid(WrappedBlockState state) {
        return get(state).liquid;
    }

    public static boolean isClimbable(WrappedBlockState state) {
        return get(state).climbable;
    }

    public static boolean isIce(WrappedBlockState state) {
        return get(state).ice;
    }

    public static boolean isThin(WrappedBlockState state) {
        return get(state).thin;
    }

    /** State lookup that never throws: a hostile chunk can carry arbitrary
     *  "global ids" that map to nothing, and an NPE on the packet thread is
     *  a crash vector, not a verdict. Falls back to the default-solid read. */
    private static WrappedBlockState safeState(int globalId) {
        if (globalId == 0) return null;
        try {
            return WrappedBlockState.getByGlobalId(globalId);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String getName(int globalId) {
        WrappedBlockState state = safeState(globalId);
        if (state == null || state.getType() == null) return "AIR";
        return state.getType().getName().toUpperCase();
    }

    public static String getNameFast(int globalId) {
        WrappedBlockState state = safeState(globalId);
        if (state == null || state.getType() == null) return "AIR";
        return state.getType().getName();
    }

    public static boolean isSolid(int globalId) {
        WrappedBlockState state = safeState(globalId);
        if (state == null || state.getType() == null) return false;
        return get(state).solid;
    }

    public static boolean isLiquid(int globalId) {
        WrappedBlockState state = safeState(globalId);
        if (state == null || state.getType() == null) return false;
        return get(state).liquid;
    }

    public static boolean isClimbable(int globalId) {
        WrappedBlockState state = safeState(globalId);
        if (state == null || state.getType() == null) return false;
        return get(state).climbable;
    }

    public static boolean isIce(int globalId) {
        WrappedBlockState state = safeState(globalId);
        if (state == null || state.getType() == null) return false;
        return get(state).ice;
    }

    public static boolean isTall(int globalId) {
        WrappedBlockState state = safeState(globalId);
        if (state == null || state.getType() == null) return false;
        String name = state.getType().getName().toUpperCase();
        return name.contains("FENCE") || name.endsWith("WALL");
    }

    public static float getFriction(int globalId) {
        WrappedBlockState state = safeState(globalId);
        if (state == null || state.getType() == null) return 0.6f;
        return get(state).friction;
    }

    public static class BlockProperties {
        public final float friction;
        public final boolean solid;
        public final boolean liquid;
        public final boolean climbable;
        public final boolean ice;
        public final boolean thin;

        public BlockProperties(float friction, boolean solid, boolean liquid, boolean climbable, boolean ice, boolean thin) {
            this.friction = friction;
            this.solid = solid;
            this.liquid = liquid;
            this.climbable = climbable;
            this.ice = ice;
            this.thin = thin;
        }
    }
}
