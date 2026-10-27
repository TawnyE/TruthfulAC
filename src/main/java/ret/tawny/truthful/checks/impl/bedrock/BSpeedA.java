package ret.tawny.truthful.checks.impl.bedrock;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.ExemptionType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.bedrock.BedrockUtils;
import ret.tawny.truthful.utils.world.PhysicsUtils;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;


@CheckData(order = 'A', type = CheckType.BEDROCK, displayName = "BSpeed(A)")
public final class BSpeedA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(15.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper wrapper) {
        if (!wrapper.isPositionUpdate()) return;

        final Player player = wrapper.getPlayer();

        // 1. Target Selector: ONLY run for Bedrock players
        if (!BedrockUtils.isBedrock(player)) {
            return;
        }

        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null) return;


        if (data.getGameMode() == GameMode.CREATIVE || data.isAllowFlight() ||
                data.isFlying() || data.isGliding() || data.isInsideVehicle()) {
            return;
        }

        // Bedrock clients suffer heavily from teleport desync, give extra grace
        if (data.isServerFrozen() || data.isTeleportTick() || data.getTicksSinceTeleport() < 20) {
            buffer.decrease(player, 0.5);
            return;
        }

        // External velocity/force exemptions
        if (data.hasVelocity() || data.isExempt(ExemptionType.VELOCITY) ||
                data.isRiptiding() || data.isExempt(ExemptionType.RIPTIDE) ||
                data.isExempt(ExemptionType.ELYTRA_BOOST) ||
                data.isInLiquid() || data.isInWeb()) {
            buffer.decrease(player, 0.5);
            return;
        }


        double deltaXZ = data.getDeltaXZ();

        // Base Limit from BedrockUtils
        double limit = data.isOnGround() ? BedrockUtils.MAX_SPEED_GROUND : BedrockUtils.MAX_SPEED_AIR;

        // Speed Potion Modifier
        // Bedrock speed effect translation is roughly consistent with Java
        int speedLevel = PhysicsUtils.getPotionLevel(player, PotionEffectType.SPEED);
        if (speedLevel > 0) {
            // Add 20% base speed per level + generic buffer
            limit += (speedLevel * 0.07);
        }

        // Ice Modifier
        // If on ice, Bedrock physics can slide significantly faster
        if (data.getLastIceTick() == data.getTicksTracked()) {
            limit += 0.3;
        }

        // --- EVALUATION ---
        if (deltaXZ > limit) {
            // Calculate severity
            double over = deltaXZ - limit;

            // Increase buffer.
            // We use a slightly higher threshold (8.0) because Bedrock packets can "burst"
            // (sending 2 movements in 1 tick essentially), creating a fake speed spike.
            if (buffer.increase(player, 1.0 + over) > 8.0) {
                flag(data, String.format("Bedrock Speed limit. Speed: %.2f, Limit: %.2f", deltaXZ, limit));
                buffer.reset(player, 4.0);
            }
        } else {
            buffer.decrease(player, 0.2);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}
