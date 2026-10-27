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
 * AimC - basic constant-rotation. Flags humanly impossible streaks of
 * near-identical yaw deltas while fighting (shitty assist replaying the same
 * correction every tick). Requires large deltas so slow drags never flag.
 */
@CheckData(order = 'C', type = CheckType.AIM)
public final class AimC extends Check {

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

        final float deltaYaw = data.getRotationDeviation(false);
        final float pitch = data.getPitch();

        if (data.getTicksTracked() - data.getLastHitTick() > 60) {
            aimData.lastDeltaYaw = deltaYaw;
            aimData.streak = 0;
            buffer.decrease(player, 0.2);
            return;
        }

        if (Math.abs(pitch) > 89.0f || deltaYaw < 1.0f || deltaYaw > 15.0f) {
            aimData.lastDeltaYaw = deltaYaw;
            aimData.streak = 0;
            return;
        }

        if (Math.abs(deltaYaw - aimData.lastDeltaYaw) < 0.002f) {
            aimData.streak++;
            if (aimData.streak >= 6) {
                if (buffer.increase(player, 1.0) > 8.0) {
                    flag(data, "Constant rotation");
                    buffer.reset(player, 3.0);
                }
                aimData.streak = 0;
            }
        } else {
            aimData.streak = 0;
            buffer.decrease(player, 0.1);
        }

        aimData.lastDeltaYaw = deltaYaw;
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        dataMap.remove(event.getPlayer().getUniqueId());
    }

    private static class AimData {
        float lastDeltaYaw = 0f;
        int streak = 0;
    }
}
