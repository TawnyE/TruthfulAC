package ret.tawny.truthful.checks.impl.movement.elytra;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'L', type = CheckType.ELYTRA)
@SuppressWarnings("unused")
public final class ElytraL extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null)
            return;

        if (!player.isGliding() || player.isInsideVehicle())
            return;
        try {
            if (player.isRiptiding())
                return;
        } catch (Throwable ignored) {
        }

        if (data.getTicksTracked() - data.getLastFireworkTick() < 60)
            return;

        // --- FIX: WEB EXEMPTION ---
        if (data.isInWeb() || (data.getTicksTracked() - data.getLastWebTick() < 20)) {
            buffer.decrease(player, 0.5);
            return;
        }

        double deltaY = data.getDeltaY();
        float pitch = data.getPitch();

        // Logic: Pitch Mismatch
        // If looking down (pitch > 10) but going up (deltaY > 0), that's impossible.
        if (pitch > 10.0 && deltaY > 0.1) {
            if (buffer.increase(player, 1.0) > 10.0) {
                flag(data, String.format("Pitch Mismatch. Pitch: %.1f, Y: %.4f", pitch, deltaY));
                if (Truthful.getInstance().getConfiguration().isLagbacks()) {
                    player.teleport(data.getLastLocation());
                }
                buffer.reset(player, 5.0);
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
