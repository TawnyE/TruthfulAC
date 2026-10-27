package ret.tawny.truthful.checks.impl.packet.sprint;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'A', type = CheckType.SPRINT)
public final class SprintA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper wrapper) {
        if (!wrapper.isPositionUpdate()) return;

        final Player player = wrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null) return;

        if (!data.isSprinting()) {
            buffer.decrease(player, 0.5);
            return;
        }

        if (data.isAllowFlight()) return;

        // 1. Blindness Check
        if (data.hasPotionEffect(PotionEffectType.BLINDNESS)) {
            if (buffer.increase(player, 2.0) > 4.0) {
                flag(data, "OmniSprint (Blindness)");
                buffer.reset(player, 2.0);
            }
            return;
        }

        // 2. Hunger Check
        int foodLevel = data.getFoodLevel();
        if (foodLevel <= 6) {
            if (buffer.increase(player, 1.0) > 5.0) {
                // DETAILED INFO FOR ALERT
                flag(data, String.format("OmniSprint (Hunger: %d)", foodLevel));
                buffer.reset(player, 2.0);
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
