package ret.tawny.truthful.checks.impl.world.scaffold;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.math.MathHelper;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@CheckData(order = 'G', type = CheckType.SCAFFOLD)
@SuppressWarnings("unused")
public final class ScaffoldG extends Check {

    private final Map<UUID, PlayerScaffoldData> scaffoldDataMap = new ConcurrentHashMap<>();

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        if (!relMovePacketWrapper.isRotationUpdate()) return;

        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null || data.isTeleportTick()) return;

        // Only check if looking down (Bridging)
        // Pitch 90 is straight down. Pitch 0 is forward.
        if (data.getPitch() < 60.0) return;

        // Only check significant snaps. Tiny movements are often too noisy.
        if (Math.abs(data.getDeltaYaw()) < 5.0) return;

        PlayerScaffoldData scaffoldData = scaffoldDataMap.computeIfAbsent(player.getUniqueId(), id -> new PlayerScaffoldData());

        float deltaYaw = Math.abs(data.getDeltaYaw());
        float lastDeltaYaw = Math.abs(data.getLastDeltaYaw());

        long gcdYaw = MathHelper.gcd((long) (deltaYaw * 16777216.0), (long) (lastDeltaYaw * 16777216.0));

        // --- FIX: Sensitivity / Smoothing Compatibility ---
        // Values like 64, 128, 256, etc. appear when players use mouse smoothing or specific gaming mice.
        // These values are mathematically "off-grid" but consistent with hardware smoothing.
        // Valid 'Bot' rotations often return 1 or very close to 0 relative to the multiplier.
        // We lowered the threshold to 40 to filter out these smoothed inputs.
        if (gcdYaw < 40) {
            scaffoldData.violations++;
            // Require a streak of failures to confirm robotic movement
            if (scaffoldData.violations > 10) {
                flag(data, String.format("Rotation GCD failure. YawGCD: %d", gcdYaw));
                scaffoldData.violations = 0;
            }
        } else {
            // Instant forgiveness for valid input
            scaffoldData.violations = 0;
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        scaffoldDataMap.remove(event.getPlayer().getUniqueId());
    }

    private static class PlayerScaffoldData {
        private int violations = 0;
    }
}