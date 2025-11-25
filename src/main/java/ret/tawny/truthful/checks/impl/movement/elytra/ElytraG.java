package ret.tawny.truthful.checks.impl.movement.elytra;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'G', type = CheckType.ELYTRA)
@SuppressWarnings("unused")
public final class ElytraG extends Check {

    private final CheckBuffer buffer = new CheckBuffer(8.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null) return;

        // FIX 1: Bedrock Exemption (Bedrock handles vectors/angles differently)
        if (Truthful.getInstance().isBedrockPlayer(player)) return;

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        // FIX 2: Strict Item Check
        ItemStack chest = player.getInventory().getChestplate();
        if (chest == null || chest.getType() != Material.ELYTRA) return;

        if (data.isInWeb() || (data.getTicksTracked() - data.getLastWebTick() < 20)) {
            buffer.decrease(player, 0.5);
            return;
        }

        Vector movement = new Vector(data.getDeltaX(), data.getDeltaY(), data.getDeltaZ());
        Vector direction = player.getLocation().getDirection();

        // Stalling threshold
        if (data.getDeltaXZ() < 0.3) {
            buffer.decrease(player, 0.1);
            return;
        }

        if (movement.lengthSquared() < 0.001 || direction.lengthSquared() < 0.001) {
            return;
        }

        float angle = (float) Math.toDegrees(movement.angle(direction));

        if (angle > 75.0) {
            if (buffer.increase(player, 1.0) > 8.0) {
                flag(data, String.format("Elytra Misalignment. Angle: %.1f, Speed: %.2f", angle, data.getDeltaXZ()));

                if (Truthful.getInstance().getConfiguration().isLagbacks()) {
                    player.teleport(data.getLastLocation());
                }
                buffer.reset(player, 4.0);
            }
        } else {
            buffer.decrease(player, 0.5);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}