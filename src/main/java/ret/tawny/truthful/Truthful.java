package ret.tawny.truthful;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.geysermc.floodgate.api.FloodgateApi;
import ret.tawny.truthful.checks.registry.CheckRegistry;
import ret.tawny.truthful.commands.impl.CommandManager;
import ret.tawny.truthful.compensation.CompensationTracker;
import ret.tawny.truthful.compensation.Scheduler;
import ret.tawny.truthful.config.api.Configuration;
import ret.tawny.truthful.data.DataManager;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.database.LogManager;
import ret.tawny.truthful.gui.GuiManager;
import ret.tawny.truthful.listener.PacketListener;
import ret.tawny.truthful.listener.PlayerListener;
import ret.tawny.truthful.version.VersionManager;

public final class Truthful {
    private static final Truthful INSTANCE = new Truthful();

    private boolean floodgateSupportEnabled = false;
    private FloodgateApi floodgateApi = null;

    private VersionManager versionManager;
    private CheckRegistry checkManager;
    private DataManager dataManager;
    private Scheduler scheduler;
    private CompensationTracker compensationTracker;
    private PlayerListener playerListener;
    private PacketListener packetListener;
    private LogManager logManager;
    private GuiManager guiManager;
    private Plugin plugin;

    private Truthful() {}

    public void start(final Plugin plugin) {
        this.plugin = plugin;

        if (Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
            try {
                this.floodgateApi = FloodgateApi.getInstance();
                this.floodgateSupportEnabled = true;
                plugin.getLogger().info("Successfully hooked into Floodgate.");
            } catch (Exception e) {
                plugin.getLogger().warning("Found Floodgate, but failed to hook.");
            }
        }

        this.versionManager = new VersionManager();
        this.dataManager = new DataManager();
        this.scheduler = new Scheduler();
        this.logManager = new LogManager(plugin);
        this.guiManager = new GuiManager();

        this.versionManager.load();
        this.compensationTracker = new CompensationTracker();

        plugin.getServer().getPluginCommand("truthful").setExecutor(new CommandManager());

        this.checkManager = new CheckRegistry();
        this.playerListener = new PlayerListener();
        this.packetListener = new PacketListener(this.checkManager);

        this.checkManager.init();

        // --- SYNC TASK LOOP ---
        new BukkitRunnable() {
            @Override
            public void run() {
                // 1. Tick Compensation Tracker
                if (compensationTracker != null) {
                    compensationTracker.tick();
                }

                // 2. Update Thread-Safe Entity Data for Checks
                for (Player player : Bukkit.getOnlinePlayers()) {
                    PlayerData data = dataManager.getPlayerData(player);
                    if (data == null) continue;

                    boolean vehicleNearby = false;
                    boolean entityNearby = false;

                    // Scan for entities in a 1.5 block radius (generous to prevent falses)
                    // Added Entity Type checks to avoid casting unnecessary entities
                    for (Entity entity : player.getNearbyEntities(1.5, 1.5, 1.5)) {
                        if (entity instanceof Vehicle) {
                            vehicleNearby = true;
                        } else if (entity instanceof Player || entity.getType().isAlive()) {
                            // Any other living entity or player
                            entityNearby = true;
                        }
                    }

                    data.setNearVehicle(vehicleNearby);
                    data.setNearEntity(entityNearby);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public void shutdown() {
        if (logManager != null) {
            logManager.shutdown();
        }
    }

    public boolean isBedrockPlayer(Player player) {
        if (!floodgateSupportEnabled || floodgateApi == null || player == null) {
            return false;
        }
        try {
            return floodgateApi.isFloodgatePlayer(player.getUniqueId());
        } catch (Exception e) {
            return false;
        }
    }

    public static Truthful getInstance() { return INSTANCE; }
    public VersionManager getVersionManager() { return this.versionManager; }
    public DataManager getDataManager() { return this.dataManager; }
    public CheckRegistry getCheckManager() { return this.checkManager; }
    public Configuration getConfiguration() { return ((TruthfulPlugin) plugin).getConfiguration(); }
    public Plugin getPlugin() { return plugin; }
    public PlayerListener getPlayerListener() { return playerListener; }
    public Scheduler getScheduler() { return scheduler; }
    public CompensationTracker getCompensationTracker() { return compensationTracker; }
    public LogManager getLogManager() { return logManager; }
    public GuiManager getGuiManager() { return guiManager; }
}