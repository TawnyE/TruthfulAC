package ret.tawny.truthful.managers;

import org.bukkit.Bukkit;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.config.api.Configuration;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class BanwaveManager {

    private final Set<String> queuedPlayers = new LinkedHashSet<>();
    private WrappedTask executionTask;
    private LocalDateTime lastAutoStartTrigger;
    private int sweepPosition;

    public synchronized boolean addPlayer(String playerName) {
        return queuedPlayers.add(playerName);
    }

    public synchronized boolean removePlayer(String playerName) {
        return queuedPlayers.remove(playerName);
    }

    public synchronized void clearQueue() {
        queuedPlayers.clear();
    }

    public synchronized List<String> getQueuedPlayers() {
        return new ArrayList<>(queuedPlayers);
    }

    public synchronized int getQueuedCount() {
        return queuedPlayers.size();
    }

    public synchronized boolean isRunning() {
        return executionTask != null;
    }

    public synchronized boolean start() {
        if (executionTask != null) return false;

        Configuration config = Truthful.getInstance().getConfiguration();
        long intervalTicks = config.getBanwaveIntervalSeconds() * 20L;
        sweepPosition = 0;
        executionTask = Truthful.getInstance().getServerScheduler().runGlobalTimer(() -> {
            String next;
            int position;
            synchronized (BanwaveManager.this) {
                if (queuedPlayers.isEmpty()) {
                    stop();
                    return;
                }
                next = queuedPlayers.iterator().next();
                position = ++sweepPosition;
                queuedPlayers.remove(next);
            }

            // Banwave means BAN. The 'banwave.command' config decides the exact
            // punishment so servers can route to ban plugins if they want.
            final String command = config.getBanwaveCommand().replace("%player%", next);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);

            final String sweepMessage = config.getBanwaveSweepMessage()
                    .replace("%position%", String.valueOf(position))
                    .replace("%player%", next);
            Bukkit.broadcastMessage(sweepMessage);
        }, 1L, intervalTicks);

        return true;
    }

    public synchronized boolean stop() {
        if (executionTask == null) return false;
        executionTask.cancel();
        executionTask = null;
        return true;
    }

    public void tickAutoStart() {
        final Configuration config = Truthful.getInstance().getConfiguration();
        if (!config.isBanwaveEnabled() || !config.isBanwaveAutoStartEnabled()) return;

        final DayOfWeek scheduledDay = config.getBanwaveAutoStartDay();
        final LocalTime scheduledTime = config.getBanwaveAutoStartTime();
        final LocalDateTime now = LocalDateTime.now();

        if (now.getDayOfWeek() != scheduledDay) return;
        if (now.toLocalTime().getHour() != scheduledTime.getHour() || now.toLocalTime().getMinute() != scheduledTime.getMinute()) {
            return;
        }

        synchronized (this) {
            if (lastAutoStartTrigger != null
                    && lastAutoStartTrigger.getYear() == now.getYear()
                    && lastAutoStartTrigger.getDayOfYear() == now.getDayOfYear()
                    && lastAutoStartTrigger.getHour() == now.getHour()
                    && lastAutoStartTrigger.getMinute() == now.getMinute()) {
                return;
            }

            lastAutoStartTrigger = now;
            if (!queuedPlayers.isEmpty() && executionTask == null) {
                start();
                Bukkit.broadcastMessage(config.getBanwaveStartedMessage());
            }
        }
    }

}
