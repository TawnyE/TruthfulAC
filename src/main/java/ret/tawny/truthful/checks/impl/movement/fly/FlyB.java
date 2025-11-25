package ret.tawny.truthful.checks.impl.movement.fly;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'B', type = CheckType.FLY)
@SuppressWarnings("unused")
public final class FlyB extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null) return;

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (player.getAllowFlight() || player.isFlying() || player.isInsideVehicle()) return;
        if (player.isGliding()) return;

        try { if (player.isRiptiding()) return; } catch (Throwable ignored) {}

        if (data.isInLiquid() || data.isOnClimbable() || data.isInWeb()) {
            buffer.decrease(player, 0.5);
            return;
        }

        if (!data.getVelocities().isEmpty()) {
            buffer.decrease(player, 0.5);
            return;
        }

        // Slime block exemption
        if (data.isLastGround() && data.getLastGroundLocation().getBlock().getType().name().contains("SLIME")) {
            return;
        }

        // Check 1: Jump Height Limit
        if (data.isLastGround() && !data.isOnGround()) {
            double jumpLimit = 0.42f + (getPotionLevel(player, PotionEffectType.JUMP_BOOST) * 0.1);
            jumpLimit += 0.05; // Buffer

            if (data.getDeltaY() > jumpLimit) {
                // Ignore step ups
                boolean isStep = Math.abs(data.getDeltaY() - 0.5) < 0.001 || Math.abs(data.getDeltaY() - 0.6) < 0.001;

                if (!isStep) {
                    if (buffer.increase(player, 1.0) > 10.0) {
                        flag(data, String.format("Abnormal Jump Height. Y: %.4f, Max: %.4f", data.getDeltaY(), jumpLimit));
                    }
                }
            }
            return;
        }

        // Check 2: Mid-Air Ascension
        if (data.getTicksInAir() > 7 && data.getDeltaY() > 0.0) {
            if (player.hasPotionEffect(PotionEffectType.LEVITATION)) return;

            // Threshold > 0.01 prevents flags from tiny bobbing
            if (data.getDeltaY() > 0.01) {
                if (buffer.increase(player, 1.0) > 10.0) {
                    flag(data, "Ascension in mid-air (Jetpack/Fly)");
                }
            }
        } else {
            buffer.decrease(player, 0.25);
        }
    }

    private int getPotionLevel(Player player, PotionEffectType type) {
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType().equals(type))
                return effect.getAmplifier() + 1;
        }
        return 0;
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}