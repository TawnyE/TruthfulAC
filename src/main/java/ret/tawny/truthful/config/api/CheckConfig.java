package ret.tawny.truthful.config.api;

import java.util.Map;

/**
 * CheckConfig - immutable per-check configuration snapshot.
 *
 * Built once at load/reload from checks.yml, stored on the Check itself, and
 * read as plain field accesses on the hot path. Zero path lookups, zero map
 * probes, zero string building per packet. Mutations (GUI toggles) write
 * through Configuration, which rebuilds the affected snapshot in place.
 */
public final class CheckConfig {

    public final boolean enabled;
    public final boolean lagbackEnabled;
    public final int lagbackVl;
    public final boolean punishmentEnabled;
    public final int punishmentVl;
    public final String punishmentCommand;
    public final boolean banwaveQueue;
    /**
     * Whether this check may reject packets directly. The existing punishment
     * and lagback switches only control commands/setbacks - they never covered
     * direct packet rejection. This is the explicit switch for that: false
     * turns the check observe-only (it still flags/alerts, it just lets the
     * packet through). Default true keeps rejection as the security default.
     */
    public final boolean cancelPackets;

    /** Raw check section for check-specific settings (e.g. thresholds). */
    public final Map<String, Object> raw;

    public CheckConfig(boolean enabled,
                       boolean lagbackEnabled, int lagbackVl,
                       boolean punishmentEnabled, int punishmentVl,
                       String punishmentCommand, boolean banwaveQueue,
                       Map<String, Object> raw) {
        this(enabled, lagbackEnabled, lagbackVl, punishmentEnabled, punishmentVl,
                punishmentCommand, banwaveQueue, true, raw);
    }

    public CheckConfig(boolean enabled,
                       boolean lagbackEnabled, int lagbackVl,
                       boolean punishmentEnabled, int punishmentVl,
                       String punishmentCommand, boolean banwaveQueue,
                       boolean cancelPackets,
                       Map<String, Object> raw) {
        this.enabled = enabled;
        this.lagbackEnabled = lagbackEnabled;
        this.lagbackVl = lagbackVl;
        this.punishmentEnabled = punishmentEnabled;
        this.punishmentVl = punishmentVl;
        this.punishmentCommand = punishmentCommand;
        this.banwaveQueue = banwaveQueue;
        this.cancelPackets = cancelPackets;
        this.raw = raw == null ? Map.of() : Map.copyOf(raw);
    }

    public double getDouble(String key, double fallback) {
        Object v = raw.get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    public int getInt(String key, int fallback) {
        Object v = raw.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    public boolean getBoolean(String key, boolean fallback) {
        Object v = raw.get(key);
        return v instanceof Boolean b ? b : fallback;
    }
}
