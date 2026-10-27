package ret.tawny.truthful.checks.impl.packet.badpacket;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.ExemptionType;
import ret.tawny.truthful.data.PlayerData;

/**
 * BadPacket8 - digging blocks that aren't there. Way out of reach means a
 * nuker guessing positions. 6 blocks is already generous.
 */
@CheckData(order = '8', type = CheckType.BAD_PACKET)
public final class BadPacket8 extends Check {

    private static final double MAX_DIG_DIST = 6.0D;

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_DIGGING) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isServerFrozen()) return;

        // Fast movement (lunges, knockback, teleports) desyncs the eye this is
        // measured from. Skip the chaotic states, measure from both the
        // current and last eye otherwise.
        int ticksNow;
        try {
            ticksNow = data.getTicksTracked();
            if (data.isTeleportTick() || data.getTicksSinceTeleport() < 5) return;
            if (data.hasVelocity() || ticksNow - data.getLastVelocityTick() <= 8) return;
            if (data.isExempt(ExemptionType.RIPTIDE) || data.isExempt(ExemptionType.WIND_CHARGE)
                    || data.isExempt(ExemptionType.MACE_SMASH) || data.isExempt(ExemptionType.SPEAR_LUNGE)) return;
        } catch (Throwable ignored) {
            return;
        }

        Vector3i block;
        try {
            WrapperPlayClientPlayerDigging wrapper = new WrapperPlayClientPlayerDigging(event);
            com.github.retrooper.packetevents.protocol.player.DiggingAction action = wrapper.getAction();
            // Only distance-judge actual mining actions. RELEASE_USE_ITEM
            // (shield use, eating), SWAP_ITEM, and DROP actions carry no
            // meaningful block position and must never be distance-checked
            // (Grim's FarBreak filters to START/FINISHED/CANCELLED only).
            if (action != com.github.retrooper.packetevents.protocol.player.DiggingAction.START_DIGGING
                    && action != com.github.retrooper.packetevents.protocol.player.DiggingAction.FINISHED_DIGGING
                    && action != com.github.retrooper.packetevents.protocol.player.DiggingAction.CANCELLED_DIGGING) {
                return;
            }
            block = wrapper.getBlockPosition();
        } catch (Throwable ignored) {
            return;
        }
        if (block == null) return;

        double eyeH;
        try {
            eyeH = data.getEyeHeightScaled();
        } catch (Throwable ignored) {
            eyeH = 1.62D;
        }
        Vector eye;
        Vector lastEye;
        try {
            eye = new Vector(data.getX(), data.getY() + eyeH, data.getZ());
            org.bukkit.Location last = data.getLastLocation();
            lastEye = new Vector(last.getX(), last.getY() + eyeH, last.getZ());
        } catch (Throwable ignored) {
            return;
        }
        Vector target = new Vector(block.getX() + 0.5D, block.getY() + 0.5D, block.getZ() + 0.5D);
        double dist = Math.min(eye.distance(target), lastEye.distance(target));

        // Attribute-aware ceiling: the block interaction range + a real
        // margin for eye/hand offsets, instead of a flat 6.0. Creative and
        // ranged-attribute configs (some servers run 5.0-6.0+) dug at
        // legitimate distances past the old cap.
        double range;
        try {
            range = Truthful.getInstance().getVersionManager().getAdapter().getBlockInteractionRange(player);
        } catch (Throwable t) {
            range = 4.5D;
        }
        double maxDist = Math.max(6.0D, range + 1.5D);

        if (dist > maxDist) {
            if (buffer.increase(player, 1.5) > 4.0) {
                flag(data, "Dig too far");
                buffer.reset(player, 2.0);
            }
        } else {
            buffer.decrease(player, 0.25);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}
