package ret.tawny.truthful.checks.impl.combat.autoclicker;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.math.MathHelper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@CheckData(order = 'E', type = CheckType.AUTOCLICKER)
public final class AutoClickerE extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private final Map<UUID, AutoClickerData> dataMap = new ConcurrentHashMap<>();

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
            final Player player = (Player) event.getPlayer();
            if (Truthful.getInstance().isBedrockPlayer(player)) return;

            final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
            if (data == null || data.isServerFrozen() || data.isExempt() || data.getActionTracker().isDigging()) return;
            if (!Truthful.getInstance().getConfiguration().shouldCountGroundPunches()
                    && data.getTicksTracked() - data.getLastAttackPacketTick() > 2) return;

            long now = System.nanoTime() / 1_000_000L; // monotonic; GCD stats die under a 15.6ms wall clock
            AutoClickerData acData = dataMap.computeIfAbsent(player.getUniqueId(), k -> new AutoClickerData());

            if (acData.lastClick != 0) {
                long delay = now - acData.lastClick;

                if (delay < 10) return;

                if (delay <= 250) {
                    acData.delays.addLast(delay);

                    if (acData.delays.size() > 15) {
                        acData.delays.pollFirst();
                    }

                    if (acData.delays.size() == 15) {
                        checkGCD(player, data, new ArrayList<>(acData.delays));
                    }
                } else {
                    acData.delays.clear();
                }
            }
            acData.lastClick = now;
        }
    }

    private void checkGCD(Player player, PlayerData data, ArrayList<Long> list) {
        long gcd = 0;
        for (long delay : list) {
            gcd = MathHelper.gcd(gcd, delay);
        }

        if (gcd > 15) {
            if (buffer.increase(player, 1.5) > 6.0) {
                flag(data, "Click Pattern (GCD). Grid Lock: " + gcd + "ms");
                buffer.reset(player, 5.0);
            }
        } else {
            buffer.decrease(player, 0.4);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        dataMap.remove(uuid);
        buffer.remove(event.getPlayer());
    }

    private static class AutoClickerData {
        long lastClick = 0;
        final Deque<Long> delays = new ArrayDeque<>();
    }
}
