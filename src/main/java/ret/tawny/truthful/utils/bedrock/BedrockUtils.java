package ret.tawny.truthful.utils.bedrock;

import org.bukkit.entity.Player;
import ret.tawny.truthful.Truthful;

import java.util.UUID;

/**
 * BedrockUtils - Dedicated utilities for Geyser/Floodgate clients.
 *
 * Bedrock clients behave differently than Java clients due to:
 * 1. Touch controls (different rotation packets).
 * 2. Input lag/Jitter (Geyser translation).
 * 3. Native physics differences (Reach, Knockback).
 *
 * This class defines the "Hard Limits" for Bedrock players to allow
 * lenience while stopping blatant cheating.
 *
 * DETECTION IS SERVER-TRUSTED ONLY. The client brand string is fully
 * client-controlled (any Java client can send "geyser" and used to turn the
 * entire anticheat off), so it is never a detection source here. The trusted
 * signals are, in order:
 *   1. Floodgate API (reflection, when the plugin is installed), which knows
 *      whether the login actually came through Geyser.
 *   2. The configured username prefix (Floodgate's own convention).
 *   3. The Floodgate-reserved offline UUID version (0).
 */
public final class BedrockUtils {

    private BedrockUtils() {}

    // Floodgate API reflection handle (resolved once). Floodgate is a soft
    // dependency: when it is absent these stay null and the fallbacks apply.
    private static volatile boolean floodgateChecked = false;
    private static volatile Object floodgateApi = null;
    private static volatile java.lang.reflect.Method floodgateIsPlayer = null;

    private static boolean isFloodgatePlayer(UUID uuid) {
        if (!floodgateChecked || floodgateApi == null) {
            synchronized (BedrockUtils.class) {
                if (!floodgateChecked || floodgateApi == null) {
                    try {
                        Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                        java.lang.reflect.Method getInstance = apiClass.getMethod("getInstance");
                        Object api = getInstance.invoke(null);
                        java.lang.reflect.Method isPlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
                        // Verify before publishing.
                        if (api != null) {
                            floodgateApi = api;
                            floodgateIsPlayer = isPlayer;
                        }
                    } catch (Throwable ignored) {
                        floodgateApi = null;
                        floodgateIsPlayer = null;
                    }
                    floodgateChecked = true;
                }
            }
        }
        Object api = floodgateApi;
        java.lang.reflect.Method method = floodgateIsPlayer;
        if (api == null || method == null) return false;
        try {
            Object result = method.invoke(api, uuid);
            return result instanceof Boolean b && b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // === BEDROCK PHYSICS CONSTANTS ===

    // Bedrock reach is naturally higher (up to 3.0-3.5 on mobile).
    // We set a hard cap at 4.5 to allow for translation jitter.
    public static final double MAX_REACH = 4.5;

    // Bedrock friction is often miscalculated by Geyser during lag.
    // Instead of 0.91 (Air Drag), we use broader limits.
    public static final double MAX_SPEED_GROUND = 0.65; // ~2x Walking speed
    public static final double MAX_SPEED_AIR = 0.75;    // Allows for inconsistent air-strafing

    // Gravity calculation tolerance.
    // Java is precise (0.08). Bedrock packets often arrive in bursts, making Y-deltas fluctuate.
    public static final double GRAVITY_TOLERANCE = 0.15;

    /**
     * Determines if a player is using a Bedrock client, from server-trusted
     * signals only (see class docs). The client brand is deliberately NOT
     * consulted: it is spoofable and gating checks on it was a total bypass.
     */
    public static boolean isBedrock(Player player) {
        try {
            // 1. Floodgate API - the primary trusted signal
            if (isFloodgatePlayer(player.getUniqueId())) {
                return true;
            }

            // If Floodgate is installed and actively responded false, this is NOT a Bedrock player
            if (floodgateApi != null) {
                return false;
            }

            // 2. Configurable Username Prefix (Floodgate fallback when Floodgate plugin is not loaded)
            String prefix = Truthful.getInstance().getConfiguration().getBedrockPrefix();
            if (prefix != null && !prefix.isEmpty() && player.getName().startsWith(prefix)) {
                if (org.bukkit.Bukkit.getOnlineMode()) {
                    return true;
                }
            }

            // 3. Floodgate UUID Check (fallback for offline/foreign clients on online servers)
            UUID uuid = player.getUniqueId();
            if (uuid.version() == 0 && org.bukkit.Bukkit.getOnlineMode()) {
                return true;
            }

        } catch (Exception ignored) {
            // Fail safe -> Treat as Java to ensure standard checks run
        }
        return false;
    }

    /**
     * Returns true if the player should skip Standard Java Checks.
     */
    public static boolean shouldSkipJavaChecks(Player player) {
        return isBedrock(player);
    }
}