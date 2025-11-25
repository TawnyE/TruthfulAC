package ret.tawny.truthful.checks.impl.combat.aim;

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

@CheckData(order = 'B', type = CheckType.AIM)
@SuppressWarnings("unused")
public final class AimB extends Check {

    private final CheckBuffer buffer = new CheckBuffer(5.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null)
            return;

        // Cinematic/Snap Check
        // Detects unnatural "snaps" (instant large rotation) followed by perfect
        // stillness or vice versa.

        float deltaYaw = Math.abs(data.getDeltaYaw());
        float lastDeltaYaw = Math.abs(data.getLastDeltaYaw());

        // Condition 1: Still -> Snap (Aimbot locking on)
        boolean snapStart = deltaYaw > 20.0 && lastDeltaYaw < 1.0;

        // Condition 2: Snap -> Still (Aimbot locking off or snapping to target then
        // stopping)
        boolean snapEnd = lastDeltaYaw > 20.0 && deltaYaw < 1.0;

        if (snapStart || snapEnd) {
            // We need to be careful about legitimate mouse flicks.
            // Usually legitimate flicks have *some* acceleration/deceleration frames, even
            // if small.
            // Instant 0 -> 20 -> 0 is very suspicious.

            if (buffer.increase(player, 1.0) > 5.0) {
                flag(data, String.format("Snap Rotation. Y: %.1f -> %.1f", lastDeltaYaw, deltaYaw));
                buffer.reset(player, 2.0);
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
