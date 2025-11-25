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

@CheckData(order = 'D', type = CheckType.AIM)
@SuppressWarnings("unused")
public final class AimD extends Check {

    private final CheckBuffer buffer = new CheckBuffer(8.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper movePacketWrapper) {
        if (!movePacketWrapper.isRotationUpdate())
            return;
        final PlayerData playerData = Truthful.getInstance().getDataManager()
                .getPlayerData(movePacketWrapper.getPlayer());

        if (playerData == null)
            return;

        final float deltaYaw = Math.abs(playerData.getDeltaYaw());
        final float deltaPitch = Math.abs(playerData.getDeltaPitch());
        final float yawAccel = Math.abs(deltaYaw - Math.abs(playerData.getLastDeltaYaw()));
        final float pitchAccel = Math.abs(deltaPitch - Math.abs(playerData.getLastDeltaPitch()));
        final Player player = movePacketWrapper.getPlayer();

        // Check for "Perfect" acceleration (Smooth Aim)
        // If acceleration is extremely low (constant speed) while moving significantly.
        if (deltaYaw > 1.0F && deltaPitch > 1.0F && yawAccel < 1.0E-3F && pitchAccel < 1.0E-3F) {
            if (buffer.increase(player, 1.0) > 8.0) {
                flag(playerData, "Perfect rotation acceleration (Smooth Aim)");
                buffer.reset(player, 4.0);
            }
        } else {
            buffer.decrease(player, 1.5);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}
