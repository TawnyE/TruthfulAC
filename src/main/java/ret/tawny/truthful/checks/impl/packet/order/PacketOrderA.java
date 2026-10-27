package ret.tawny.truthful.checks.impl.packet.order;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


@CheckData(order = 'A', type = CheckType.PACKET_ORDER)
@SuppressWarnings("unused")
public final class PacketOrderA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private final Map<UUID, PlaceOrderState> stateMap = new ConcurrentHashMap<>();

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        final Player player = (Player) event.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null || Truthful.getInstance().isBedrockPlayer(player))
            return;

        PacketTypeCommon type = event.getPacketType();
        PlaceOrderState state = stateMap.computeIfAbsent(player.getUniqueId(), k -> new PlaceOrderState());

        // Track swings
        if (type == PacketType.Play.Client.ANIMATION) {
            state.lastSwingTick = data.getTicksTracked();
        }

        // Check block placements
        else if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            // Hold-click repeats: the client auto-places against the same
            // target without re-swinging per packet. Repeats are exempt.
            boolean repeat = data.getTicksTracked() - state.lastPlaceTick <= 4;
            if (repeat) { state.lastPlaceTick = data.getTicksTracked(); buffer.decrease(player, 0.2); return; }
            state.lastPlaceTick = data.getTicksTracked();
            int diff = data.getTicksTracked() - state.lastSwingTick;

            // Swing must be near the place. Modern clients fire placement
            // RETRIES per face/interaction while holding or jitter-clicking
            // with a single swing per click - a tight 2-tick window flags
            // legit spam. 5 ticks covers click cadence + tick bundles.
            if (diff > 5) {
                if (state.pendingPlaceTick == -1) {
                    state.pendingPlaceTick = data.getTicksTracked();
                }
            } else {
                state.pendingPlaceTick = -1;
                buffer.decrease(player, 0.2);
            }
        }

        // Check timeout on flying packets
        else if (WrapperPlayClientPlayerFlying.isFlying(type)) {
            if (state.pendingPlaceTick != -1) {
                if (data.getTicksTracked() - state.pendingPlaceTick > 4) {
                    if (buffer.increase(player, 1.0) > 8.0) {
                        flag(data, "Block placement without animation");
                        buffer.reset(player, 3.0);
                    }
                    state.pendingPlaceTick = -1;
                }
            } else {
                buffer.decrease(player, 0.1);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        stateMap.remove(event.getPlayer().getUniqueId());
    }

    private static class PlaceOrderState {
        int lastSwingTick = -100;
        int lastPlaceTick = -100;
        int pendingPlaceTick = -1;
    }
}
