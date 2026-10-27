package ret.tawny.truthful.utils.world;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Collision shape table regression tests. Every case here maps to a real
 * false-flag class that shipped at some point: stairs resolved as air
 * ("STAIRS".contains("AIR")), snow blocks as 1/8 slabs, phantom full cubes
 * for crops/grass/portals, pane walls with no face collision, centered
 * doors, fence-tall wall posts. The routing and geometry are pure string /
 * primitive functions so they are testable without a running server.
 */
class CollisionShapesTest {

    private static boolean hasBoxWithMaxY(List<CollisionShapes.Box> boxes, double maxY) {
        return boxes.stream().anyMatch(b -> Math.abs(b.maxY() - maxY) < 1.0E-6);
    }

    @Test
    void stairsRouteToStairFamilyNotAir() {
        // "OAK_STAIRS".contains("AIR") used to send every stair to the
        // no-collision list before the family switch ever ran.
        assertFalse(CollisionShapes.isNonSolid("OAK_STAIRS"), "stairs must not be classified non-solid");
        assertFalse(CollisionShapes.isNonSolid("COBBLESTONE_STAIRS"));
        assertFalse(CollisionShapes.isNonSolid("CRIMSON_STAIRS"));

        assertEquals(CollisionShapes.Family.STAIR, CollisionShapes.shapeFamily("OAK_STAIRS"));

        List<CollisionShapes.Box> bottom = CollisionShapes.stairBoxes(false, "NORTH");
        assertTrue(hasBoxWithMaxY(bottom, 0.5), "bottom-half stair must have the 1/2 base slab");
        List<CollisionShapes.Box> top = CollisionShapes.stairBoxes(true, "EAST");
        assertTrue(hasBoxWithMaxY(top, 1.0));
        assertTrue(hasBoxWithMaxY(top, 0.5), "top-half stair must still cover the lower step");
    }

    @Test
    void airRoutingIsExact() {
        assertTrue(CollisionShapes.isNonSolid("AIR"));
        assertTrue(CollisionShapes.isNonSolid("CAVE_AIR"));
        assertTrue(CollisionShapes.isNonSolid("VOID_AIR"));
        // The classic containment trap: these must all stay SOLID.
        assertFalse(CollisionShapes.isNonSolid("STAIRS"));
        assertFalse(CollisionShapes.isNonSolid("CHAIR")); // hypothetical future block
        assertEquals(CollisionShapes.Family.FULL, CollisionShapes.shapeFamily("SNOW_BLOCK"));
        assertEquals(CollisionShapes.Family.POWDER_SNOW, CollisionShapes.shapeFamily("POWDER_SNOW"));
        assertEquals(CollisionShapes.Family.SNOW, CollisionShapes.shapeFamily("SNOW"));
    }

    @Test
    void thinPlantsAreNotPhantomCubes() {
        // These used to fall through to FULL: walking through grass fields and
        // wheat farms read as walking into solid blocks.
        for (String name : new String[]{"SHORT_GRASS", "GRASS", "TALL_GRASS", "WHEAT", "CARROTS",
                "POTATOES", "BEETROOTS", "PITCHER_CROP", "PITCHER_PLANT", "MELON_STEM", "PUMPKIN_STEM",
                "SWEET_BERRY_BUSH", "DEAD_BUSH", "SMALL_DRIPLEAF", "HANGING_ROOTS", "CRIMSON_ROOTS",
                "NETHER_PORTAL", "END_PORTAL", "MOVING_PISTON", "LIGHT", "SPORE_BLOSSOM",
                "MANGROVE_PROPAGULE", "NETHER_WART"}) {
            assertTrue(CollisionShapes.isNonSolid(name), name + " must be non-solid");
        }
    }

    @Test
    void solidBlocksAreNotPhantomAir() {
        // These were wrongly classified non-solid by over-broad contains() matches.
        for (String name : new String[]{"NETHER_WART_BLOCK", "MANGROVE_ROOTS", "MUDDY_MANGROVE_ROOTS",
                "CAMPFIRE", "SOUL_CAMPFIRE", "GRASS_BLOCK", "SNOW_BLOCK", "AMETHYST_BLOCK",
                "CHAIN_COMMAND_BLOCK", "MUD", "MUD_BRICKS", "END_PORTAL_FRAME", "MUSHROOM_STEM",
                "STONE", "STAIRS"}) {
            assertFalse(CollisionShapes.isNonSolid(name), name + " must stay solid");
        }
        assertEquals(CollisionShapes.Family.CAMPFIRE, CollisionShapes.shapeFamily("SOUL_CAMPFIRE"));
        assertEquals(CollisionShapes.Family.FULL, CollisionShapes.shapeFamily("AMETHYST_BLOCK"));
        assertEquals(CollisionShapes.Family.AMETHYST, CollisionShapes.shapeFamily("AMETHYST_CLUSTER"));
        assertEquals(CollisionShapes.Family.CHAIN, CollisionShapes.shapeFamily("CHAIN"));
        assertEquals(CollisionShapes.Family.FULL, CollisionShapes.shapeFamily("BARREL"));
        assertEquals(CollisionShapes.Family.FULL, CollisionShapes.shapeFamily("MUD"));
        assertEquals(CollisionShapes.Family.FARMLAND_FAMILY, CollisionShapes.shapeFamily("DIRT_PATH"));
    }

    @Test
    void panesHaveFaceCollision() {
        // A connected pane row is a thin wall: the strip must run the full
        // block length or walking into a pane screen reads as pass-through.
        List<CollisionShapes.Box> row = CollisionShapes.paneBoxes(true, true, false, false);
        assertTrue(row.stream().anyMatch(b -> Math.abs(b.minZ()) < 1.0E-9 && Math.abs(b.maxZ() - 1.0) < 1.0E-9
                        && Math.abs(b.minX() - 0.4375) < 1.0E-9 && Math.abs(b.maxX() - 0.5625) < 1.0E-9),
                "N/S-connected pane must carry a full-length thin strip");

        // Lone pane: only the center post, so the block face stays walkable.
        List<CollisionShapes.Box> lone = CollisionShapes.paneBoxes(false, false, false, false);
        assertEquals(1, lone.size(), "disconnected pane keeps just the center post");

        List<CollisionShapes.Box> bars = CollisionShapes.paneBoxes(false, false, true, true);
        assertTrue(bars.stream().anyMatch(b -> Math.abs(b.minX()) < 1.0E-9 && Math.abs(b.maxX() - 1.0) < 1.0E-9
                        && Math.abs(b.minZ() - 0.4375) < 1.0E-9 && Math.abs(b.maxZ() - 0.5625) < 1.0E-9),
                "E/W-connected bars must carry a full-length thin strip");
    }

    @Test
    void doorsAreFlushPanels() {
        // Closed: 3/16 panel at the edge OPPOSITE the facing, never centered.
        CollisionShapes.Box closedNorth = CollisionShapes.doorBoxes(false, "NORTH", false).get(0);
        assertEquals(0.8125, closedNorth.minZ(), 1.0E-9, "closed north door sits on the south edge");
        assertEquals(1.0, closedNorth.maxZ(), 1.0E-9);
        assertEquals(0.0, closedNorth.minX(), 1.0E-9);
        assertEquals(1.0, closedNorth.maxX(), 1.0E-9);

        CollisionShapes.Box closedEast = CollisionShapes.doorBoxes(false, "EAST", false).get(0);
        assertEquals(0.0, closedEast.minX(), 1.0E-9, "closed east door sits on the west edge");
        assertEquals(0.1875, closedEast.maxX(), 1.0E-9);

        // Open: plate rotated onto the hinge-post edge (left/right viewed
        // from outside, looking along the facing).
        CollisionShapes.Box openLeft = CollisionShapes.doorBoxes(true, "NORTH", false).get(0);
        assertEquals(0.0, openLeft.minX(), 1.0E-9, "left hinge (facing N) parks the panel on the west edge");
        assertEquals(0.1875, openLeft.maxX(), 1.0E-9);
        assertEquals(0.0, openLeft.minZ(), 1.0E-9);
        assertEquals(1.0, openLeft.maxZ(), 1.0E-9);

        CollisionShapes.Box openRight = CollisionShapes.doorBoxes(true, "NORTH", true).get(0);
        assertEquals(0.8125, openRight.minX(), 1.0E-9, "right hinge (facing N) parks the panel on the east edge");

        CollisionShapes.Box openEastLeft = CollisionShapes.doorBoxes(true, "EAST", false).get(0);
        assertEquals(0.0, openEastLeft.minZ(), 1.0E-9, "left hinge (facing E) parks the panel on the north edge");
        assertEquals(0.1875, openEastLeft.maxZ(), 1.0E-9);
    }

    @Test
    void wallsUseVanillaHeights() {
        // No-up post tops at 1.3125 (21/16), not the fence-style 1.5.
        List<CollisionShapes.Box> post = CollisionShapes.wallBoxes(false, "NONE", "NONE", "NONE", "NONE");
        assertTrue(hasBoxWithMaxY(post, 1.3125), "no-up wall post tops at 21/16");
        assertFalse(post.stream().anyMatch(b -> b.maxY() > 1.3125 + 1.0E-9),
                "nothing may exceed the no-up post height");

        List<CollisionShapes.Box> up = CollisionShapes.wallBoxes(true, "NONE", "NONE", "NONE", "NONE");
        assertTrue(hasBoxWithMaxY(up, 1.5), "up post tops at 1.5");

        // LOW arms are jumpable in vanilla (0.9375), never 1.5 - but the
        // post still tops at 1.3125, so scope the check to the north arm box.
        List<CollisionShapes.Box> lowArm = CollisionShapes.wallBoxes(false, "LOW", "NONE", "NONE", "NONE");
        CollisionShapes.Box arm = lowArm.stream()
                .filter(b -> Math.abs(b.maxZ() - 0.3125) < 1.0E-9)
                .findFirst().orElseThrow();
        assertEquals(0.9375, arm.maxY(), 1.0E-9, "LOW wall arm tops at 15/16");
        assertTrue(hasBoxWithMaxY(lowArm, 1.3125), "no-up post still tops at 21/16");

        // TALL arms reach 1.5 like fence arms.
        List<CollisionShapes.Box> tallArm = CollisionShapes.wallBoxes(false, "TALL", "NONE", "NONE", "NONE");
        assertTrue(hasBoxWithMaxY(tallArm, 1.5));

        assertEquals(0.9375, CollisionShapes.armHeight("low"), 1.0E-9);
        assertEquals(1.5, CollisionShapes.armHeight("TALL"), 1.0E-9);
        assertEquals(0.0, CollisionShapes.armHeight("none"), 1.0E-9);
        assertEquals(0.0, CollisionShapes.armHeight(null), 1.0E-9);
    }

    @Test
    void fencesStayConnectedAndGatesOpen() {
        List<CollisionShapes.Box> loneFence = CollisionShapes.fenceBoxes(false, false, false, false);
        assertEquals(1, loneFence.size(), "lone fence is just the post");

        List<CollisionShapes.Box> row = CollisionShapes.fenceBoxes(true, true, false, false);
        assertEquals(3, row.size(), "connected fence carries two arms + post");
        assertTrue(row.stream().allMatch(b -> b.maxY() <= 1.5 + 1.0E-9));

        assertTrue(CollisionShapes.fenceGateBoxes(true, "NORTH").isEmpty(), "open gate has no collision");
        assertTrue(hasBoxWithMaxY(CollisionShapes.fenceGateBoxes(false, "NORTH"), 1.5));
    }

    @Test
    void slabsAndTrapdoorsResolveByHalf() {
        assertTrue(hasBoxWithMaxY(CollisionShapes.slabBoxes("BOTTOM"), 0.5));
        assertTrue(CollisionShapes.slabBoxes("TOP").get(0).minY() >= 0.5 - 1.0E-9);
        assertEquals(1.0, CollisionShapes.slabBoxes("DOUBLE").get(0).maxY(), 1.0E-9);
        // Unknown type data: the both-halves fallback keeps the block
        // collidable somewhere instead of vanishing.
        assertEquals(2, CollisionShapes.slabBoxes(null).size());

        CollisionShapes.Box closedBottom = CollisionShapes.trapdoorBoxes(false, false, "NORTH").get(0);
        assertEquals(0.1875, closedBottom.maxY(), 1.0E-9);
        CollisionShapes.Box closedTop = CollisionShapes.trapdoorBoxes(true, false, "NORTH").get(0);
        assertEquals(0.8125, closedTop.minY(), 1.0E-9);
        CollisionShapes.Box open = CollisionShapes.trapdoorBoxes(false, true, "NORTH").get(0);
        assertEquals(0.1875, open.maxZ(), 1.0E-9, "open north trapdoor parks the plate on the north edge");
    }

    @Test
    void pistonHeadsAreStandable() {
        // PISTON_HEAD contains "HEAD" but must not inherit the skull box:
        // an up-facing extended head must present a full-height plate on top.
        assertEquals(CollisionShapes.Family.PISTON_HEAD, CollisionShapes.shapeFamily("PISTON_HEAD"));
        CollisionShapes.Box plate = CollisionShapes.pistonHeadBoxes("UP").stream()
                .filter(b -> b.maxY() > 0.99).findFirst().orElseThrow();
        assertEquals(1.0, plate.maxY(), 1.0E-9, "up-facing head plate tops the block");
        assertEquals(0.0, plate.minX(), 1.0E-9);
        assertEquals(1.0, plate.maxX(), 1.0E-9, "plate spans the full footprint");

        CollisionShapes.Box northPlate = CollisionShapes.pistonHeadBoxes("NORTH").stream()
                .filter(b -> Math.abs(b.maxZ() - 1.0) < 1.0E-9).findFirst().orElseThrow();
        assertEquals(0.75, northPlate.minZ(), 1.0E-9, "north-facing plate sits at the far end");
    }
}
