package ret.tawny.truthful;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import ret.tawny.truthful.config.api.Configuration;
import ret.tawny.truthful.listener.BrandListener;

public final class TruthfulPlugin extends JavaPlugin {
    // Volatile: packet threads read this on hot paths while /truthful reload
    // replaces it. A plain field let them keep seeing the old object per JMM.
    private volatile Configuration configuration;

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings()
                .checkForUpdates(false)
                .bStats(true);
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        printBanner();
        log("§7Initializing configuration...");

        this.configuration = new Configuration(this);
        logEnvironment();
        if (!this.configuration.validateOrRepair()) {
            getLogger().severe("Configuration was invalid. A backup was written to config.yml.broken and default config restored.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        PacketEvents.getAPI().init();
        PacketEvents.getAPI().getEventManager().registerListener(new BrandListener());

        // Pre-warm PacketEvents' block-state registry: without this, the
        // lazy mapping load (~220ms of NBT parsing) lands inside the first
        // login chunk build on the main thread (visible as startup lag).
        try {
            com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState
                    .getByString("minecraft:stone");
        } catch (Throwable ignored) {
        }

        log("§7Loading core systems...");

        int pluginId = 28120;
        new Metrics(this, pluginId);

        Truthful.startEngine(this);

        log("§7Hooking into PacketEvents...");
        log("§7Registering checks...");
        log("§aStartup complete. TruthfulAC is now active.");
        printFooter();
    }

    @Override
    public void onDisable() {
        Truthful.stopEngine();
        PacketEvents.getAPI().terminate();
        log("§cTruthfulAC has been disabled.");
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public synchronized void reloadConfiguration() {
        this.reloadConfig();
        final Configuration fresh = new Configuration(this);
        // Startup validated the config; reloads did not, so a bad value only
        // surfaced at some later cold read. Validate the fresh instance before
        // publishing it.
        try {
            if (!fresh.validateOrRepair()) {
                getLogger().warning("Configuration issues detected during reload; defaults were repaired.");
            }
        } catch (Throwable t) {
            getLogger().warning("Configuration validation failed during reload: " + t.getMessage());
        }
        this.configuration = fresh;
    }

    private void log(String message) {
        Bukkit.getConsoleSender().sendMessage("§8[§bTruthfulAC§8] " + message);
    }

    // Prints the exact network stack on every boot. Version-guessing from
    // memory is how disconnect mysteries stay mysteries, so this line ends it.
    private void logEnvironment() {
        String serverVer;
        try {
            serverVer = Bukkit.getBukkitVersion();
        } catch (Throwable t) {
            serverVer = "unknown";
        }
        boolean txnPing;
        try {
            txnPing = getConfiguration() != null && getConfiguration().isTransactionPingEnabled();
        } catch (Throwable t) {
            txnPing = true;
        }
        Bukkit.getConsoleSender().sendMessage("§8[§bTruthfulAC§8] §7Server: §f" + serverVer
                + " §7| PE: §f" + pluginVersion("packetevents")
                + " §7| ViaVersion: §f" + pluginVersion("ViaVersion")
                + " §7| ViaBackwards: §f" + pluginVersion("ViaBackwards")
                + " §7| TxnPing: §f" + (txnPing ? "ON" : "OFF"));
    }

    private static String pluginVersion(String name) {
        try {
            org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
            if (plugin != null) return plugin.getDescription().getVersion();
        } catch (Throwable ignored) {}
        return "not installed";
    }

    private void printBanner() {
        Bukkit.getConsoleSender().sendMessage("§b");
        Bukkit.getConsoleSender().sendMessage("§b  _______       _   _      __       _");
        Bukkit.getConsoleSender().sendMessage("§b |__   __|     | | | |    / _|     | |");
        Bukkit.getConsoleSender().sendMessage("§b    | |_ __ _ _| |_| |__ | |_ _ __ | |");
        Bukkit.getConsoleSender().sendMessage("§b    | | '__| | | __| '_ \\|  _| |  || |");
        Bukkit.getConsoleSender().sendMessage("§b    | | |  | |_| |_| | | | | | |_| | |");
        Bukkit.getConsoleSender().sendMessage("§b    |_|_|   \\__,_|\\__|_| |_|_| \\__,_|_|");
        Bukkit.getConsoleSender().sendMessage("§3           Anti-Cheat Solution v" + getDescription().getVersion());
        Bukkit.getConsoleSender().sendMessage("§b");
    }

    private void printFooter() {
        Bukkit.getConsoleSender().sendMessage("§b");
    }
}