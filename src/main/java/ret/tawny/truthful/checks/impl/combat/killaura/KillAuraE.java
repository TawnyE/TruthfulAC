package ret.tawny.truthful.checks.impl.combat.killaura;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.compensation.CompensationTracker;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.hitbox.SimpleHitbox;
import ret.tawny.truthful.utils.world.BlockPropertyRegistry;

@CheckData(order = 'E', type = CheckType.KILLAURA)
public final class KillAuraE extends Check {

    private static final double STEP = 0.2;
    private static final double MAX_RAY_LENGTH = 6.0;

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }

        WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
        if (interact.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            return;
        }

        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData((Player) event.getPlayer());
        if (data == null || data.isServerFrozen() || data.shouldSkipChecks() || Truthful.getInstance().isBedrockPlayer((Player) event.getPlayer())) {
            return;
        }

        if (data.isInsideVehicle() || data.isTeleportTick() || data.getTicksSinceTeleport() < 5
                || data.hasVelocity() || data.isExempt(ret.tawny.truthful.data.ExemptionType.VELOCITY)) {
            return;
        }

        CompensationTracker tracker = Truthful.getInstance().getCompensationTracker();
        if (tracker == null) {
            return;
        }

        CompensationTracker.CompensatedEntity target = tracker.getEntityData(interact.getEntityId());
        if (target == null) {
            return;
        }

        long ping = data.getPing();
        // One-way latency = RTT/2. Using the full RTT rewinds ~2x too far,
        // which puts legit targets' stale hitboxes behind walls they have
        // already left and turns those corners into false wall-hits.
        int tickDelay = (int) Math.ceil(ping / 100.0);

        // Extreme jitter introduces too much uncertainty for wall checks.
        if (tickDelay > 20 || ping > 1000L) {
            return;
        }

        // Compensation clock, not Bukkit's. Bukkit.getCurrentTick() doesn't even
        // exist before 1.21 (crashed this check on 1.20), and the snapshots are
        // stamped with the tracker's ticks anyway.
        final int currentTick = tracker.getCurrentTick();
        SimpleHitbox hitbox = target.getHitboxAt(tickDelay, currentTick);
        if (hitbox == null) {
            return;
        }

        final double eyeHeight = data.getEyeHeight(false, data.isSneaking(), data.isSwimming());
        final double originX = data.getX();
        final double originY = data.getY() + eyeHeight;
        final double originZ = data.getZ();

        final double[] direction = computeDirection(data.getYaw(), data.getPitch());
        final double dirX = direction[0];
        final double dirY = direction[1];
        final double dirZ = direction[2];

        final double distance = distanceToBox(originX, originY, originZ, hitbox);
        if (distance < 1.0 || distance > MAX_RAY_LENGTH) {
            return;
        }

        boolean blocked = rayIntersectsSolid(originX, originY, originZ, dirX, dirY, dirZ,
                Math.min(distance, MAX_RAY_LENGTH), data);

        if (blocked) {
            if (buffer.increase(data.getPlayer(), 1.0) > 5.0) {
                flag(data, "Wall Hit (Async Raytrace)");
            }
        } else {
            buffer.decrease(data.getPlayer(), 0.05);
        }
    }

    private static double[] computeDirection(float yaw, float pitch) {
        final double yawRad = Math.toRadians(yaw);
        final double pitchRad = Math.toRadians(pitch);
        final double cosPitch = Math.cos(pitchRad);

        return new double[] {
                -Math.sin(yawRad) * cosPitch,
                -Math.sin(pitchRad),
                Math.cos(yawRad) * cosPitch
        };
    }

    private static boolean rayIntersectsSolid(double ox, double oy, double oz,
                                              double dx, double dy, double dz,
                                              double maxDistance, PlayerData data) {
        // Start at 0.05 and step 0.25: the old 1.0 start / 0.4 step never saw
        // blocks within ~1 block of the eye or thinner than ~0.4 along the
        // ray, which is exactly the wall-hit case this check exists for.
        for (double d = 0.05D; d <= maxDistance; d += 0.25D) {
            final int bx = floor(ox + (dx * d));
            final int by = floor(oy + (dy * d));
            final int bz = floor(oz + (dz * d));

            var state = data.getWorldCache().getBlockState(bx, by, bz);
            if (BlockPropertyRegistry.isSolid(state)) {
                String name = state.getType().getName().toUpperCase();
                if (!isPartialBlock(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isPartialBlock(String name) {
        return name.contains("SLAB") || name.contains("STAIR") || name.contains("FENCE") ||
               name.contains("WALL") || name.contains("TRAPDOOR") || name.contains("DOOR") ||
               name.contains("GLASS_PANE") || name.contains("IRON_BARS") || name.contains("CHEST") ||
               name.contains("ANVIL") || name.contains("CAULDRON") || name.contains("HOPPER") ||
               name.contains("BED") || name.contains("CARPET") || name.contains("SNOW") ||
               name.contains("FLOWER") || name.contains("GRASS") || name.contains("FERN") ||
               name.contains("LADDER") || name.contains("VINE") || name.contains("SCAFFOLDING") ||
               name.contains("LANTERN") || name.contains("TORCH") || name.contains("SIGN") ||
               name.contains("BANNER") || name.contains("DAYLIGHT_DETECTOR") || name.contains("CAMPFIRE") ||
               name.contains("BELL") || name.contains("END_ROD") || name.contains("CONDUIT") ||
               name.contains("DIRT_PATH") || name.contains("FARMLAND") || name.contains("GRINDSTONE") ||
               name.contains("STONECUTTER") || name.contains("COBWEB") || name.contains("BREWING_STAND") ||
               name.contains("LECTERN") || name.contains("PISTON") || name.contains("REPEATER") ||
               name.contains("COMPARATOR") || name.contains("REDSTONE") || name.contains("RAIL") ||
               name.contains("TRIPWIRE") || name.contains("MUSHROOM") || name.contains("SAPLING") ||
               name.contains("CORAL") || name.contains("POTTED") ||
                name.contains("GLASS") || name.contains("LEAVES") || name.contains("BARS") ||
               name.contains("CHAIN") || name.contains("CANDLE") || name.contains("AMETHYST") ||
               name.contains("POINTED_DRIPSTONE") || name.contains("HANGING") ||
               name.contains("AZALEA") || name.contains("MANGROVE_ROOTS") || name.contains("GLOW_LICHEN") ||
               name.contains("SCULK_VEIN") || name.contains("MOSS_CARPET") || name.contains("SPORE_BLOSSOM") ||
               name.contains("BUTTON") || name.contains("LEVER") || name.contains("PRESSURE") ||
               name.contains("SKULL") || name.contains("HEAD") || name.contains("CAKE") ||
               name.contains("CANDLE") || name.contains("ENCHANTING") || name.contains("DRAGON_EGG") ||
               name.contains("LILY") || name.contains("SEA_PICKLE") || name.contains("TURTLE_EGG");
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    private static double distanceToBox(double x, double y, double z, SimpleHitbox box) {
        double dx = Math.max(Math.max(box.minX - x, 0.0), x - box.maxX);
        double dy = Math.max(Math.max(box.minY - y, 0.0), y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - z, 0.0), z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}
