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


@CheckData(order = 'B', type = CheckType.BEDROCK, displayName = "BFly(A)")
public final class BFlyA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

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

        // --- EXEMPTIONS ---
        if (data.getGameMode() == GameMode.CREATIVE || data.isAllowFlight() ||
                data.isFlying() || data.isGliding() || data.isInsideVehicle()) {
            return;
        }

        // Resync & Lag
        if (data.isServerFrozen() || data.isTeleportTick() || data.getTicksSinceTeleport() < 20) {
            buffer.decrease(player, 0.5);
            return;
        }

        // Environment
        if (data.isExempt(ExemptionType.VELOCITY) || data.hasVelocity() ||
                data.isExempt(ExemptionType.CLIMBABLE) || data.isInLiquid() ||
                data.isInWeb() || data.isExempt(ExemptionType.RIPTIDE) ||
                data.isExempt(ExemptionType.WIND_CHARGE)) {
            buffer.decrease(player, 0.5);
            return;
        }

        // Levitation allows defying gravity naturally
        if (PhysicsUtils.hasEffect(player, PotionEffectType.LEVITATION)) {
            buffer.decrease(player, 0.5);
            return;
        }

        // --- LOGIC ---
        // Only check if strictly in the air (server-side)
        if (!data.isServerGround() && !data.isLastGround()) {

            double deltaY = data.getDeltaY();
            int airTicks = data.getAirTicks();

            // Check: Rising or Hovering while airborne
            if (deltaY >= 0.0) {

                // Allow initial jump ticks (0-15 ticks approx for jump apex)
                // Bedrock jumps can seem longer due to lag, so we give 20 ticks (1 second).
                int limit = 20;

                // Jump Boost extends air time slightly
                if (PhysicsUtils.getPotionLevel(player, PotionEffectType.JUMP_BOOST) > 0) {
                    limit += 10;
                }

                if (airTicks > limit) {
                    // They have been in the air for > 1 second and are still rising or hovering.
                    // This is impossible without flight/levitation.

                    if (buffer.increase(player, 1.0) > 6.0) {
                        flag(data, String.format("Bedrock Fly (Hover/Rise). AirTicks: %d, Y: %.4f", airTicks, deltaY));
                        buffer.reset(player, 3.0);
                    }
                } else {
                    // Still within valid jump window
                    buffer.decrease(player, 0.1);
                }
            } else {
                // Falling naturally
                buffer.decrease(player, 0.25);
            }
        } else {
            // On ground
            buffer.decrease(player, 0.5);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}
