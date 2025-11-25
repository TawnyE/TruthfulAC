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

@CheckData(order = 'D', type = CheckType.ELYTRA)
@SuppressWarnings("unused")
public final class ElytraD extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private double lastEnergy = 0.0;

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null) return;

        if (!player.isGliding()) {
            lastEnergy = 0.0;
            return;
        }

        // 1. Must wear Elytra
        ItemStack chest = player.getInventory().getChestplate();
        if (chest == null || chest.getType() != Material.ELYTRA) {
            lastEnergy = 0.0;
            return;
        }

        if (player.isInsideVehicle()) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        try { if (player.isRiptiding()) return; } catch (Throwable ignored) {}

        if (data.getTicksTracked() - data.getLastFireworkTick() < 80) {
            lastEnergy = 0.0;
            return;
        }

        // --- FIX: HIGH ASCENT EXEMPTION ---
        // During a massive swoop (Y > 0.3 per tick), potential energy gains calculate
        // wildly differently between client and server due to sync.
        // We skip the check during the "Zoom" phase of the swoop.
        if (data.getDeltaY() > 0.3) {
            buffer.decrease(player, 0.5);
            updateEnergyState(player, data);
            return;
        }

        // Deceleration Exemption
        double acceleration = data.getDeltaXZ() - data.getLastDeltaXZ();
        if (acceleration < -0.01) {
            buffer.decrease(player, 0.25);
            updateEnergyState(player, data);
            return;
        }

        // --- ENERGY MATH ---
        double velSq = Math.pow(data.getDeltaX(), 2) + Math.pow(data.getDeltaY(), 2) + Math.pow(data.getDeltaZ(), 2);
        double heightEnergy = player.getLocation().getY() * 2.0;

        double currentEnergy = heightEnergy + velSq;

        if (lastEnergy > 0.0) {
            double diff = currentEnergy - lastEnergy;

            if (diff > 0.75) { // Increased threshold slightly
                if (data.getDeltaXZ() > 0.3) {
                    if (buffer.increase(player, 1.0) > 10.0) {
                        flag(data, String.format("Energy Gain. Diff: +%.4f", diff));
                        buffer.reset(player, 5.0);
                    }
                }
            } else {
                buffer.decrease(player, 0.25);
            }
        }

        this.lastEnergy = currentEnergy;
    }

    private void updateEnergyState(Player player, PlayerData data) {
        double velSq = Math.pow(data.getDeltaX(), 2) + Math.pow(data.getDeltaY(), 2) + Math.pow(data.getDeltaZ(), 2);
        this.lastEnergy = (player.getLocation().getY() * 2.0) + velSq;
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        lastEnergy = 0.0;
    }
}