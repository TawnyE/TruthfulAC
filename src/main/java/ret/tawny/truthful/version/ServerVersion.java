package ret.tawny.truthful.version;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed server version, ordered on a single monotonic scale.
 *
 * Minecraft switched from "1.x" to year-based "26.x" versioning after the
 * 1.21.x line, and Bukkit reports both shapes ("1.21.11-R0.1-SNAPSHOT",
 * "26.3-R0.1-SNAPSHOT"). Encoding major/minor/patch into one integer keeps
 * every comparison a plain int compare:
 *
 *   1.20.4  -> 1_020_004
 *   1.21.11 -> 1_021_011
 *   26.2    -> 26_002_000   (year-based versions sort above all 1.x)
 *   26.3    -> 26_003_000
 */
public final class ServerVersion implements Comparable<ServerVersion> {

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{1,2}))?");

    /** 1.20.4 */
    public static final ServerVersion V1_20_4 = new ServerVersion(1, 20, 4);
    /** 1.21.2 - CLIENT_TICK_END / known-input era */
    public static final ServerVersion V1_21_2 = new ServerVersion(1, 21, 2);
    /** 1.21.5 - modern input transform (square movement math) */
    public static final ServerVersion V1_21_5 = new ServerVersion(1, 21, 5);
    /** 26.2 "Chaos Cubed" - bounciness/air-drag/friction attributes, bed bounce 75% */
    public static final ServerVersion V26_2 = new ServerVersion(26, 2, 0);
    /** 26.3 "Wilderness Bound" - shelf mushrooms, straw beds, cushions */
    public static final ServerVersion V26_3 = new ServerVersion(26, 3, 0);

    public final int major;
    public final int minor;
    public final int patch;
    private final int code;

    private ServerVersion(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.code = major * 1_000_000 + minor * 1_000 + patch;
    }

    public static ServerVersion parse(String bukkitVersion) {
        if (bukkitVersion == null) return V1_20_4;
        Matcher matcher = VERSION_PATTERN.matcher(bukkitVersion.trim());
        if (!matcher.find()) return V1_20_4;

        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int patch = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;
        return new ServerVersion(major, minor, patch);
    }

    public boolean isAtLeast(ServerVersion other) {
        return this.code >= other.code;
    }

    public boolean isLegacy() {
        return this.major == 1;
    }

    /** Human-readable, e.g. "1.21.11" or "26.3". */
    public String display() {
        return patch == 0 ? major + "." + minor : major + "." + minor + "." + patch;
    }

    @Override
    public int compareTo(ServerVersion other) {
        return Integer.compare(this.code, other.code);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ServerVersion other && other.code == this.code;
    }

    @Override
    public int hashCode() {
        return code;
    }

    @Override
    public String toString() {
        return display();
    }
}
