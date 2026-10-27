package ret.tawny.truthful.config.api;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import ret.tawny.truthful.TruthfulPlugin;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.data.CheckType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configuration - two-file, snapshot-backed configuration.
 *
 * config.yml : plugin settings (options, messages, banwave, discord, ...)
 * checks.yml : per-check sections (enabled / lagback / punishment / extra keys)
 *
 * Everything read on a packet or tick path (per-check verdicts, TPS gates,
 * bedrock detection, probe intervals) is served from immutable snapshots
 * rebuilt on load/reload; YAML sections are only touched by cold paths
 * (commands, GUI rendering, message formatting) and by write-through setters.
 */
public final class Configuration {

    private final TruthfulPlugin plugin;
    private final FileConfiguration config;      // config.yml (plugin-managed)
    private final File checksFile;
    private final FileConfiguration checks;      // checks.yml

    // ---- immutable snapshots (hot path reads) ----
    private volatile String bedrockPrefix = ".";
    private volatile double minTps = 18.5;
    private volatile boolean lagbacks = true;
    private volatile LagbackMode lagbackMode = LagbackMode.MODERATE;
    private volatile double lagbackGroundSnap = 5.0;
    private volatile boolean transactionPing = true;
    private volatile int probeIntervalTicks = 2;
    private volatile int environmentScanTicks = 10;
    private volatile int violationResetMinutes = 5;
    private volatile boolean debugMode = false;
    private volatile boolean pingKickEnabled = true;
    private volatile int pingKickThreshold = 650;
    private volatile int pingKickDurationSeconds = 3;
    private volatile int queueMaxEntries = 50;
    private volatile long queueTtlMillis = 5000L;

    /** "TYPE.ORDER" -> immutable snapshot. */
    private final Map<String, CheckConfig> checkConfigSnapshots = new ConcurrentHashMap<>();

    public Configuration(final TruthfulPlugin plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        this.config = plugin.getConfig();

        this.checksFile = new File(plugin.getDataFolder(), "checks.yml");
        if (!checksFile.exists()) {
            plugin.saveResource("checks.yml", false);
        }
        this.checks = YamlConfiguration.loadConfiguration(checksFile);

        refreshSnapshots();
    }

    private void refreshSnapshots() {
        this.bedrockPrefix = config.getString("settings.bedrock-prefix", ".");
        this.minTps = Math.max(1.0, config.getDouble("options.min-tps", 18.5));
        this.lagbacks = config.getBoolean("options.lagback", true);
        this.lagbackMode = parseLagbackMode();
        this.lagbackGroundSnap = config.getDouble("options.lagback-ground-snap-distance", 5.0);
        this.transactionPing = config.getBoolean("transaction-ping.enabled", true);
        this.probeIntervalTicks = Math.max(1, config.getInt("latency-kicker.probe-interval-ticks", 2));
        this.environmentScanTicks = Math.max(1, config.getInt("options.environment-scan-interval-ticks", 10));
        this.violationResetMinutes = Math.max(1, config.getInt("options.violation-reset-interval", 5));
        this.debugMode = config.getBoolean("options.debug-mode", false);
        this.pingKickEnabled = config.getBoolean("latency-kicker.enabled", true);
        this.pingKickThreshold = config.getInt("latency-kicker.max-ping", 650);
        this.pingKickDurationSeconds = config.getInt("latency-kicker.duration-seconds", 3);
        this.queueMaxEntries = Math.max(1, config.getInt("sync.max-entries", 50));
        this.queueTtlMillis = Math.max(1000L, config.getLong("sync.entry-ttl-millis", 5000L));
        // NOTE: check snapshots are intentionally NOT cleared here. save()
        // calls this from an async task, and clearing forced every subsequent
        // packet-thread cfg() read to touch YAML concurrently with main-thread
        // GUI writes. Setters rebuild their own snapshot (rebuildSnapshot);
        // full reloads construct a new Configuration or call reloadAll().
    }

    // =====================================================================
    // SAVING (debounced async for GUI toggles)
    // =====================================================================

    private final java.util.concurrent.atomic.AtomicLong pendingSaveAt = new java.util.concurrent.atomic.AtomicLong(0);

    private void scheduleAsyncSave() {
        long due = System.currentTimeMillis() + 300L;
        pendingSaveAt.set(due);
        ret.tawny.truthful.Truthful.getInstance().getServerScheduler().runAsyncLater(this::flushSaves, 6L /* ~300ms */);
    }

    private void flushSaves() {
        if (System.currentTimeMillis() >= pendingSaveAt.get() - 20L) {
            save();
        }
    }

    public synchronized void save() {
        try {
            this.config.save(new File(this.plugin.getDataFolder(), "config.yml"));
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            this.checks.save(this.checksFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
        refreshSnapshots();
    }

    // =====================================================================
    // CHECK CONFIG SNAPSHOTS
    // =====================================================================

    private static String key(String checkType, String checkOrder) {
        return checkType + "." + checkOrder;
    }

    public CheckConfig getCheckConfig(String checkType, String checkOrder) {
        return checkConfigSnapshots.computeIfAbsent(key(checkType, checkOrder),
                k -> buildCheckConfig(checkType, checkOrder));
    }

    private CheckConfig buildCheckConfig(String checkType, String checkOrder) {
        String base = "checks." + checkType + "." + checkOrder;
        // Lagback inheritance: the "lagback" node may be a scalar
        // ("inherit" / true / false) or a section with an "enabled" child.
        // The old reader only looked at "lagback.enabled", so the scalar the
        // GUI cycler writes was ignored and every check silently fell back to
        // the global default.
        boolean lagbackEnabled;
        boolean globalDefault = globalLagbackDefaultFor(checkType);
        Object lagbackNode = this.checks.get(base + ".lagback");
        if (lagbackNode instanceof Boolean b) {
            lagbackEnabled = b;
        } else if (lagbackNode instanceof String s && s.equalsIgnoreCase("inherit")) {
            lagbackEnabled = globalDefault;
        } else if (lagbackNode instanceof org.bukkit.configuration.ConfigurationSection section) {
            lagbackEnabled = section.getBoolean("enabled", globalDefault);
        } else {
            lagbackEnabled = globalDefault;
        }
        Map<String, Object> raw = this.checks.getConfigurationSection(base) != null
                ? this.checks.getConfigurationSection(base).getValues(false)
                : Map.of();

        return new CheckConfig(
                this.checks.getBoolean(base + ".enabled", true),
                lagbackEnabled,
                this.checks.getInt(base + ".lagback-vl", this.checks.getInt(base + ".lagback.vl", 5)),
                this.checks.getBoolean(base + ".punishment.enabled", false),
                this.checks.getInt(base + ".punishment.vl", 20),
                this.checks.getString(base + ".punishment.command", "kick %player% Unfair Advantage"),
                this.checks.getBoolean(base + ".punishment.banwave", false),
                this.checks.getBoolean(base + ".cancel", true),
                raw);
    }

    // --- cached check getters & GUI write-through setters ---

    public boolean isCheckEnabled(String checkType, String checkOrder) {
        CheckConfig cfg = checkConfigSnapshots.get(key(checkType, checkOrder));
        return cfg != null ? cfg.enabled : getCheckConfig(checkType, checkOrder).enabled;
    }

    public synchronized void setCheckEnabled(String checkType, String checkOrder, boolean enabled) {
        this.checks.set("checks." + checkType + "." + checkOrder + ".enabled", enabled);
        rebuildSnapshot(checkType, checkOrder);
        scheduleAsyncSave();
    }

    public boolean isCheckLagbackEnabled(String checkType, String checkOrder) {
        if (!isLagbacks()) return false;
        CheckConfig cfg = getCheckConfig(checkType, checkOrder);
        return cfg.lagbackEnabled;
    }


    /** Raw per-check lagback value ("inherit" | "true" | "false" | null). */
    public String getCheckLagbackRaw(String checkType, String checkOrder) {
        Object v = this.checks.get("checks." + checkType + "." + checkOrder + ".lagback");
        return v != null ? String.valueOf(v) : null;
    }

    /** Write the raw per-check lagback value (GUI three-state cycler). */
    public synchronized void setCheckLagbackRaw(String checkType, String checkOrder, String value) {
        this.checks.set("checks." + checkType + "." + checkOrder + ".lagback", value);
        rebuildSnapshot(checkType, checkOrder);
        scheduleAsyncSave();
    }

        public synchronized void setCheckLagbackEnabled(String checkType, String checkOrder, boolean enabled) {
        // Single schema: the raw cycler ("inherit" | true | false) and the
        // boolean setter both write the scalar "lagback" node. Writing
        // "lagback.enabled" here used to fight the cycler and corrupt reads.
        this.checks.set("checks." + checkType + "." + checkOrder + ".lagback", enabled);
        rebuildSnapshot(checkType, checkOrder);
        scheduleAsyncSave();
    }

    public int getCheckLagbackVl(String checkType, String checkOrder) {
        return getCheckConfig(checkType, checkOrder).lagbackVl;
    }

    public synchronized void setCheckLagbackVl(String checkType, String checkOrder, int vl) {
        this.checks.set("checks." + checkType + "." + checkOrder + ".lagback-vl", vl);
        rebuildSnapshot(checkType, checkOrder);
        scheduleAsyncSave();
    }

    /** The global-profile default this check inherits when set to "inherit". */
    private boolean globalLagbackDefaultFor(String checkType) {
        if (lagbacks) {
            try {
                return isMovementType(CheckType.valueOf(checkType));
            } catch (IllegalArgumentException ex) {
                return false;
            }
        }
        return false;
    }

    private boolean isMovementType(CheckType type) {
        return type == CheckType.SIMULATION || type == CheckType.VELOCITY ||
                type == CheckType.SPOOF || type == CheckType.SCAFFOLD ||
                type == CheckType.PHASE || type == CheckType.TIMER || type == CheckType.BEDROCK || type == CheckType.LAG;
    }

    private void rebuildSnapshot(String checkType, String checkOrder) {
        checkConfigSnapshots.put(key(checkType, checkOrder), buildCheckConfig(checkType, checkOrder));
    }

    // =====================================================================
    // PUNISHMENTS
    // =====================================================================

    public boolean isPunishmentEnabled(String checkType, String checkOrder) {
        return getCheckConfig(checkType, checkOrder).punishmentEnabled;
    }

    public synchronized void setPunishmentEnabled(String checkType, String checkOrder, boolean enabled) {
        this.checks.set("checks." + checkType + "." + checkOrder + ".punishment.enabled", enabled);
        rebuildSnapshot(checkType, checkOrder);
        scheduleAsyncSave();
    }

    public int getPunishmentVl(String checkType, String checkOrder) {
        return getCheckConfig(checkType, checkOrder).punishmentVl;
    }

    public String getPunishmentCommand(String checkType, String checkOrder) {
        return getCheckConfig(checkType, checkOrder).punishmentCommand;
    }

    public boolean shouldQueueBanwave(String checkType, String checkOrder) {
        return getCheckConfig(checkType, checkOrder).banwaveQueue;
    }

    /**
     * Whether this check is allowed to reject packets directly. False = the
     * check is observe-only (flags/alerts still fire, packets pass through).
     * Defaults to true so direct rejection remains the security default.
     */
    public boolean isCheckCancelEnabled(String checkType, String checkOrder) {
        return getCheckConfig(checkType, checkOrder).cancelPackets;
    }

    public synchronized void setCheckCancelEnabled(String checkType, String checkOrder, boolean cancel) {
        this.checks.set("checks." + checkType + "." + checkOrder + ".cancel", cancel);
        rebuildSnapshot(checkType, checkOrder);
        scheduleAsyncSave();
    }

    // =====================================================================
    // CHECK-SPECIFIC SETTINGS (type-level extras live in checks.yml)
    // =====================================================================

    public int getAutoClickerMaxCps() { return this.checks.getInt("checks.AUTOCLICKER.max_cps", 22); }
    public boolean shouldCountGroundPunches() { return this.checks.getBoolean("checks.AUTOCLICKER.count_ground_punches", false); }
    public synchronized void setCountGroundPunches(boolean enabled) {
        this.checks.set("checks.AUTOCLICKER.count_ground_punches", enabled);
        scheduleAsyncSave();
    }

    // =====================================================================
    // OPTIONS (snapshotted - read on hot paths)
    // =====================================================================

    public String getBedrockPrefix() { return bedrockPrefix; }
    public String getPluginDisplayName() { return color(config.getString("settings.plugin-name", "&9&lT&e&lAC")); }
    public boolean isLagbacks() { return lagbacks; }
    public boolean isDebugMode() { return debugMode; }
    public int getViolationResetInterval() { return violationResetMinutes; }
    public double getMinTps() { return minTps; }
    public boolean isPunishmentBroadcastEnabled() { return config.getBoolean("options.punishment.broadcast", true); }
    public boolean isPunishmentAnimationEnabled() { return config.getBoolean("options.punishment.lightning", true); }
    public boolean isAlertsAutoEnableOnJoin() { return config.getBoolean("options.alerts.auto-enable-on-join", true); }

    public enum LagbackMode {
        BARELY(500, 8.0, false),
        MODERATE(80, 0.0, true),
        STRICT(100, 0.0, true),
        SUPER_STRICT(0, 0.0, true);

        public final int cooldownMs;
        public final double distanceThreshold;
        public final boolean setbackOnAirborne;

        LagbackMode(int cooldownMs, double distanceThreshold, boolean setbackOnAirborne) {
            this.cooldownMs = cooldownMs;
            this.distanceThreshold = distanceThreshold;
            this.setbackOnAirborne = setbackOnAirborne;
        }
    }

    private LagbackMode parseLagbackMode() {
        String raw = config.getString("options.lagback-mode", "MODERATE");
        if (raw != null) {
            try {
                return LagbackMode.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return LagbackMode.MODERATE;
    }

    public LagbackMode getLagbackMode() { return lagbackMode; }
    public double getLagbackGroundSnapDistance() { return lagbackGroundSnap; }

    // =====================================================================
    // ALERTS / MESSAGES (cold path)
    // =====================================================================

    public List<String> getAlertHoverLines() {
        if (this.config.isList("messages.alert-settings.hover-lines")) {
            return this.config.getStringList("messages.alert-settings.hover-lines");
        }
        return Arrays.asList(
                "&7Check: &f%check%",
                "&7VL: &f%vl%",
                "&7Ping: &f%ping%ms",
                "&7TPS: &f%tps%",
                "&7Client: &f%brand%",
                "&7Debug: &f%debug%"
        );
    }

    public boolean isAlertHoverEnabled() { return this.config.getBoolean("messages.alert-settings.hover-enabled", true); }
    public boolean isAlertShowCheckType() { return this.config.getBoolean("messages.alert-settings.show-check-type", true); }
    public boolean isAlertShowPing() { return this.config.getBoolean("messages.alert-settings.show-ping", true); }
    public boolean isAlertShowTps() { return this.config.getBoolean("messages.alert-settings.show-tps", true); }
    public boolean isAlertShowVl() { return this.config.getBoolean("messages.alert-settings.show-vl", true); }
    public boolean isAlertShowBrand() { return this.config.getBoolean("messages.alert-settings.show-client-brand", true); }
    public boolean isAlertShowCoords() { return this.config.getBoolean("messages.alert-settings.show-coordinates", false); }

    public boolean isPingKickEnabled() { return pingKickEnabled; }
    public int getPingKickThreshold() { return pingKickThreshold; }
    public int getPingKickDuration() { return pingKickDurationSeconds; }
    public int getTransactionPingIntervalTicks() { return probeIntervalTicks; }

    public boolean isTransactionPingEnabled() { return transactionPing; }
    public int getEnvironmentScanIntervalTicks() { return environmentScanTicks; }
    public String getPingKickMessage() { return color(config.getString("latency-kicker.kick-message", "&cConnection lost")); }

    public boolean isClientBlockerEnabled() { return config.getBoolean("client-blocker.enabled", true); }
    public List<String> getBlockedBrands() { return config.getStringList("client-blocker.blocked-brands"); }
    public String getClientBlockerMessage() { return color(config.getString("client-blocker.kick-message", "&cBlocked client brand.")); }

    public String getAlertMessage() { return color(config.getString("messages.alert", "&8[&cTruthful&8] &c%player% &ffailed &c%check% &8(&fVL:%vl%&8) &7%debug%")); }

    public List<String> getContributors() {
        if (this.config.isList("credits.contributors")) {
            return this.config.getStringList("credits.contributors");
        }
        return List.of("Nobody yet - be the first, open a PR");
    }

    // --- CHECK TAGS ---
    public String getCheckTagPosition() {
        String raw = this.config.getString("messages.check-tags.position", "SUFFIX");
        if (raw == null) return "SUFFIX";
        String upper = raw.trim().toUpperCase();
        if (upper.equals("SUFFIX") || upper.equals("PREFIX")
                || upper.equals("HOVER_ONLY") || upper.equals("OFF")) {
            return upper;
        }
        return "SUFFIX";
    }

    public String getCheckTagFormat() { return this.config.getString("messages.check-tags.format", "&7[%tag%]"); }
    public String getCheckTagSeparator() { return this.config.getString("messages.check-tags.separator", " "); }
    public boolean isCheckTagHoverEnabled() { return this.config.getBoolean("messages.check-tags.show-in-hover", true); }

    public String getBrandMessage() { return color(config.getString("messages.brand", "&8[&cTruthful&8] &7Client Brand: &c%player% &7using &f%brand%")); }
    public String getPunishmentBroadcast() { return color(config.getString("messages.punishment-broadcast", "&8[&cTruthful&8] &c%player% &fhas been removed from the server for cheating.")); }
    public String getOnlyPlayersMessage() { return color(config.getString("messages.commands.only-players", "&cOnly players can use this command.")); }
    public String getNoPermissionMessage() { return color(config.getString("messages.commands.no-permission", "&cNo permission.")); }
    public String getNoDataMessage() { return color(config.getString("messages.commands.no-data", "&cError: No data found.")); }
    public String getAlertsEnabledMessage() { return color(config.getString("messages.alerts-enabled", "&8[&bTruthful&8] &7Alerts have been &aenabled&7.")); }
    public String getAlertsDisabledMessage() { return color(config.getString("messages.alerts-disabled", "&8[&bTruthful&8] &7Alerts have been &cdisabled&7.")); }

    // =====================================================================
    // BANWAVE (cold path)
    // =====================================================================

    public boolean isBanwaveEnabled() { return config.getBoolean("banwave.enabled", true); }
    public int getBanwaveIntervalSeconds() { return Math.max(1, config.getInt("banwave.interval-seconds", 5)); }
    public String getBanwaveCommand() { return config.getString("banwave.command", "ban %player% [Truthful] BANWAVE"); }
    public String getBanwaveSweepMessage() { return color(config.getString("messages.banwave-sweep", "&8[&5&lBANWAVE&8] &7#%position% swept by the band wave: &f%player%")); }
    public String getBanwaveQueuedMessage() { return color(config.getString("messages.banwave-queued", "&8[&5&lBANWAVE&8] &f%player% &7was added to the BANWAVE queue.")); }
    public String getBanwaveDuplicateMessage() { return color(config.getString("messages.banwave-duplicate", "&8[&5&lBANWAVE&8] &f%player% &7is already queued.")); }
    public String getBanwaveStartedMessage() { return color(config.getString("messages.banwave-started", "&8[&5&lBANWAVE&8] &7Execution started.")); }
    public String getBanwaveStoppedMessage() { return color(config.getString("messages.banwave-stopped", "&8[&5&lBANWAVE&8] &7Execution stopped.")); }
    public String getBanwaveEmptyMessage() { return color(config.getString("messages.banwave-empty", "&8[&5&lBANWAVE&8] &7Queue is empty.")); }
    public String getBanwaveClearedMessage() { return color(config.getString("messages.banwave-cleared", "&8[&5&lBANWAVE&8] &7Queue cleared.")); }
    public String getBanwaveStatusMessage() { return color(config.getString("messages.banwave-status", "&8[&5&lBANWAVE&8] &7Running: &f%running% &8| &7Queued: &f%queued%")); }
    public String getBanwaveListHeader() { return color(config.getString("messages.banwave-list-header", "&8[&5&lBANWAVE&8] &7Queued players (&f%queued%&7):")); }
    public String getBanwaveListEntry() { return color(config.getString("messages.banwave-list-entry", "&8 - &f%position%. %player%")); }
    public String getBanwaveUsageMessage() { return color(config.getString("messages.banwave-usage", "&cUsage: /banwave <add|remove|list|start|stop|clear|status> [player]")); }
    public String getBanwaveNotEnabledMessage() { return color(config.getString("messages.banwave-disabled", "&8[&5&lBANWAVE&8] &cBANWAVE is disabled in config.")); }
    public String getBanwaveRemovedMessage() { return color(config.getString("messages.banwave-removed", "&8[&5&lBANWAVE&8] &f%player% &7was removed from the queue.")); }
    public String getBanwaveScheduleMessage() { return color(config.getString("messages.banwave-schedule", "&8[&5&lBANWAVE&8] &7Auto-start: &f%enabled% &8| &7Day: &f%day% &8| &7Time: &f%time%")); }

    public boolean isBanwaveAutoStartEnabled() { return config.getBoolean("banwave.auto-start.enabled", false); }
    public DayOfWeek getBanwaveAutoStartDay() {
        String raw = config.getString("banwave.auto-start.day", "SUNDAY");
        try { return DayOfWeek.valueOf(raw.toUpperCase()); } catch (Exception ignored) { return DayOfWeek.SUNDAY; }
    }
    public LocalTime getBanwaveAutoStartTime() {
        String raw = config.getString("banwave.auto-start.time", "18:00");
        try { return LocalTime.parse(raw); } catch (DateTimeParseException ignored) { return LocalTime.of(18, 0); }
    }

    // =====================================================================
    // DISCORD (cold path)
    // =====================================================================

    public boolean isDiscordEnabled() { return config.getBoolean("discord.enabled", false); }
    public String getDiscordWebhookUrl() { return config.getString("discord.webhook-url", ""); }
    public int getDiscordMinVl() { return config.getInt("discord.min-vl", 10); }
    public String getDiscordUsername() { return config.getString("discord.username", "Truthful Watchdog"); }
    public String getDiscordAvatarUrl() { return config.getString("discord.avatar-url", ""); }
    public int getDiscordEmbedColor() { return config.getInt("discord.embed.color", 16733525); }
    public String getDiscordFooter() { return config.getString("discord.embed.footer", "Truthful Anti-Cheat"); }
    public boolean isDiscordHeadEnabled() { return config.getBoolean("discord.embed.show-player-head", true); }

    public int getQueueMaxEntries() { return queueMaxEntries; }
    public long getQueueTtlMillis() { return queueTtlMillis; }

    // =====================================================================
    // VALIDATION
    // =====================================================================

    public boolean validateOrRepair() {
        int maxEntries = config.getInt("sync.max-entries", 50);
        long ttl = config.getLong("sync.entry-ttl-millis", 5000L);
        int probeInterval = config.getInt("latency-kicker.probe-interval-ticks", 2);
        boolean invalid = maxEntries <= 0 || ttl <= 0 || probeInterval <= 0;

        if (!checksFile.exists() || !checks.isConfigurationSection("checks")) {
            plugin.getLogger().severe("checks.yml is missing or malformed - backing up and restoring defaults.");
            restoreChecksFile();
            return false;
        }

        if (invalid) {
            plugin.getLogger().severe("Invalid configuration detected: sync.max-entries, sync.entry-ttl-millis and latency-kicker.probe-interval-ticks must be > 0.");
            File configFile = new File(plugin.getDataFolder(), "config.yml");
            File broken = new File(plugin.getDataFolder(), "config.yml.broken");
            try { Files.copy(configFile.toPath(), broken.toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) {}
            plugin.saveResource("config.yml", true);
            reloadAll();
            return false;
        }
        return true;
    }

    private void restoreChecksFile() {
        File broken = new File(plugin.getDataFolder(), "checks.yml.broken");
        try { Files.copy(checksFile.toPath(), broken.toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) {}
        plugin.saveResource("checks.yml", true);
        try {
            this.checks.load(checksFile);
        } catch (Exception ignored) {
        }
        // Full-file repair: snapshots must be rebuilt from the restored file.
        checkConfigSnapshots.clear();
        refreshSnapshots();
    }

    private void reloadAll() {
        plugin.reloadConfig();
        try {
            this.checks.load(checksFile);
        } catch (Exception ignored) {
        }
        checkConfigSnapshots.clear();
        refreshSnapshots();
    }

    public synchronized void cleanupOrphanedChecks(Collection<Check> activeChecks) {
        // No file pruning: user comments in checks.yml are preserved.
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
