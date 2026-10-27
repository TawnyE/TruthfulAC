package ret.tawny.truthful.checks.impl.combat.aim;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AimA - combat rotation grid consistency. Mouse input is quantized by the
 * vanilla sensitivity curve, so every legitimate aim delta is (gamma * integer
 * pixels). This check evaluates the player's rotation samples against the
 * accumulated sensitivity estimator: a client that keeps fighting for seconds
 * with rotations that no vanilla sensitivity grid can explain is not using a
 * mouse pipeline.
 *
 * The old implementation used a pairwise GCD and an inverted sensitivity
 * band, which made the flag window ~0.0009 degrees wide (i.e. the check was
 * dead). The estimator already existed in the codebase and is the correct
 * model; this check now consumes it conservatively (long streak + combat
 * gate + large buffer), so cinematic-camera users and legit sensitivity
 * changes are not punished.
 */
@CheckData(order = 'A', type = CheckType.AIM)
public final class AimA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private final Map<UUID, AimData> dataMap = new ConcurrentHashMap<>();

    @Override
    public void handleRelMove(final RelMovePacketWrapper event) {
        if (!event.isRotationUpdate()) return;

        final Player player = event.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isRotationExempt() || data.isInsideVehicle()) return;
        if (data.isServerFrozen()) {
            buffer.decrease(player, 0.5);
            return;
        }

        AimData aimData = dataMap.computeIfAbsent(player.getUniqueId(), k -> new AimData());

        // Combat gate: only judge aim while actually fighting (and for a
        // short tail after the last hit). Free-look, cinematics and menu
        // spins are never judged.
        if (data.getTicksTracked() - data.getLastHitTick() > 20) {
            aimData.offGridStreak = 0;
            buffer.decrease(player, 0.2);
            return;
        }

        if (Math.abs(data.getPitch()) > 89.0f) {
            aimData.offGridStreak = 0;
            return;
        }

        final float deltaPitch = data.getRotationDeviation(true);
        final float deltaYaw = data.getRotationDeviation(false);
        final float delta = Math.max(deltaPitch, deltaYaw);

        // Sub-pixel idle jitter and over-15-degree frame drops carry no
        // signal (the latter cannot be sampled cleanly per tick anyway).
        if (delta < 0.5f || delta > 15.0f) {
            return;
        }

        final int samples = data.getSensitivitySampleCount();
        final boolean resolved = data.isSensitivityResolved();
        final double confidence = data.getSensitivityConfidence();

        // Enough varied samples, no sensitivity grid can explain them, and
        // the signal has held for a sustained combat window. 4+ seconds of
        // this cannot come from a mouse, but a sensitivity change or a
        // short cinematic stretch can - hence the streak + buffer.
        if (samples >= 64 && !resolved && confidence < 0.25D) {
            aimData.offGridStreak++;
            if (aimData.offGridStreak >= 80 && buffer.increase(player, 1.0) > 10.0) {
                flag(data, String.format("Non-grid combat rotation (samples=%d conf=%.2f streak=%d)",
                        samples, confidence, aimData.offGridStreak));
                buffer.reset(player, 3.0);
                aimData.offGridStreak = 0;
            }
        } else {
            aimData.offGridStreak = 0;
            buffer.decrease(player, 0.15);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        dataMap.remove(event.getPlayer().getUniqueId());
    }

    private static class AimData {
        int offGridStreak = 0;
    }
}
