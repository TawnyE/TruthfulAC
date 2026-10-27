package ret.tawny.truthful.checks.impl.movement.spoof;

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
 * GroundSpoofE - Fall Distance Spoofing
 *
 * Targets NoFall hacks that toggle ground status momentarily to reset fall distance.
 */
@CheckData(order = 'E', type = CheckType.SPOOF)
public final class GroundSpoofE extends Check {

    private final CheckBuffer buffer = new CheckBuffer(5.0);
    private final Map<UUID, Float> fallDistances = new ConcurrentHashMap<>();

    @Override
    public void handleRelMove(final RelMovePacketWrapper wrapper) {
        final Player player = wrapper.getPlayer();
        final PlayerData data = wrapper.getPlayerData();
        if (data == null || data.isServerFrozen()) return;

        float fall = fallDistances.getOrDefault(player.getUniqueId(), 0f);

        if (data.isAllowFlight() || data.isFlying() || data.isInsideVehicle() || data.isGliding()
                || data.isInLiquid() || data.isOnClimbable() || data.isTeleportTick()) {
            fallDistances.put(player.getUniqueId(), 0f);
            return;
        }

        double deltaY = data.getDeltaY();

        if (data.isServerGround()) {
            fall = 0f;
        } else if (deltaY < 0) {
            fall += (float) -deltaY;
        }
        fallDistances.put(player.getUniqueId(), fall);

        // Logic: Server sees significant fall, Client claims ground
        if (fall > 3.5 && wrapper.isGround()) {
            // Leniency for edge landing
            if (data.isOnGround()) return;

            if (buffer.increase(player, 1.0) > 3.0) {
                flag(data, String.format("Fall Distance Spoof. Server: %.2f", fall));
                buffer.reset(player, 1.0);
            }
        } else {
            buffer.decrease(player, 0.1);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        fallDistances.remove(event.getPlayer().getUniqueId());
    }
}
