package ret.tawny.truthful.checks.impl.packet.badpacket;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.math.PositionSanity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BadPacketI - impossible position claims: the "far position packet"
 * disabler.
 *
 * A vanilla client cannot move more than ~10 blocks between movement
 * packets, and every legal larger jump comes from a server teleport we can
 * see as a pending queue entry. A position packet that jumps further with
 * nothing explaining it exists for exactly one purpose: forcing the server
 * to send correction teleports. The correction churn used to feed the
 * disabler - every correction bought free exemptions, tracker resets and
 * timer rebasing, and the claimed ghost position also flipped the
 * chunk-loaded gate that silences every check.
 *
 * Verdict: drop the packet at the door (the server never sees it, so no
 * correction is ever built around it), never let the trackers adopt it,
 * and flag. Cancellation honors the per-check "cancel" switch like every
 * other rejecting check; observe-only still streams flags while the packet
 * passes through to the server.
 */
@CheckData(order = 'I', type = CheckType.BAD_PACKET)
public final class BadPacketI extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private final Map<UUID, Double> lastJumpDist = new ConcurrentHashMap<>();

    private static volatile BadPacketI instance;

    public BadPacketI() {
        instance = this;
    }

    @Override
    public boolean rejectsPackets() { return true; }

    /** True while this check wants offending packets rejected (cancel switch on). */
    public static boolean shouldRejectPackets() {
        return shouldRejectPackets(null);
    }

    public static boolean shouldRejectPackets(Player player) {
        if (player != null && ret.tawny.truthful.utils.bedrock.BedrockUtils.isBedrock(player)) return false;
        BadPacketI self = instance;
        return self != null && self.isEnabled() && self.mayCancelPackets();
    }

    /**
     * Entry point from the packet listener's position-sanity gate: the claim
     * is impossible (non-finite, out of bounds, or an unexplained
     * teleport-distance jump). Runs BEFORE any tracker adopts the position.
     */
    public static void noteImpossibleClaim(final PlayerData data, final double jumpDist) {
        BadPacketI self = instance;
        if (self == null || data == null) return;
        Player player = data.getPlayer();
        if (player == null || !player.isOnline() || ret.tawny.truthful.utils.bedrock.BedrockUtils.isBedrock(player)) return;
        if (!self.isEnabled()) return;
        if (data.isServerFrozen() || data.isTeleportTick()) return;

        self.lastJumpDist.put(player.getUniqueId(), jumpDist);

        // Rejecting the packet is always safe (it never reaches the server).
        // But flagging/punishing on a single claim risked kicking legit
        // players moved by a plugin teleport we failed to match - require a
        // short streak of genuinely unexplained claims before flagging.
        if (self.buffer.increase(player, 1.0D) > 4.0D) {
            self.flag(data, String.format(
                    "Impossible position jump: %.1f blocks in one packet (max legal: %.0f, no pending teleport, x%d)",
                    jumpDist, PositionSanity.MAX_JUMP, (int) self.buffer.get(player)), 2.0D);
            self.buffer.reset(player, 1.0D);
        }
    }

    /** Last recorded impossible-jump distance for the player (debug tooling). */
    public static double getLastJumpDist(UUID playerId) {
        BadPacketI self = instance;
        if (self == null) return -1.0D;
        return self.lastJumpDist.getOrDefault(playerId, -1.0D);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        lastJumpDist.remove(event.getPlayer().getUniqueId());
    }
}
