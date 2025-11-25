package ret.tawny.truthful.checks.impl.movement.timer;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@CheckData(order = 'A', type = CheckType.TIMER)
@SuppressWarnings("unused")
public final class TimerA extends Check {

    private final Map<UUID, Long> lastPacketTime = new ConcurrentHashMap<>();
    private final Map<UUID, Double> balanceMap = new ConcurrentHashMap<>();

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null)
            return;

        if (data.getTicksTracked() < 100 || data.isTeleportTick()) {
            lastPacketTime.put(player.getUniqueId(), System.nanoTime());
            return;
        }

        long now = System.nanoTime();
        long last = lastPacketTime.getOrDefault(player.getUniqueId(), now);

        long diffNano = now - last;
        double diffMs = diffNano / 1_000_000.0;

        // FIX 1: Detect Lag Spikes (Server or Client freeze)
        // If gap is > 750ms, assume lag spike and do not penalize balance
        if (diffMs > 750.0) {
            balanceMap.put(player.getUniqueId(), -50.0); // Reset balance
            lastPacketTime.put(player.getUniqueId(), now);
            return;
        }

        double balance = balanceMap.getOrDefault(player.getUniqueId(), -50.0);

        balance += 50.0; // Expected 50ms per tick
        balance -= diffMs; // Subtract actual time

        // Clamp negative balance (catching up)
        if (balance < -500.0) {
            balance = -500.0;
        }

        // FIX 2: Increased threshold from 100.0 to 200.0 (Relaxed)
        if (balance > 200.0) {
            if (data.getTicksTracked() > 150) {
                flag(data, String.format("Game speed too fast. Balance: +%.2fms", balance));
                // Hard reset to avoid spamming
                balance = 0.0;
            }
        }

        balanceMap.put(player.getUniqueId(), balance);
        lastPacketTime.put(player.getUniqueId(), now);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        lastPacketTime.remove(event.getPlayer().getUniqueId());
        balanceMap.remove(event.getPlayer().getUniqueId());
    }
}