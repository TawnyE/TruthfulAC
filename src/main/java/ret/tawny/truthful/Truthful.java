package ret.tawny.truthful;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import ret.tawny.truthful.checks.registry.CheckRegistry;
import ret.tawny.truthful.commands.impl.CommandAlerts;
import ret.tawny.truthful.commands.impl.CommandManager;
import ret.tawny.truthful.commands.impl.CommandBanwave;
import ret.tawny.truthful.compensation.CompensationTracker;
import ret.tawny.truthful.config.api.Configuration;
import ret.tawny.truthful.data.DataManager;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.database.LogManager;
import ret.tawny.truthful.debug.DebugManager;
import ret.tawny.truthful.debug.logging.DebugLoggingManager;
import ret.tawny.truthful.debug.telemetry.TelemetryManager;
import ret.tawny.truthful.gui.GuiManager;
import ret.tawny.truthful.listener.CheckListener;
import ret.tawny.truthful.listener.PlayerListener;
import ret.tawny.truthful.listener.TruthfulPacketListener;
import ret.tawny.truthful.managers.BanwaveManager;
import ret.tawny.truthful.managers.DiscordManager;
import ret.tawny.truthful.task.EnvironmentTask;
import ret.tawny.truthful.utils.bedrock.BedrockUtils;
import ret.tawny.truthful.version.VersionManager;

import java.lang.reflect.Method;

public final class Truthful {
    private static final Truthful INSTANCE = new Truthful();

    private Method getTPSMethod = null;
    private boolean getTPSMethodFetched = false;

    // Folia has no global TPS API; the Paper reflection path fails there and
    // the old fallback returned a constant 20.0 - silently disabling the
    // min-tps kill-switch. Instead, measure the health of the global
    // scheduler itself: the transaction probe timer is scheduled at a fixed
    // tick interval, so the ratio of scheduled to observed wall-time gaps IS
    // the effective tick rate.
    private volatile long lastProbeTimerRunNanos = 0L;
    private volatile double measuredTps = 20.0D;
    // Hot-path TPS: refreshed once per tick by the global timer. Checks used
    // to reflectively invoke Bukkit.getServer().getTPS() dozens of times per
    // movement packet through canCheck -> isExempt -> shouldSkipChecks.
    private volatile double cachedTps = 20.0D;

    private VersionManager versionManager;
    private CheckRegistry checkManager;
    private DataManager dataManager;
    private ret.tawny.truthful.data.world.GlobalWorldCache globalWorldCache;
    private CompensationTracker compensationTracker;
    private PlayerListener playerListener;
    private LogManager logManager;
    private GuiManager guiManager;
    private DebugManager debugManager;
    private DiscordManager discordManager;
    private DebugLoggingManager debugLoggingManager;
    private TelemetryManager telemetryManager;
    private BanwaveManager banwaveManager;
    private Plugin plugin;
    private EnvironmentTask environmentTask;
    private ret.tawny.truthful.utils.ServerScheduler serverScheduler;

    private Truthful() {}

    public void start(final Plugin plugin) {
        this.plugin = plugin;

        this.serverScheduler = new ret.tawny.truthful.utils.ServerScheduler(plugin);

        this.versionManager = new VersionManager();
        this.dataManager = new DataManager();
        this.globalWorldCache = new ret.tawny.truthful.data.world.GlobalWorldCache();
        this.logManager = new LogManager(plugin);
        this.guiManager = new GuiManager();
        this.debugManager = new DebugManager();
        this.discordManager = new DiscordManager();
        this.debugLoggingManager = new DebugLoggingManager();
        this.telemetryManager = new TelemetryManager();
        this.banwaveManager = new BanwaveManager();

        this.versionManager.load();
        this.compensationTracker = new CompensationTracker();

        CommandManager commandManager = new CommandManager();
        plugin.getServer().getPluginCommand("truthful").setExecutor(commandManager);
        plugin.getServer().getPluginCommand("truthful").setTabCompleter(commandManager);
        plugin.getServer().getPluginCommand("alerts").setExecutor(new CommandAlerts());
        plugin.getServer().getPluginCommand("banwave").setExecutor(new CommandBanwave());

        this.checkManager = new CheckRegistry();
        this.playerListener = new PlayerListener();
        new TruthfulPacketListener(this.checkManager);
        new CheckListener();

        this.checkManager.init();

        this.environmentTask = new EnvironmentTask();
        long envInterval = getConfiguration().getEnvironmentScanIntervalTicks();
        this.serverScheduler.runGlobalTimer(this.environmentTask, envInterval, envInterval);

        this.serverScheduler.runGlobalLater(() -> {
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                for (Entity ent : world.getEntities()) {
                    this.compensationTracker.handleEntitySnapshot(ent);
                }
            }
        }, 10L);

        int resetMinutes = getConfiguration().getViolationResetInterval();
        if (resetMinutes > 0) {
            long ticks = (long) resetMinutes * 60 * 20L;
            this.serverScheduler.runGlobalTimer(() -> {
                try {
                    checkManager.resetAllViolations();
                    for (PlayerData data : dataManager.getCollection()) {
                        data.resetTotalViolations();
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("[Truthful] Violation reset task error: " + t.getMessage());
                }
            }, ticks, ticks);
        }

        this.serverScheduler.runGlobalTimer(() -> {
            try {
                // One reflective TPS read per tick, shared by every check.
                refreshTpsCache();
                if (compensationTracker != null) {
                    compensationTracker.tick();
                }
                banwaveManager.tickAutoStart();
                // Exemptions decay once per SERVER TICK, not per packet -
                // packet-counted windows are stretchable with fake lag.
                for (PlayerData data : dataManager.getCollection()) {
                    data.tickExemptions();
                }
            } catch (Throwable t) {
                // A single bad tick must never cancel the repeating timer.
                plugin.getLogger().warning("[Truthful] Tick task error: " + t.getMessage());
            }
        }, 1L, 1L);

        // Rotate the per-tick block-write dedup window (see BlockWriteDedup)
        this.serverScheduler.runGlobalTimer(ret.tawny.truthful.utils.world.BlockWriteDedup::rotateAll, 1L, 1L);

        final long transactionInterval = getConfiguration().getTransactionPingIntervalTicks();
        this.serverScheduler.runAsyncTimer(() -> {
            try {
            // Kill-switch for disconnect diagnosis. Off means zero outbound
            // packets from this timer. Velocity, timer and lagback checks all
            // go half-blind without it, so default stays on.
            if (!getConfiguration().isTransactionPingEnabled()) return;

            long nowNanos = System.nanoTime();
            updateMeasuredTps(nowNanos, transactionInterval);
            this.lastProbeTimerRunNanos = nowNanos;

            for (PlayerData data : dataManager.getCollection()) {
                Player player = data.getPlayer();
                if (player == null || !player.isOnline() || isBedrockPlayer(player)) {
                    continue;
                }

                short id = (short) data.getNextTransactionId();
                data.recordTransactionSent(id);

                PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerPing(id));

                // Latency kicker: sustained high transaction ping => kick.
                // Ping-spoofers and lag-exploiters park an inflated ping to
                // farm latency leniency; a sustained threshold breaches that.
                if (getConfiguration().isPingKickEnabled()) {
                    long ping = data.getPing();
                    if (ping >= getConfiguration().getPingKickThreshold()) {
                        if (data.getHighPingStartTime() == 0L) {
                            data.setHighPingStartTime(System.currentTimeMillis());
                        } else if (System.currentTimeMillis() - data.getHighPingStartTime()
                                >= getConfiguration().getPingKickDuration() * 1000L) {
                            data.setHighPingStartTime(0L);
                            final Player victim = player;
                            final String kickMessage = getConfiguration().getPingKickMessage();
                            this.serverScheduler.runRegion(victim, () -> {
                                if (victim.isOnline()) {
                                    victim.kickPlayer(kickMessage);
                                }
                            });
                        }
                    } else {
                        data.setHighPingStartTime(0L);
                    }
                }
            }
            } catch (Throwable t) {
                plugin.getLogger().warning("[Truthful] Transaction probe task error: " + t.getMessage());
            }
        }, transactionInterval, transactionInterval);
    }

    private void updateMeasuredTps(long nowNanos, long scheduledIntervalTicks) {
        long last = this.lastProbeTimerRunNanos;
        if (last <= 0L || scheduledIntervalTicks <= 0L) return;
        long gapNanos = nowNanos - last;
        if (gapNanos <= 0L) return;
        double scheduledNanos = scheduledIntervalTicks * 50_000_000L;
        double observed = 20.0D * (scheduledNanos / (double) gapNanos);
        // Clamp to plausible server tick rates so a scheduling hiccup can't
        // read as 200 TPS.
        observed = Math.max(0.5D, Math.min(20.0D, observed));
        // EMA smoothing: fast enough to catch real lag, slow enough to
        // ignore single-timer jitter.
        this.measuredTps = (this.measuredTps * 3.0D + observed) / 4.0D;
    }

    public void shutdown() {
        if (serverScheduler != null) serverScheduler.shutdownWorldCache();
        if (logManager != null) logManager.shutdown();
        if (banwaveManager != null) banwaveManager.stop();
        if (dataManager != null) dataManager.teardownAll();
        if (serverScheduler != null) serverScheduler.cancelAll();
    }

    public static void startEngine(Plugin plugin) {
        INSTANCE.start(plugin);
    }

    public static void stopEngine() {
        INSTANCE.shutdown();
    }

    public boolean isBedrockPlayer(Player player) {
        return BedrockUtils.isBedrock(player);
    }

    public static Truthful getInstance() {
        if (INSTANCE.plugin == null) {
            throw new IllegalStateException("Truthful is not initialized! Call start() first.");
        }
        return INSTANCE;
    }

    public VersionManager getVersionManager() { return this.versionManager; }
    public DataManager getDataManager() { return this.dataManager; }
    public ret.tawny.truthful.data.world.GlobalWorldCache getGlobalWorldCache() { return this.globalWorldCache; }
    public CheckRegistry getCheckManager() { return this.checkManager; }
    public Configuration getConfiguration() { return ((TruthfulPlugin) plugin).getConfiguration(); }
    public Plugin getPlugin() { return plugin; }
    public PlayerListener getPlayerListener() { return playerListener; }
    public ret.tawny.truthful.utils.ServerScheduler getServerScheduler() { return serverScheduler; }
    public CompensationTracker getCompensationTracker() { return compensationTracker; }
    public LogManager getLogManager() { return logManager; }
    public GuiManager getGuiManager() { return guiManager; }
    public DebugManager getDebugManager() { return debugManager; }
    public DiscordManager getDiscordManager() { return discordManager; }
    public DebugLoggingManager getDebugLoggingManager() { return debugLoggingManager; }
    public TelemetryManager getTelemetryManager() { return telemetryManager; }
    public BanwaveManager getBanwaveManager() { return banwaveManager; }

    public void reload() {
        if (this.plugin == null) return;
        if (this.plugin instanceof TruthfulPlugin truthfulPlugin) truthfulPlugin.reloadConfiguration();
        if (this.checkManager != null) this.checkManager.init();
    }

    /**
     * Refreshes the hot-path TPS cache. Runs on the main/global thread from
     * the 1-tick timer; the reflective read happens once per tick instead of
     * once per canCheck() call on the packet threads.
     */
    private void refreshTpsCache() {
        try {
            if (!getTPSMethodFetched) {
                getTPSMethodFetched = true;
                try {
                    getTPSMethod = Bukkit.getServer().getClass().getMethod("getTPS");
                } catch (Throwable ignored) {
                }
            }
            if (getTPSMethod != null) {
                final Object value = getTPSMethod.invoke(Bukkit.getServer());
                if (value instanceof double[] tps && tps.length > 0) {
                    cachedTps = tps[0];
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        // Paper API absent (Folia): fall back to measured scheduler health
        // instead of a fake constant 20.0.
        cachedTps = measuredTps;
    }

    /** Cached server TPS, safe and cheap on packet threads. */
    public double getTps() {
        return cachedTps;
    }
}