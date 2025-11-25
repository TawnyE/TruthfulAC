package ret.tawny.truthful.checks.impl.combat.autoclicker;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.entity.Player;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;

@CheckData(order = 'B', type = CheckType.AUTOCLICKER)
@SuppressWarnings("unused")
public final class AutoClickerB extends Check {

    private final CheckBuffer buffer = new CheckBuffer(5.0);
    private long lastClickTime;
    private int consistentTicks;
    private long lastDelay;

    @Override
    public void handlePacketPlayerReceive(final PacketEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ARM_ANIMATION) {
            final Player player = event.getPlayer();
            final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

            if (data == null)
                return;

            long now = System.currentTimeMillis();
            if (lastClickTime != 0) {
                long delay = now - lastClickTime;

                // Logic: 0 Variance / Perfect Consistency
                // If the delay is identical to the previous delay, we increment a counter.
                // Humans can hit the same ms delay occasionally, but not consistently.

                if (delay == lastDelay && delay < 250) {
                    if (++consistentTicks > 4) {
                        if (buffer.increase(player, 1.0) > 4.0) {
                            flag(data, "Perfect Click Consistency (Macro)");
                            buffer.reset(player, 2.0);
                        }
                    }
                } else {
                    consistentTicks = 0;
                    buffer.decrease(player, 0.25);
                }
                lastDelay = delay;
            }
            lastClickTime = now;
        }
    }
}
