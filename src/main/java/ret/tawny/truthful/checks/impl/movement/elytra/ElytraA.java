package ret.tawny.truthful.checks.impl.movement.elytra;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'A', type = CheckType.ELYTRA)
@SuppressWarnings("unused")
public final class ElytraA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(12.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null) return;

        // --- STRICT EXEMPTIONS ---
        if (!player.isGliding()) return;

        // 1. Must wear Elytra (Fixes "no wings" flags)
        ItemStack chest = player.getInventory().getChestplate();
        if (chest == null || chest.getType() != Material.ELYTRA) return;

        if (player.isInsideVehicle()) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        try { if (player.isRiptiding()) return; } catch (Throwable ignored) {}

        if (data.isInLiquid() || data.isOnClimbable() || data.isInWeb()) return;
        if (data.getTicksTracked() - data.getLastFireworkTick() < 80) return;

        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)) return;

        double deltaY = data.getDeltaY();
        double deltaXZ = data.getDeltaXZ();
        double accel = deltaXZ - data.getLastDeltaXZ();

        // 1. Force Height (Rising)
        if (deltaY >= 0.1) {

            // --- FIX: SWOOP LOGIC ---
            // We allow rising if the player is losing speed (Converting Kinetic to Potential).
            // Previously required < -0.02. Relaxed to < 0.0 (Any deceleration).
            boolean isSwooping = accel < 0.0;

            if (!isSwooping && deltaXZ > 0.2 && data.getTicksInAir() > 20) {
                if (buffer.increase(player, 1.0) > 12.0) {
                    flag(data, String.format("Force Height. Y: %.4f, Speed: %.3f, Accel: %.4f", deltaY, deltaXZ, accel));
                    buffer.reset(player, 6.0);
                }
            } else {
                // Valid swoop, reduce buffer
                buffer.decrease(player, 0.5);
            }
            return;
        }

        // 2. Aerodynamic Drag (Relaxed)
        double absY = Math.abs(deltaY);
        double requiredDrop = (deltaXZ - 0.5) * 0.10;

        if (deltaXZ > 1.0 && deltaY < 0.0) {
            if (absY < requiredDrop) {
                if (buffer.increase(player, 1.0) > 12.0) {
                    flag(data, String.format("Invalid Glide Ratio (Drag). Speed: %.3f, Drop: %.4f, Req: %.4f", deltaXZ, absY, requiredDrop));
                    buffer.reset(player, 6.0);
                }
            } else {
                buffer.decrease(player, 0.25);
            }
        } else {
            buffer.decrease(player, 0.25);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}