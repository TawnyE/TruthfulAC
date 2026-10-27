package ret.tawny.truthful.checks.impl.bedrock;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.compensation.CompensationTracker;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.bedrock.BedrockUtils;
import ret.tawny.truthful.utils.hitbox.SimpleHitbox;


@CheckData(order = 'C', type = CheckType.BEDROCK, displayName = "BReach(A)")
public final class BReachA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public boolean rejectsPackets() { return true; }

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;

        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;

        final Player player = (Player) event.getPlayer();

        // 1. Target Selector: ONLY run for Bedrock players
        if (!BedrockUtils.isBedrock(player)) {
            return;
        }

        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.getGameMode() == GameMode.CREATIVE) return;

        // --- ASYNC EXECUTION ---
        CompensationTracker tracker = Truthful.getInstance().getCompensationTracker();
        if (tracker == null) return;

        // Look up entity by ID (Thread-Safe)
        int entityId = wrapper.getEntityId();
        CompensationTracker.CompensatedEntity comp = tracker.getEntityData(entityId);

        if (comp == null) {
            // Entity not tracked (likely dead or too far/unloaded)
            return;
        }

        long ping = data.getPing();
        int tickDelay = (int) Math.ceil(ping / 50.0);

        // Bedrock packets can be very jittery, check a wider window of history
        int[] ticks = { tickDelay, tickDelay - 1, tickDelay + 1, tickDelay - 2, tickDelay + 2 };
        // Same Bukkit.getCurrentTick crash as KillAuraE had (missing before
        // 1.21). The tracker's own clock is what the snapshots use anyway.
        final int currentTick = tracker.getCurrentTick();

        // Calculate Eye Position from PlayerData
        double eyeHeight = data.getEyeHeight(false, data.isSneaking(), data.isSwimming());
        Vector eyePos = new Vector(data.getX(), data.getY() + eyeHeight, data.getZ());

        // Limits
        double baseLimit = BedrockUtils.MAX_REACH; // 4.5

        // Add ping buffer (0.003 blocks per ms)
        double maxReach = baseLimit + (ping * 0.003);

        // Add movement buffer (sprinting expands reach logic slightly due to relative velocity)
        if (data.isSprinting() || data.getDeltaXZ() > 0.22) {
            maxReach += 0.4;
        }

        double minDistance = Double.MAX_VALUE;

        // Check against tracking history.
        // getHitboxAt is synchronized internally; avoid extending lock duration while
        // performing per-tick distance calculations.
        for (int t : ticks) {
            SimpleHitbox box = comp.getHitboxAt(t, currentTick);
            if (box == null) continue;

            // Expand hitbox generously for Bedrock (0.4 expansion)
            // This accounts for "fat fingers" on touch screens and protocol desync.
            box.expand(0.4);

            double dist = getDistanceToBox(box, eyePos);
            if (dist < minDistance) {
                minDistance = dist;
            }
        }

        // Evaluation
        if (minDistance == Double.MAX_VALUE) {
            return; // Could not find valid history
        }

        if (minDistance > maxReach) {
            double over = minDistance - maxReach;

            if (minDistance > 10.0) return; // Ignore massive desyncs

            // Increase buffer.
            // We use a high multiplier because hitting from > 5 blocks is blatant.
            if (buffer.increase(player, 1.0 + over) > 15.0) {
                flag(data, String.format("Bedrock Reach. Dist: %.2f, Max: %.2f", minDistance, maxReach));

                // Cancel the hit (lagback is annoying for reach, cancelling is better).
                // Honors the per-check "cancel" switch - observe mode lets the
                // hit through but still flags.
                if (mayCancelPackets()) event.setCancelled(true);
                buffer.reset(player, 4.0);
            }
        } else {
            buffer.decrease(player, 0.25);
        }
    }

    /**
     * Calculates the closest distance from a point to the hitbox.
     * Logic: If inside, distance is 0. Else, distance to nearest edge.
     */
    private double getDistanceToBox(SimpleHitbox box, Vector origin) {
        double dx = Math.max(Math.max(box.minX - origin.getX(), 0), origin.getX() - box.maxX);
        double dy = Math.max(Math.max(box.minY - origin.getY(), 0), origin.getY() - box.maxY);
        double dz = Math.max(Math.max(box.minZ - origin.getZ(), 0), origin.getZ() - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}
