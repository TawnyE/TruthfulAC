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
import ret.tawny.truthful.utils.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

@CheckData(order = 'A', type = CheckType.AUTOCLICKER)
@SuppressWarnings("unused")
public final class AutoClickerA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private final List<Integer> delays = new ArrayList<>();
    private long lastClickTime;

    @Override
    public void handlePacketPlayerReceive(final PacketEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ARM_ANIMATION) {
            final Player player = event.getPlayer();
            final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

            if (data == null)
                return;
            if (data.isExempt())
                return;

            long now = System.currentTimeMillis();
            if (lastClickTime != 0) {
                int delay = (int) (now - lastClickTime);
                if (delay > 250) { // Reset if pause is too long
                    delays.clear();
                } else {
                    delays.add(delay);
                }
            }
            lastClickTime = now;

            if (delays.size() >= 20) {
                double kurtosis = MathHelper.getKurtosis(delays);
                double skewness = MathHelper.getSkewness(delays);

                // Logic: High Kurtosis means very peaked distribution (consistent clicks).
                // Human clicks are flatter (lower kurtosis).
                // Thresholds need tuning, but > 2.0 is often suspicious for simple
                // autoclickers.

                if (kurtosis > 1.5 && Math.abs(skewness) < 0.5) {
                    if (buffer.increase(player, 1.0) > 5.0) {
                        flag(data, String.format("Consistent Clicks. K: %.2f, S: %.2f", kurtosis, skewness));
                        buffer.reset(player, 2.0);
                    }
                } else {
                    buffer.decrease(player, 0.5);
                }
                delays.clear();
            }
        }
    }
}
