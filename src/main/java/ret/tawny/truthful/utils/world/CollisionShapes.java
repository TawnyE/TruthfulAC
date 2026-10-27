package ret.tawny.truthful.utils.world;

import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.enums.Half;
import com.github.retrooper.packetevents.protocol.world.states.enums.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * Shape resolution is STATE-AWARE: global block ids encode block state
 * properties, so doors (open/closed/facing), trapdoors, slabs (top/bottom/
 * double), snow (layer count) and waterlogged variants all resolve to their
 * real vanilla shapes instead of a bag of "maybe" boxes. Results are cached
 * per global id (bounded by the total state count, so the cache is tiny and
 * permanent).
 *
 * Boxes are block-local fractions: {minX, maxX, minY, maxY, minZ, maxZ}.
 *
 * Known approximation: stair corner variants (inner/outer left/right) are not
 * exposed by the protocol library, so stairs model the straight variant.
 * The engine adds a small stair-proximity allowance to absorb the difference.
 */
public final class CollisionShapes {

    private CollisionShapes() {}

    public record Box(double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
        /**
         * Does this box support a player whose feet are at feetY standing over
         * horizontal range [px0..px1]x[pz0..pz1] (world coordinates)?
         */
        public boolean supportsAt(int bx, int by, int bz,
                                  double px0, double pz0, double px1, double pz1,
                                  double feetY) {
            double bMinX = bx + minX, bMaxX = bx + maxX;
            double bMinZ = bz + minZ, bMaxZ = bz + maxZ;
            if (bMaxX <= px0 || bMinX >= px1 || bMaxZ <= pz0 || bMinZ >= pz1) return false;
            double top = by + maxY;
            return feetY >= top - 0.75D && feetY <= top + 0.35D;
        }

        public Box expand(double x, double y, double z) {
            return new Box(minX - x, maxX + x, minY - y, maxY + y, minZ - z, maxZ + z);
        }
    }

    private static final Box FULL = new Box(0, 1, 0, 1, 0, 1);
    private static final List<Box> FULL_LIST = List.of(FULL);
    private static final List<Box> NONE = List.of();

    /** globalId -> exact state-aware shape (bounded by total block state count). */
    private static final Map<Integer, List<Box>> STATE_CACHE = new ConcurrentHashMap<>();

    public static List<Box> of(WrappedBlockState state) {
        if (state == null || state.getType() == null) return NONE;
        return STATE_CACHE.computeIfAbsent(state.getGlobalId(), id -> lookup(state));
    }

    public static List<Box> of(int globalId) {
        if (globalId == 0) return NONE;
        List<Box> cached = STATE_CACHE.get(globalId);
        if (cached != null) return cached;
        return of(WrappedBlockState.getByGlobalId(globalId));
    }

    private static List<Box> lookup(WrappedBlockState state) {
        String name = state.getType().getName().toUpperCase();

        // --- No collision. Keep exact: CORAL must not swallow CORAL_BLOCK. ---
        if (isNonSolid(name)) {
            return NONE;
        }

        switch (shapeFamily(name)) {
            case CARPET, PRESSURE_PLATE -> { return List.of(new Box(0, 1, 0, 0.0625, 0, 1)); }
            case LILY_PAD -> { return List.of(new Box(0.0625, 0.9375, 0, 0.09375, 0, 1)); }
            case SNOW -> {
                // Exact: layers are encoded in the state (1..8 -> 1/8 blocks each).
                int layers = safeLayers(state);
                return layers <= 0 ? NONE : List.of(new Box(0, 1, 0, Math.min(8, layers) / 8.0, 0, 1));
            }
            // Powder snow has no collision: entities sink through it (or rest
            // on top with leather boots, at the block's integer boundary). A
            // phantom collision floor here made the engine clip sinking
            // players and fight the powder-snow movement context.
            case POWDER_SNOW -> { return NONE; }
            case DAYLIGHT_DETECTOR -> { return List.of(new Box(0, 1, 0, 0.375, 0, 1)); }
            case BED -> { return List.of(new Box(0, 1, 0, 0.5625, 0, 1)); }
            case CAKE -> { return List.of(new Box(0.0625, 0.9375, 0, 0.5, 0, 1)); }
            case FLOWER_POT -> { return List.of(new Box(0.3125, 0.6875, 0, 0.375, 0.3125, 0.6875)); }
            case SCULK_SENSOR -> { return List.of(new Box(0, 1, 0, 0.5, 0, 1)); }
            case STONE_CUTTER -> { return List.of(new Box(0, 1, 0, 0.5625, 0, 1)); }
            case ENCHANTING_TABLE -> { return List.of(new Box(0, 1, 0, 0.75, 0, 1)); }
            case CAMPFIRE -> { return List.of(new Box(0, 1, 0, 0.4375, 0, 1)); }
            case LANTERN -> { return List.of(new Box(0.3125, 0.6875, 0, 0.4375, 0.3125, 0.6875)); }
            case SEA_PICKLE -> { return List.of(new Box(0.375, 0.625, 0, 0.375, 0.375, 0.625)); }
            case TURTLE_EGG -> { return List.of(new Box(0.4375, 0.5625, 0, 0.25, 0.4375, 0.5625)); }
            case SKULL_HEAD -> { return List.of(new Box(0.25, 0.75, 0, 0.5, 0.25, 0.75)); }
            case PISTON_HEAD -> { return pistonHeadBoxes(faceName(state.getFacing())); }
            case LECTERN -> { return List.of(new Box(0, 1, 0, 0.9375, 0, 1)); }
            case BELL -> { return List.of(new Box(0.25, 0.75, 0, 1.0, 0.25, 0.75)); }
            case BREWING_STAND -> { return List.of(new Box(0.125, 0.875, 0, 0.8125, 0.125, 0.875)); }
            case CANDLE -> { return List.of(new Box(0.4375, 0.5625, 0, 0.375, 0.4375, 0.5625)); }
            case CONDUIT -> { return List.of(new Box(0.3125, 0.6875, 0, 0.6875, 0.3125, 0.6875)); }
            case AMETHYST -> { return List.of(new Box(0.1875, 0.8125, 0, 0.8125, 0.1875, 0.8125)); }
            case POINTED_DRIPSTONE, SULFUR_SPIKE -> { return List.of(new Box(0.125, 0.875, 0, 0.6875, 0.125, 0.875)); }
            case BAMBOO -> { return List.of(new Box(0.25, 0.75, 0, 1.0, 0.25, 0.75)); }
            case CHAIN -> { return List.of(new Box(0.40625, 0.59375, 0, 1.0, 0.40625, 0.59375)); }
            case END_ROD -> { return List.of(new Box(0.375, 0.625, 0, 1.0, 0.375, 0.625)); }
            case SCAFFOLDING -> { return List.of(new Box(0, 1, 0, 0.875, 0, 1)); }
            case SHELF -> { return FULL_LIST; }
            case SHELF_MUSHROOM -> { return List.of(new Box(0.1875, 0.8125, 0, 0.4375, 0.1875, 0.8125)); }
            case DECORATED_POT -> { return List.of(new Box(0.1875, 0.8125, 0, 1.0, 0.1875, 0.8125)); }
            case CHEST_FAMILY -> { return List.of(new Box(0.0625, 0.9375, 0, 0.875, 0.0625, 0.9375)); }
            case HOPPER -> {
                return List.of(new Box(0, 1, 0, 1.0, 0, 1),
                        new Box(0.25, 0.75, 0, 0.625, 0.25, 0.75));
            }
            case CAULDRON -> {
                // Real cauldron: four 1/16 walls full height plus the inset
                // base whose top is the 5/16 interior floor. The old single
                // full cube clipped nothing from inside - a player jumping
                // into the cauldron fell through in every candidate while the
                // client rested on the 5/16 floor.
                return List.of(
                        new Box(0, 1, 0, 1, 0, 0.0625),
                        new Box(0, 1, 0, 1, 0.9375, 1),
                        new Box(0, 0.0625, 0, 1, 0.0625, 0.9375),
                        new Box(0.9375, 1, 0, 1, 0.0625, 0.9375),
                        new Box(0.0625, 0.9375, 0, 0.3125, 0.0625, 0.9375));
            }
            case COMPOSTER -> {
                return List.of(new Box(0, 1, 0, 1.0, 0, 1),
                        new Box(0.125, 0.875, 0, 0.125, 0.125, 0.875));
            }
            case ANVIL -> {
                return List.of(new Box(0.125, 0.875, 0, 1.0, 0.125, 0.875),
                        new Box(0.25, 0.75, 0, 0.3125, 0.25, 0.75));
            }
            case SLAB -> { return slabShape(state, name); }
            case STAIR -> { return stairShape(state); }
            case TRAPDOOR -> { return trapdoorShape(state); }
            case DOOR -> { return doorShape(state); }
            case PANE -> { return paneShape(state); }
            case FENCE_GATE -> { return fenceGateShape(state); }
            case FENCE -> { return fenceShape(state); }
            case WALL -> { return wallShape(state); }
            case FARMLAND_FAMILY -> { return List.of(new Box(0, 1, 0, 0.9375, 0, 1)); }
            case SOUL_FAMILY -> { return List.of(new Box(0, 1, 0, 0.875, 0, 1)); }
            default -> { return FULL_LIST; }
        }
    }
    /** Open gates have no collision; closed gates are 1.5-tall plates along their axis. */
    private static List<Box> fenceGateShape(WrappedBlockState state) {
        return fenceGateBoxes(state.isOpen(), faceName(state.getFacing()));
    }

    static List<Box> fenceGateBoxes(boolean open, String facing) {
        if (open) return NONE;
        return switch (facing) {
            case "NORTH", "SOUTH" -> List.of(new Box(0, 1, 0, 1.5, 0.4375, 0.5625));
            default -> List.of(new Box(0.4375, 0.5625, 0, 1.5, 0, 1));
        };
    }

    /**
     * Dynamic fence shape: center post (0.375-0.625 wide, 1.5 tall) plus an
     * arm (0.4375-0.5625 wide, 1.5 tall) toward each connected side. The
     * N/S/E/W connection booleans live in the block state; a static
     * post-only box under-collides connected fences (clients clip against
     * the arms) - the classic fence FP source.
     */
    private static List<Box> fenceShape(WrappedBlockState state) {
        return fenceBoxes(isConnected(state.getNorth()), isConnected(state.getSouth()),
                isConnected(state.getWest()), isConnected(state.getEast()));
    }

    static List<Box> fenceBoxes(boolean north, boolean south, boolean west, boolean east) {
        List<Box> boxes = new ArrayList<>(5);
        boxes.add(new Box(0.375, 0.625, 0, 1.5, 0.375, 0.625));
        if (north) boxes.add(new Box(0.4375, 0.5625, 0, 1.5, 0, 0.4375));
        if (south) boxes.add(new Box(0.4375, 0.5625, 0, 1.5, 0.5625, 1));
        if (west) boxes.add(new Box(0, 0.4375, 0, 1.5, 0.4375, 0.5625));
        if (east) boxes.add(new Box(0.5625, 1, 0, 1.5, 0.4375, 0.5625));
        return List.copyOf(boxes);
    }

    /**
     * Walls: 6/16 post + 6/16 arms, connection-driven. Heights per vanilla:
     * post 1.5 with "up", 1.3125 without (the old model stretched the no-up
     * post to 1.5, so landing on a wall post read a phantom 3/16 overshoot);
     * TALL arms 1.5, LOW arms 0.9375 (clearable by a normal jump - the old
     * model blocked those jumps mid-air).
     */
    private static List<Box> wallShape(WrappedBlockState state) {
        boolean up = false;
        try { up = state.isUp(); } catch (Throwable ignored) {}
        return wallBoxes(up, connectionName(state.getNorth()), connectionName(state.getSouth()),
                connectionName(state.getWest()), connectionName(state.getEast()));
    }

    static List<Box> wallBoxes(boolean up, String north, String south, String west, String east) {
        List<Box> boxes = new ArrayList<>(5);
        double postHeight = up ? 1.5 : 1.3125;
        boxes.add(new Box(0.3125, 0.6875, 0, postHeight, 0.3125, 0.6875));
        // Arm heights are per-side (LOW vs TALL), independent of the post.
        boxes.add(armBox(0.3125, 0.6875, 0, 0.3125, armHeight(north)));
        boxes.add(armBox(0.3125, 0.6875, 0.6875, 1, armHeight(south)));
        boxes.add(armBox(0, 0.3125, 0.3125, 0.6875, armHeight(west)));
        boxes.add(armBox(0.6875, 1, 0.3125, 0.6875, armHeight(east)));
        return List.copyOf(boxes);
    }

    private static Box armBox(double minX, double maxX, double minZ, double maxZ, double height) {
        return new Box(minX, maxX, 0, height, minZ, maxZ);
    }

    /** Arm height for a wall side connection: LOW = 0.9375, TALL/TRUE = 1.5. */
    static double armHeight(String connection) {
        if (connection == null) return 0.0;
        String v = connection.toUpperCase(java.util.Locale.ROOT);
        return switch (v) {
            case "LOW" -> 0.9375D;
            case "TALL", "TRUE" -> 1.5D;
            default -> 0.0;
        };
    }

    /**
     * Panes / iron bars: thin 2/16 strips. A strip runs the full block length
     * on a connected axis; disconnected sides keep only the tiny center post
     * (the short vanilla nubs are sub-1/16 and omitted). The old model was a
     * 2/16 center ROD, so pane walls had no collision along their faces at
     * all - walking into a pane screen read as pass-through in every
     * candidate and flagged the resulting offset.
     */
    private static List<Box> paneShape(WrappedBlockState state) {
        return paneBoxes(isConnected(state.getNorth()), isConnected(state.getSouth()),
                isConnected(state.getWest()), isConnected(state.getEast()));
    }

    static List<Box> paneBoxes(boolean north, boolean south, boolean west, boolean east) {
        List<Box> boxes = new ArrayList<>(3);
        boxes.add(new Box(0.4375, 0.5625, 0, 1, 0.4375, 0.5625)); // center post
        if (north || south) {
            // N/S strip: thin in X, full length in Z.
            boxes.add(new Box(0.4375, 0.5625, 0, 1, 0, 1));
        }
        if (west || east) {
            // E/W strip: thin in Z, full length in X.
            boxes.add(new Box(0, 1, 0, 1, 0.4375, 0.5625));
        }
        return List.copyOf(boxes);
    }

    private static boolean isConnected(Object directionValue) {
        return directionValue != null && armHeight(directionValue.toString()) > 0.0;
    }

    private static String connectionName(Object directionValue) {
        return directionValue == null ? null : directionValue.toString();
    }

    // ------------------------------------------------------------------
    // Families with real state properties
    // ------------------------------------------------------------------

    private static List<Box> slabShape(WrappedBlockState state, String name) {
        Type type = state.getTypeData();
        return slabBoxes(type == null ? null : type.name());
    }

    static List<Box> slabBoxes(String typeName) {
        if (typeName == null) {
            // State properties unavailable (exotic/unknown id): both halves as candidates.
            return List.of(new Box(0, 1, 0, 0.5, 0, 1), FULL);
        }
        return switch (typeName) {
            case "TOP" -> List.of(new Box(0, 1, 0.5, 1.0, 0, 1));
            case "BOTTOM" -> List.of(new Box(0, 1, 0, 0.5, 0, 1));
            case "DOUBLE" -> FULL_LIST;
            // LEFT/RIGHT/SINGLE are piston/technical blocks, not slabs - full cube is safe.
            default -> FULL_LIST;
        };
    }

    /**
     * Straight stair: base slab on the half, upper slab on the opposite end of
     * the facing axis, plus the riser between them. Corner variants are not
     * distinguishable through the protocol library's state API; the straight
     * model covers the load-bearing faces and the engine adds a small
     * stair-proximity allowance for the corner cases.
     */
    private static List<Box> stairShape(WrappedBlockState state) {
        Half half = state.getHalf();
        boolean top = half == Half.TOP || half == Half.UPPER;
        return stairBoxes(top, faceName(state.getFacing()));
    }

    static List<Box> stairBoxes(boolean top, String facing) {
        double baseMinY = top ? 0.5 : 0.0;
        double baseMaxY = top ? 1.0 : 0.5;
        List<Box> boxes = new ArrayList<>(3);
        boxes.add(new Box(0, 1, baseMinY, baseMaxY, 0, 1));

        double upperMinY = top ? 0.0 : 0.5;
        double upperMaxY = top ? 0.5 : 1.0;
        switch (facing == null ? "NORTH" : facing) {
            case "NORTH" -> boxes.add(new Box(0, 1, upperMinY, upperMaxY, 0, 0.5));
            case "SOUTH" -> boxes.add(new Box(0, 1, upperMinY, upperMaxY, 0.5, 1));
            case "WEST" -> boxes.add(new Box(0, 0.5, upperMinY, upperMaxY, 0, 1));
            case "EAST" -> boxes.add(new Box(0.5, 1, upperMinY, upperMaxY, 0, 1));
            default -> boxes.add(new Box(0, 1, upperMinY, upperMaxY, 0, 1));
        }
        return List.copyOf(boxes);
    }

    private static List<Box> trapdoorShape(WrappedBlockState state) {
        Half half = state.getHalf();
        boolean top = half == Half.TOP || half == Half.UPPER;
        return trapdoorBoxes(top, state.isOpen(), faceName(state.getFacing()));
    }

    static List<Box> trapdoorBoxes(boolean top, boolean open, String facing) {
        if (!open) {
            double minY = top ? 0.8125 : 0.0;
            double maxY = top ? 1.0 : 0.1875;
            return List.of(new Box(0, 1, minY, maxY, 0, 1));
        }
        String face = facing == null ? "NORTH" : facing;
        return switch (face) {
            case "NORTH" -> List.of(new Box(0, 1, 0, 1, 0, 0.1875));
            case "SOUTH" -> List.of(new Box(0, 1, 0, 1, 0.8125, 1));
            case "WEST" -> List.of(new Box(0, 0.1875, 0, 1, 0, 1));
            case "EAST" -> List.of(new Box(0.8125, 1, 0, 1, 0, 1));
            default -> List.of(new Box(0, 1, 0, 0.1875, 0, 1));
        };
    }

    /**
     * Doors are flush 3/16 panels, never centered plates:
     *   closed: panel sits at the block edge OPPOSITE the facing (vanilla
     *   doors mount on the edge nearest the placer, and double doors meet at
     *   the seam). The old centered box put every door bump ~0.19-0.44 blocks
     *   off from where the client actually stops.
     *   open: panel rotates onto the hinge-post edge (left/right as viewed
     *   from outside, i.e. looking along the facing).
     */
    private static List<Box> doorShape(WrappedBlockState state) {
        boolean rightHinge = state.getHinge() == com.github.retrooper.packetevents.protocol.world.states.enums.Hinge.RIGHT;
        return doorBoxes(state.isOpen(), faceName(state.getFacing()), rightHinge);
    }

    static List<Box> doorBoxes(boolean open, String facing, boolean rightHinge) {
        String face = facing == null ? "NORTH" : facing;
        if (!open) {
            return switch (face) {
                case "NORTH" -> List.of(new Box(0, 1, 0, 1, 0.8125, 1));
                case "SOUTH" -> List.of(new Box(0, 1, 0, 1, 0, 0.1875));
                case "WEST" -> List.of(new Box(0.8125, 1, 0, 1, 0, 1));
                case "EAST" -> List.of(new Box(0, 0.1875, 0, 1, 0, 1));
                default -> List.of(new Box(0, 1, 0, 1, 0.4375, 0.5625));
            };
        }
        return switch (face) {
            case "NORTH" -> rightHinge ? List.of(new Box(0.8125, 1, 0, 1, 0, 1))
                                       : List.of(new Box(0, 0.1875, 0, 1, 0, 1));
            case "SOUTH" -> rightHinge ? List.of(new Box(0, 0.1875, 0, 1, 0, 1))
                                       : List.of(new Box(0.8125, 1, 0, 1, 0, 1));
            case "EAST" -> rightHinge ? List.of(new Box(0, 1, 0, 1, 0.8125, 1))
                                       : List.of(new Box(0, 1, 0, 1, 0, 0.1875));
            case "WEST" -> rightHinge ? List.of(new Box(0, 1, 0, 1, 0, 0.1875))
                                       : List.of(new Box(0, 1, 0, 1, 0.8125, 1));
            default -> List.of(new Box(0.4375, 0.5625, 0, 1, 0, 1));
        };
    }

    /** null-safe BlockFace -> name. */
    private static String faceName(BlockFace face) {
        return face == null ? null : face.name();
    }

    /**
     * Extended piston head: a full 16/16 plate (4/16 thick) at the facing
     * end of the cell plus a 4/16 rod back toward the base. The head block
     * was previously routed to the skull family, whose half-height box left
     * players standing on an extended piston (piston doors, elevators,
     * redstone flying machines) with no support and no Y clip.
     */
    static List<Box> pistonHeadBoxes(String facing) {
        String face = facing == null ? "UP" : facing;
        return switch (face) {
            case "DOWN" -> List.of(
                    new Box(0, 1, 0, 0.25, 0, 1),
                    new Box(0.375, 0.625, 0.25, 1, 0.375, 0.625));
            case "NORTH" -> List.of(
                    new Box(0, 1, 0, 1, 0.75, 1),
                    new Box(0.375, 0.625, 0, 0.75, 0.375, 0.625));
            case "SOUTH" -> List.of(
                    new Box(0, 1, 0, 1, 0, 0.25),
                    new Box(0.375, 0.625, 0.25, 1, 0.375, 0.625));
            case "WEST" -> List.of(
                    new Box(0.75, 1, 0, 1, 0, 1),
                    new Box(0, 0.75, 0, 1, 0.375, 0.625));
            case "EAST" -> List.of(
                    new Box(0, 0.25, 0, 1, 0, 1),
                    new Box(0.25, 1, 0, 1, 0.375, 0.625));
            default -> List.of( // UP: plate on top, rod below.
                    new Box(0, 1, 0.75, 1, 0, 1),
                    new Box(0.375, 0.625, 0, 0.75, 0.375, 0.625));
        };
    }

    // ------------------------------------------------------------------
    // Classification
    // ------------------------------------------------------------------

    private static int safeLayers(WrappedBlockState state) {
        try {
            return state.getLayers();
        } catch (Throwable t) {
            return 1;
        }
    }

    static boolean isNonSolid(String name) {
        // Air forms are EXACT matches: a contains("AIR") check also swallowed
        // every *_STAIRS block in the game ("STAIRS" contains "AIR"), which
        // silently disabled stair collision entirely.
        if (name.equals("AIR") || name.equals("CAVE_AIR") || name.equals("VOID_AIR")
                || name.equals("LIGHT") || name.equals("STRUCTURE_AIR")) return true;
        if (name.contains("WATER") || name.contains("LAVA") || name.contains("BUBBLE_COLUMN")) return true;
        // FIRE blocks have no collision, but CAMPFIRE does (real 7/16 shape):
        // the old contains("FIRE") call made every campfire phantom-air.
        if (name.equals("FIRE") || name.endsWith("_FIRE")) return true;
        // Portals have no collision; the FRAME blocks do.
        if (name.contains("PORTAL") && !name.contains("FRAME")) return true;
        // Crops: thin plants, never phantom cubes. Explicit stem list because
        // MUSHROOM_STEM is a solid full block that ends with _STEM.
        if (name.equals("WHEAT") || name.equals("CARROTS") || name.equals("POTATOES")
                || name.equals("BEETROOTS") || name.equals("MELON_STEM") || name.equals("PUMPKIN_STEM")
                || name.endsWith("_CROP") || name.equals("PITCHER_PLANT")) return true;
        // NETHER_WART only - NETHER_WART_BLOCK is a full solid block.
        if (name.equals("NETHER_WART")) return true;
        if (name.contains("TORCH")) return true;
        // Roots are thin plants EXCEPT the solid mangrove root blocks.
        if (name.contains("ROOTS") && !name.contains("MANGROVE")) return true;
        if (name.equals("SMALL_DRIPLEAF") || name.contains("SPORE_BLOSSOM") || name.contains("PROPAGULE")) return true;
        if (name.contains("SAPLING") || name.equals("GRASS") || name.equals("SHORT_GRASS")
                || name.contains("TALL_GRASS")
                || name.contains("FERN") || name.contains("SHRUB") || name.contains("BUSH")) return true;
        if (name.equals("BROWN_MUSHROOM") || name.equals("RED_MUSHROOM")) return true;
        // FLOWER but not FLOWER_POT; CORAL but never CORAL_BLOCK.
        if ((name.contains("FLOWER") || name.contains("FLOWERS")) && !name.contains("FLOWER_POT")) return true;
        if (name.contains("CORAL") && !name.contains("CORAL_BLOCK")) return true;
        if (name.contains("VINE") || name.contains("SCULK_VEIN") || name.contains("GLOW_LICHEN")) return true;
        if (name.contains("LADDER") || name.contains("MOSS_CARPET") || name.contains("TRIPWIRE")
                || name.contains("REDSTONE_WIRE")) return true;
        if (name.contains("REPEATER") || name.contains("COMPARATOR") || name.contains("LEVER")) return true;
        if (name.contains("BUTTON") || name.contains("BANNER") || name.contains("SIGN")) return true;
        if (name.contains("RAIL") || name.contains("SUGAR_CANE") || name.contains("KELP")) return true;
        if (name.contains("SEAGRASS") || name.contains("FUNGUS")) return true;
        if (name.contains("SPROUTS") || name.contains("COBWEB") || name.contains("CUSHION")) return true;
        // The moving piston block is handled by the push allowance; a phantom
        // full cube here clipped players standing in the moving cell.
        if (name.contains("MOVING_PISTON")) return true;
        return false;
    }

    enum Family {
        CARPET, PRESSURE_PLATE, LILY_PAD, SNOW, POWDER_SNOW, DAYLIGHT_DETECTOR, BED, CAKE, FLOWER_POT,
        SCULK_SENSOR, STONE_CUTTER, ENCHANTING_TABLE, CAMPFIRE, LANTERN, SEA_PICKLE,
        TURTLE_EGG, SKULL_HEAD, PISTON_HEAD, LECTERN, BELL, BREWING_STAND, CANDLE, CONDUIT, AMETHYST,
        POINTED_DRIPSTONE, SULFUR_SPIKE, BAMBOO, CHAIN, END_ROD, SCAFFOLDING, SHELF,
        SHELF_MUSHROOM, DECORATED_POT, CHEST_FAMILY, HOPPER, CAULDRON, COMPOSTER, ANVIL,
        SLAB, STAIR, TRAPDOOR, DOOR, PANE, FENCE_GATE, FENCE, WALL, FARMLAND_FAMILY, SOUL_FAMILY, FULL
    }

    static Family shapeFamily(String name) {
        // Order matters: SNOW_BLOCK/POWDER_SNOW must be peeled off before the
        // generic SNOW match, or the full snow block resolves to a 1/8 layer
        // slab and powder snow grows a phantom 1/8 collision floor.
        if (name.contains("SNOW_BLOCK")) return Family.FULL;
        if (name.equals("POWDER_SNOW")) return Family.POWDER_SNOW;
        if (name.contains("SNOW")) return Family.SNOW;
        if (name.contains("CARPET")) return Family.CARPET;
        if (name.contains("PRESSURE_PLATE")) return Family.PRESSURE_PLATE;
        if (name.contains("LILY_PAD")) return Family.LILY_PAD;
        if (name.contains("DAYLIGHT_DETECTOR")) return Family.DAYLIGHT_DETECTOR;
        if (name.contains("BED") && !name.contains("BEDROCK")) return Family.BED;
        if (name.contains("CAKE")) return Family.CAKE;
        if (name.contains("FLOWER_POT") || name.contains("POTTED_")) return Family.FLOWER_POT;
        if (name.contains("SCULK_SENSOR")) return Family.SCULK_SENSOR;
        if (name.contains("STONE_CUTTER") || name.contains("STONECUTTER")) return Family.STONE_CUTTER;
        if (name.contains("ENCHANTING_TABLE")) return Family.ENCHANTING_TABLE;
        if (name.contains("CAMPFIRE")) return Family.CAMPFIRE;
        if (name.contains("LANTERN")) return Family.LANTERN;
        if (name.contains("SEA_PICKLE")) return Family.SEA_PICKLE;
        if (name.contains("TURTLE_EGG")) return Family.TURTLE_EGG;
        // PISTON_HEAD contains "HEAD" but is not a skull: the old skull box
        // (half-height) made standing on an extended piston head unsupported.
        if (name.equals("PISTON_HEAD")) return Family.PISTON_HEAD;
        if (name.contains("SKULL") || name.contains("HEAD")) return Family.SKULL_HEAD;
        // Lightning rods are thin columns, not full cubes - a phantom full
        // cube here head-hit-exempted every jump under one.
        if (name.contains("LIGHTNING_ROD")) return Family.CHAIN;
        if (name.contains("LECTERN")) return Family.LECTERN;
        if (name.contains("BELL")) return Family.BELL;
        if (name.contains("BREWING_STAND")) return Family.BREWING_STAND;
        if (name.contains("CANDLE")) return Family.CANDLE;
        if (name.contains("CONDUIT")) return Family.CONDUIT;
        // Cluster-shaped amethysts only; AMETHYST_BLOCK is a full block.
        if (name.contains("AMETHYST") && !name.equals("AMETHYST_BLOCK")) return Family.AMETHYST;
        if (name.contains("POINTED_DRIPSTONE")) return Family.POINTED_DRIPSTONE;
        if (name.contains("SULFUR_SPIKE")) return Family.SULFUR_SPIKE;
        if (name.contains("BAMBOO")) return Family.BAMBOO;
        // The CHAIN block only; CHAIN_COMMAND_BLOCK is a full cube.
        if (name.equals("CHAIN")) return Family.CHAIN;
        if (name.contains("END_ROD")) return Family.END_ROD;
        if (name.contains("SCAFFOLDING")) return Family.SCAFFOLDING;
        if (name.contains("SHELF_MUSHROOM") || name.contains("BIG_SHELF_MUSHROOM")) return Family.SHELF_MUSHROOM;
        if (name.contains("SHELF")) return Family.SHELF;
        if (name.contains("DECORATED_POT")) return Family.DECORATED_POT;
        // Chests/ender chests share the inset chest box; BARREL is a full
        // cube and must not inherit it.
        if (name.contains("CHEST") || name.contains("ENDER")) return Family.CHEST_FAMILY;
        if (name.contains("HOPPER")) return Family.HOPPER;
        if (name.contains("CAULDRON")) return Family.CAULDRON;
        if (name.contains("COMPOSTER")) return Family.COMPOSTER;
        if (name.contains("ANVIL")) return Family.ANVIL;
        if (name.contains("SLAB")) return Family.SLAB;
        if (name.contains("STAIR")) return Family.STAIR;
        if (name.contains("TRAPDOOR")) return Family.TRAPDOOR;
        if (name.contains("DOOR")) return Family.DOOR;
        if (name.contains("GLASS_PANE") || name.contains("IRON_BARS")) return Family.PANE;
        if (name.contains("FENCE_GATE")) return Family.FENCE_GATE;
        if (name.contains("FENCE")) return Family.FENCE;
        if (name.endsWith("WALL") || name.contains("_WALL") || name.endsWith("_WALLS")) return Family.WALL;
        if (name.contains("SOUL_SAND") || name.contains("SOUL_SOIL")) return Family.SOUL_FAMILY;
        // 15/16 tops for farmland/dirt path; MUD blocks are full cubes.
        if (name.contains("FARMLAND") || name.contains("DIRT_PATH")) return Family.FARMLAND_FAMILY;
        return Family.FULL;
    }
}
