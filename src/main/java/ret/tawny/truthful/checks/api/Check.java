package ret.tawny.truthful.checks.api;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.checks.registry.CheckRegistry;
import ret.tawny.truthful.config.api.CheckConfig;
import ret.tawny.truthful.config.api.Configuration;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public abstract class Check {

    private final char order;
    private final CheckType checkType;
    private final String formattedName;
    private final boolean experimental;
    private final java.util.List<String> tags;
    private volatile boolean enabled;
    private volatile CheckConfig cfg;

    private final Map<UUID, Integer> violationLevels = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastLogTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAlertTimes = new ConcurrentHashMap<>();
    /** Last non-removal punishment dispatch per player (see flag()). */
    private final Map<UUID, Long> lastPunishTimes = new ConcurrentHashMap<>();

    /** Punishment commands that are not removals run at most this often per player. */
    private static final long PUNISH_COMMAND_COOLDOWN_MILLIS = 10_000L;
    private static final String[] REMOVAL_COMMAND_PREFIXES = {
            "ban", "kick", "ipban", "banip", "tempban", "banwave"
    };

    public Check() {
        final CheckData checkData = this.getClass().getAnnotation(CheckData.class);
        this.order = checkData.order();
        this.checkType = checkData.type();
        String customName = checkData.displayName();
        if (customName != null && !customName.isEmpty()) {
            // Verbatim display name: checks that want a clean label (e.g.
            // "Simulation" instead of "Simulation(A)") get it, while config
            // paths keep using the type/order pair for stability.
            this.formattedName = customName;
        } else {
            this.formattedName = this.checkType.getName(this);
        }
        this.experimental = checkData.experimental();
        java.util.List<String> tagList = new java.util.ArrayList<>();
        if (this.experimental) tagList.add("EXPERIMENTAL");
        if (checkData.tags() != null) {
            for (String tag : checkData.tags()) {
                if (tag != null && !tag.trim().isEmpty()
                        && tagList.stream().noneMatch(t -> t.equalsIgnoreCase(tag.trim()))) {
                    tagList.add(tag.trim().toUpperCase());
                }
            }
        }
        this.tags = java.util.Collections.unmodifiableList(tagList);
    }

    public final boolean isExperimental() { return this.experimental; }

    /** Raw tags, e.g. [EXPERIMENTAL]. Empty when the check has no tags. */
    public final java.util.List<String> getTags() { return this.tags; }

    /**
     * User-facing check name with tags applied per messages.check-tags config.
     * Position SUFFIX/PREFIX decorates the name, HOVER_ONLY/OFF leaves it raw
     * (tags are still available via %check_tags% in hover lines).
     */
    public final String getTaggedName(final Configuration config) {
        if (this.tags.isEmpty()) return this.formattedName;
        String position = "SUFFIX";
        String format = "&7[%tag%]";
        String separator = " ";
        try {
            if (config != null) {
                position = config.getCheckTagPosition();
                format = config.getCheckTagFormat();
                separator = config.getCheckTagSeparator();
            }
        } catch (Throwable ignored) {}
        if ("OFF".equalsIgnoreCase(position) || "HOVER_ONLY".equalsIgnoreCase(position)) {
            return this.formattedName;
        }
        StringBuilder rendered = new StringBuilder();
        for (int i = 0; i < this.tags.size(); i++) {
            if (i > 0) rendered.append(separator);
            rendered.append(format.replace("%tag%", this.tags.get(i)));
        }
        // Config strings use '&' colors; keep them raw here, callers colorize.
        if ("PREFIX".equalsIgnoreCase(position)) {
            return rendered + separator + this.formattedName;
        }
        return this.formattedName + separator + rendered;
    }

    /** Raw tag string for %check_tags%, e.g. "[EXPERIMENTAL]". Empty when untagged. */
    public final String getTagString(final Configuration config) {
        if (this.tags.isEmpty()) return "";
        try {
            if (config != null && !config.isCheckTagHoverEnabled()) return "";
        } catch (Throwable ignored) {}
        String format = "&7[%tag%]";
        String separator = " ";
        try {
            if (config != null) {
                format = config.getCheckTagFormat();
                separator = config.getCheckTagSeparator();
            }
        } catch (Throwable ignored) {}
        StringBuilder rendered = new StringBuilder();
        for (int i = 0; i < this.tags.size(); i++) {
            if (i > 0) rendered.append(separator);
            rendered.append(format.replace("%tag%", this.tags.get(i)));
        }
        return rendered.toString();
    }

    public void init() {
        final CheckConfig snapshot = Truthful.getInstance().getConfiguration()
                .getCheckConfig(this.checkType.name(), String.valueOf(this.order));
        this.cfg = snapshot;
        this.enabled = snapshot.enabled;
    }

    /**
     * Live per-check settings. Always reads the Configuration map - GUI
     * toggles REPLACE the snapshot in the map, so a cached init-time copy
     * went stale and "disabled" checks kept their old settings (the lagback
     * toggle bug).
     */
    protected final CheckConfig cfg() {
        return Truthful.getInstance().getConfiguration()
                .getCheckConfig(this.checkType.name(), String.valueOf(this.order));
    }

    public final void handleQuitBase(final Player player) {
        if (player == null) return;
        final UUID uuid = player.getUniqueId();
        violationLevels.remove(uuid);
        lastLogTimes.remove(uuid);
        lastAlertTimes.remove(uuid);
        lastPunishTimes.remove(uuid);
    }

    public final void clearViolations() {
        violationLevels.clear();
    }

    public void flag(final PlayerData data, final String debug) {
        flag(data, debug, 1.0D);
    }

    public void flag(final PlayerData data, final String debug, final double severity) {
        if (!this.enabled || data == null || data.isExempt())
            return;

        final Player p = data.getPlayer();
        if (p == null || !p.isOnline()) return;

        if (data.isBanning()) {
            if (System.currentTimeMillis() - data.getBanStartTime() > 10000) {
                data.setBanning(false);
                violationLevels.remove(p.getUniqueId());
            } else {
                return;
            }
        }

        final UUID uuid = p.getUniqueId();
        int vlAddition = Math.max(1, (int) Math.round(severity));
        final int vl = violationLevels.merge(uuid, vlAddition, Integer::sum);
        data.addVl(vlAddition);

        data.setLastFlagTick(data.getTicksTracked());

        Truthful.getInstance().getDebugLoggingManager().logFlag(p, this.formattedName, vl, debug);

        // Auto-record flag telemetry for AI analysis
        if (Truthful.getInstance().getTelemetryManager() != null) {
            Truthful.getInstance().getTelemetryManager().recordFlag(data, this.formattedName, debug, vl, -1);
        }

        final Configuration config = Truthful.getInstance().getConfiguration();
        final CheckConfig checkCfg = cfg();

        final long now = System.currentTimeMillis();
        final long lastAlert = lastAlertTimes.getOrDefault(uuid, 0L);
        final boolean shouldAlert = (now - lastAlert >= 500L);

        if (shouldAlert) {
            lastAlertTimes.put(uuid, now);
            Truthful.getInstance().getDiscordManager().sendAlert(data, this, debug, vl);
        }

        final String playerName = p.getName();
        final long ping = data.getPing();
        final double tps = Truthful.getInstance().getTps();
        final String brand = data.getClientBrand();
        final org.bukkit.World cachedWorld = data.getWorld();
        final String world = cachedWorld != null ? cachedWorld.getName() : "unknown";
        final int px = (int) data.getX();
        final int py = (int) data.getY();
        final int pz = (int) data.getZ();
        final String checkName = getTaggedName(config);
        final String checkTags = getTagString(config);

        final String vlStr = Integer.toString(vl);
        final String pingStr = Long.toString(ping);
        final String tpsStr = String.format("%.1f", tps);
        final String debugStr = debug != null ? debug : "";

        final String chatMessage = buildAlertMessage(config.getAlertMessage(),
                playerName, checkName, vlStr, pingStr, tpsStr, brand, debugStr, checkTags, null, null, null, null);

        Truthful.getInstance().getServerScheduler().runRegion(p, () -> {
            if (!p.isOnline()) return;

            if (shouldAlert) {
                final long nowMillis = System.currentTimeMillis();
                final long lastLog = lastLogTimes.getOrDefault(uuid, 0L);

                if (nowMillis - lastLog > 2000L) {
                    Bukkit.getLogger().info(playerName + " failed " + checkName + ": " + debug);
                    lastLogTimes.put(uuid, nowMillis);
                }

                net.md_5.bungee.api.chat.BaseComponent[] components = net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                        org.bukkit.ChatColor.translateAlternateColorCodes('&', chatMessage));

                if (config.isAlertHoverEnabled()) {
                    StringBuilder hoverBuilder = new StringBuilder();
                    java.util.List<String> hoverLines = config.getAlertHoverLines();

                    for (int i = 0; i < hoverLines.size(); i++) {
                        String line = buildAlertMessage(hoverLines.get(i),
                                playerName, checkName, vlStr, pingStr, tpsStr, brand, debugStr, checkTags,
                                world, Integer.toString(px), Integer.toString(py), Integer.toString(pz));

                        hoverBuilder.append(line);
                        if (i < hoverLines.size() - 1) {
                            hoverBuilder.append("\n");
                        }
                    }

                    net.md_5.bungee.api.chat.HoverEvent hoverEvent = new net.md_5.bungee.api.chat.HoverEvent(
                            net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                            net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                                    org.bukkit.ChatColor.translateAlternateColorCodes('&', hoverBuilder.toString()))
                    );
                    for (net.md_5.bungee.api.chat.BaseComponent component : components) {
                        component.setHoverEvent(hoverEvent);
                    }
                }

                java.util.List<UUID> toRemove = new java.util.ArrayList<>();
                for (UUID staffUuid : Truthful.getInstance().getDataManager().getAlertSubscribers()) {
                    Player staff = Bukkit.getPlayer(staffUuid);
                    if (staff != null && staff.isOnline()) {
                        staff.spigot().sendMessage(components);
                    } else {
                        toRemove.add(staffUuid);
                    }
                }
                toRemove.forEach(Truthful.getInstance().getDataManager().getAlertSubscribers()::remove);
            }

            // 1. Configurable Lagbacks (snapshot read - no YAML on the hot path)
            if (checkCfg.lagbackEnabled && vl >= checkCfg.lagbackVl && !data.isServerFrozen()) {
                if (!p.isDead()) {
                    data.forceLagback();
                }
            }

            // 2. Punishments. Position correction is exclusively the lagback
            // switch's business - this branch used to force a setback before
            // every command dispatch, so a message-only punishment (tell/
            // warning) yanked the player backwards while all lagbacks were
            // explicitly disabled. No more: the punishment branch punishes,
            // the lagback branch corrects.
            if (checkCfg.punishmentEnabled && vl >= checkCfg.punishmentVl) {

                final boolean banwave = checkCfg.banwaveQueue && config.isBanwaveEnabled();

                // Removal actions (banwave queue, ban/kick commands) keep the
                // banning gate: flag() holds off for 10s while the removal
                // lands so the command cannot be re-spammed by ongoing flags.
                // Non-removal punishments (tell/warn/...) must NOT trip it -
                // the banning state muted the ENTIRE detection pipeline for
                // 10 seconds off a single warning message. They dispatch on
                // an explicit per-player cooldown instead.
                if (banwave || isRemovalCommand(checkCfg.punishmentCommand)) {
                    data.setBanning(true);
                } else if (System.currentTimeMillis()
                        - lastPunishTimes.getOrDefault(uuid, 0L) < PUNISH_COMMAND_COOLDOWN_MILLIS) {
                    return;
                }
                lastPunishTimes.put(uuid, System.currentTimeMillis());

                if (config.isPunishmentAnimationEnabled()) {
                    p.getWorld().strikeLightningEffect(p.getLocation());
                }

                if (banwave) {
                    boolean added = Truthful.getInstance().getBanwaveManager().addPlayer(p.getName());
                    String queueMessage = (added ? config.getBanwaveQueuedMessage() : config.getBanwaveDuplicateMessage())
                            .replace("%player%", playerName);
                    Bukkit.getConsoleSender().sendMessage(queueMessage);
                } else {
                    final String command = checkCfg.punishmentCommand
                            .replace("%player%", playerName);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);

                    if (config.isPunishmentBroadcastEnabled()) {
                        final String broadcastMessage = config.getPunishmentBroadcast()
                                .replace("%player%", playerName);
                        Bukkit.broadcastMessage(broadcastMessage);
                    }
                }
            }
        });

        Truthful.getInstance().getLogManager().log(uuid, playerName, this.formattedName, vl, ping, debug);
    }

    /**
     * True when the configured command actually removes the player from the
     * server (ban family / kick). Only these keep the banning gate in flag();
     * everything else (tell, warn, title, custom plugin commands) throttles
     * on the per-player punishment cooldown instead.
     */
    private static boolean isRemovalCommand(final String command) {
        if (command == null || command.isBlank()) return false;
        String cmd = command.trim().toLowerCase(java.util.Locale.ROOT);
        // Strip a namespace prefix ("minecraft:kick") - what matters is the
        // label the dispatcher will actually run.
        final int space = cmd.indexOf(' ');
        final String head = space < 0 ? cmd : cmd.substring(0, space);
        final int colon = head.indexOf(':');
        if (colon >= 0) cmd = cmd.substring(colon + 1);
        for (final String prefix : REMOVAL_COMMAND_PREFIXES) {
            if (!cmd.startsWith(prefix)) continue;
            final int len = prefix.length();
            if (cmd.length() == len || Character.isWhitespace(cmd.charAt(len))) return true;
        }
        return false;
    }

    private static String buildAlertMessage(String template, String player, String check, String vl,
                                            String ping, String tps, String brand, String debug, String checkTags,
                                            String world, String x, String y, String z) {
        StringBuilder sb = new StringBuilder(template.length() + 64);
        int len = template.length();
        int i = 0;
        while (i < len) {
            int idx = template.indexOf('%', i);
            if (idx == -1) {
                sb.append(template, i, len);
                break;
            }
            sb.append(template, i, idx);
            int placeholderLen = 0;
            String replacement = null;
            if (idx + 8 <= len && template.startsWith("%player%", idx)) { replacement = player; placeholderLen = 8; }
            else if (idx + 7 <= len && template.startsWith("%check%", idx)) { replacement = check; placeholderLen = 7; }
            else if (idx + 7 <= len && template.startsWith("%world%", idx)) { replacement = world; placeholderLen = 7; }
            else if (idx + 4 <= len && template.startsWith("%vl%", idx)) { replacement = vl; placeholderLen = 4; }
            else if (idx + 6 <= len && template.startsWith("%ping%", idx)) { replacement = ping; placeholderLen = 6; }
            else if (idx + 5 <= len && template.startsWith("%tps%", idx)) { replacement = tps; placeholderLen = 5; }
            else if (idx + 7 <= len && template.startsWith("%brand%", idx)) { replacement = brand; placeholderLen = 7; }
            else if (idx + 7 <= len && template.startsWith("%debug%", idx)) { replacement = debug; placeholderLen = 7; }
            else if (idx + 12 <= len && template.startsWith("%check_tags%", idx)) { replacement = checkTags; placeholderLen = 12; }
            else if (idx + 3 <= len && template.startsWith("%x%", idx)) { replacement = x; placeholderLen = 3; }
            else if (idx + 3 <= len && template.startsWith("%y%", idx)) { replacement = y; placeholderLen = 3; }
            else if (idx + 3 <= len && template.startsWith("%z%", idx)) { replacement = z; placeholderLen = 3; }

            if (replacement != null) {
                sb.append(replacement);
                i = idx + placeholderLen;
            } else {
                sb.append('%');
                i = idx + 1;
            }
        }
        return sb.toString().trim();
    }

    private boolean requiresStableSimulation(CheckType type) {
        return type == CheckType.SIMULATION || type == CheckType.VELOCITY ||
                type == CheckType.SPOOF || type == CheckType.SCAFFOLD ||
                type == CheckType.PHASE || type == CheckType.TIMER ||
                type == CheckType.BEDROCK;
    }

    protected void debugVerbose(String message) {
        Truthful.getInstance().getDebugManager().sendVerbose(this.formattedName, message);
    }

    protected void debugSus(String message, double buffer) {
        Truthful.getInstance().getDebugManager().sendSuspicion(this.formattedName, message, buffer);
    }

    protected void debugStatus(PlayerData data, String details, double currentBuffer, double maxThreshold) {
        Truthful.getInstance().getDebugManager().sendDebugStatus(data, this.formattedName, details, currentBuffer, maxThreshold);
    }

    public final char getOrder() { return this.order; }
    public final String getFormattedName() { return this.formattedName; }
    public final boolean isEnabled() { return this.enabled; }
    public final void setEnabled(boolean enabled) {
        this.enabled = enabled;
        // The world/entity pipelines are derived from the enabled-check set;
        // a GUI toggle can flip whether they need to run at all.
        CheckRegistry registry = Truthful.getInstance().getCheckManager();
        if (registry != null) registry.recomputePipelineRequirements();
    }
    public final CheckType getType() { return this.checkType; }

    /**
     * Whether this check is allowed to reject packets directly. The punishment
     * and lagback switches only control commands/setbacks - they never covered
     * direct packet rejection. Setting "cancel: false" in checks.yml makes
     * the check observe-only: it still flags and alerts, it just lets the
     * offending packet through. Defaults to true (rejection is the security
     * default).
     */
    protected final boolean mayCancelPackets() {
        return cfg().cancelPackets;
    }

    /**
     * Rejects the offending packet when this check may cancel and flags either
     * way. All direct packet rejection goes through here so the per-check
     * cancel switch is honored everywhere.
     */
    protected final void rejectPacket(final PacketReceiveEvent event, final PlayerData data, final String debug) {
        if (mayCancelPackets()) {
            event.setCancelled(true);
        }
        flag(data, debug);
    }

    /**
     * True when this check rejects packets directly (see {@link #mayCancelPackets()}).
     * Used by the config GUI to show the observe-only toggle only where it
     * is meaningful. Override in checks that cancel packets.
     */
    public boolean rejectsPackets() {
        return false;
    }

    /**
     * Does this check consume compensated block data? The registry aggregates
     * this across ENABLED checks: when nobody needs the world, the chunk
     * decode/cache pipeline, ground probes and environment scans are skipped
     * entirely (a clicks-only profile pays nothing for world tracking).
     */
    public boolean requiresWorld() {
        switch (checkType) {
            case SIMULATION:
            case VELOCITY:
            case SPOOF:
            case SCAFFOLD:
            case PHASE:
            case FAST_BREAK:
            case RAYCAST:
            case KILLAURA:
            case ANCHOR:
            case HITBOX:
            case BEDROCK:
                return true;
            default:
                return false;
        }
    }

    /**
     * Does this check consume the compensated entity tracker? Same aggregate
     * logic: with no entity-consuming check enabled, spawn/teleport/move
     * tracking for other entities is skipped.
     */
    public boolean requiresEntityTracking() {
        switch (checkType) {
            case REACH:
            case LAG:
            case KILLAURA:
            case CRYSTAL:
            case BEDROCK:
                return true;
            default:
                return false;
        }
    }

    public void onPacketPlaySend(PacketSendEvent event) {
        if (canCheck(event.getPlayer())) handlePacketPlaySend(event);
    }
    public void handlePacketPlaySend(PacketSendEvent event) {}

    public void onPacketPlayerReceive(PacketReceiveEvent event) {
        if (canCheck(event.getPlayer())) handlePacketPlayerReceive(event);
    }
    public void handlePacketPlayerReceive(PacketReceiveEvent event) {}
    public void onRelMove(RelMovePacketWrapper event) {
        if (canCheck(event.getPlayer())) handleRelMove(event);
    }
    public void handleRelMove(final RelMovePacketWrapper event) {}

    public final boolean canCheck(Object rawPlayer) {
        if (!(rawPlayer instanceof Player player)) return false;
        if (!enabled) return false;
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isExempt()) return false;

        if (requiresStableSimulation(checkType) && data.shouldSkipChecks()) {
            return false;
        }

        // Server-trusted gamemode gate (main-thread cache - never a live
        // Bukkit call on the packet thread). Replaces the old blanket
        // allowFlight exemption in isExempt() that used to silence EVERY
        // check, combat included, for anyone with flight. Players ACTIVELY
        // flying are still skipped (their positions are erratic); a /fly
        // player standing on the ground is judged normally.
        final org.bukkit.GameMode gameMode = data.getGameMode();
        if (gameMode == org.bukkit.GameMode.CREATIVE || gameMode == org.bukkit.GameMode.SPECTATOR
                || data.isFlying()) {
            return false;
        }

        final boolean isBedrockPlayer = Truthful.getInstance().isBedrockPlayer(player);
        if (this.checkType == CheckType.BEDROCK) {
            return isBedrockPlayer;
        }

        if (isBedrockPlayer) {
            return false;
        }

        return true;
    }

    public void onAttack(final org.bukkit.event.entity.EntityDamageByEntityEvent event) {}
    public void onQuit(final org.bukkit.event.player.PlayerQuitEvent event) {}
    public void onBlockBreak(final org.bukkit.event.block.BlockBreakEvent event) {}
    public void onVehicleMove(final org.bukkit.event.vehicle.VehicleMoveEvent event) {}
}