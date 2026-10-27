package ret.tawny.truthful.version;

import org.bukkit.Bukkit;
import ret.tawny.truthful.version.impl.VersionAdapter_Modern;

/**
 * Resolves the running server version once at boot and hands out the
 * version-gated adapter. Understands both legacy "1.x" and year-based
 * "26.x" Bukkit version strings.
 */
public final class VersionManager {
    private volatile IVersionAdapter adapter;

    public void load() {
        final String raw = Bukkit.getServer().getBukkitVersion();
        final ServerVersion version = ServerVersion.parse(raw);

        if (version.isLegacy() && version.compareTo(ServerVersion.V1_20_4) < 0) {
            Bukkit.getLogger().warning("[Truthful] Detected legacy server version (" + version.display()
                    + "). TruthfulAC is designed for 1.20.4+. Stability is not guaranteed.");
        }

        this.adapter = new VersionAdapter_Modern(version);
        Bukkit.getLogger().info("[Truthful] Version profile: " + version.display()
                + (version.isAtLeast(ServerVersion.V26_2) ? " (26.2+ physics gates active)" : ""));
    }

    public IVersionAdapter getAdapter() {
        final IVersionAdapter local = this.adapter;
        if (local != null) return local;
        synchronized (this) {
            if (this.adapter == null) {
                this.adapter = new VersionAdapter_Modern(ServerVersion.V1_20_4);
            }
            return this.adapter;
        }
    }
}
