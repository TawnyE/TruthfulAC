package ret.tawny.truthful.debug.logging;

import org.bukkit.entity.Player;
import ret.tawny.truthful.Truthful;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DebugLoggingManager {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Map<UUID, Boolean> loggingPlayers = new ConcurrentHashMap<>();
    private final File logDir;

    public DebugLoggingManager() {
        this.logDir = new File(Truthful.getInstance().getPlugin().getDataFolder(), "debug_logs");
        if (!this.logDir.exists()) {
            this.logDir.mkdirs();
        }
    }

    public boolean isLogging(Player player) {
        return loggingPlayers.containsKey(player.getUniqueId());
    }

    public void toggleLogging(Player admin, Player target) {
        UUID targetUUID = target.getUniqueId();
        if (isLogging(target)) {
            loggingPlayers.remove(targetUUID);
            admin.sendMessage("§8[§bTruthful§8] §cStopped debug logging for " + target.getName());
        } else {
            loggingPlayers.put(targetUUID, true);
            admin.sendMessage("§8[§bTruthful§8] §aStarted debug logging for " + target.getName() + " to " + target.getName() + ".log");
        }
    }

    public void stopLogging(UUID uuid) {
        loggingPlayers.remove(uuid);
    }

    /**
     * Called from the packet thread; the actual write is queued onto the async
     * pool so flags never stall the pipeline on file I/O.
     */
    public void logFlag(Player player, String checkName, int vl, String debug) {
        if (!isLogging(player)) return;

        final File logFile = new File(logDir, player.getName() + ".log");
        final String line = "[" + LocalDateTime.now().format(DATE_FORMAT) + "] "
                + checkName + " (VL: " + vl + ") | " + debug;

        Truthful.getInstance().getServerScheduler().runAsync(() -> {
            try (PrintWriter out = new PrintWriter(new FileWriter(logFile, true))) {
                out.println(line);
            } catch (IOException e) {
                Truthful.getInstance().getPlugin().getLogger().warning(
                        "Failed to write debug log for " + player.getName() + ": " + e.getMessage());
            }
        });
    }
}
