package ret.tawny.truthful.checks.impl.packet.badpacket;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
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

/**
 * BadPacketD - duplicate sprint/sneak state packets.
 *
 * The vanilla client only transitions these states once; a client repeating
 * START_SPRINTING twice in a row (or START_SNEAKING twice) is sending illegal
 * transitions. Tiny buffer for the one-off re-login/resync edge.
 */
@CheckData(order = 'D', type = CheckType.BAD_PACKET)
public final class BadPacketD extends Check {

    private final CheckBuffer buffer = new CheckBuffer(5.0);
    private final Map<UUID, Integer> lastSprint = new ConcurrentHashMap<>(); // 1 start, 0 stop, -1 unknown
    private final Map<UUID, Integer> lastSneak = new ConcurrentHashMap<>();

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.ENTITY_ACTION) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isServerFrozen()) return;

        WrapperPlayClientEntityAction action;
        try {
            action = new WrapperPlayClientEntityAction(event);
        } catch (Throwable t) { return; }

        WrapperPlayClientEntityAction.Action type = action.getAction();
        if (type == null) return;

        UUID id = player.getUniqueId();
        boolean bad = false;

        switch (type) {
            case START_SPRINTING -> {
                if (lastSprint.getOrDefault(id, -1) == 1) bad = true;
                lastSprint.put(id, 1);
            }
            case STOP_SPRINTING -> {
                if (lastSprint.getOrDefault(id, -1) == 0) bad = true;
                lastSprint.put(id, 0);
            }
            case START_SNEAKING -> {
                if (lastSneak.getOrDefault(id, -1) == 1) bad = true;
                lastSneak.put(id, 1);
            }
            case STOP_SNEAKING -> {
                if (lastSneak.getOrDefault(id, -1) == 0) bad = true;
                lastSneak.put(id, 0);
            }
            default -> { }
        }

        if (bad) {
            if (buffer.increase(player, 1.0) > 2.0) {
                flag(data, "Duplicate entity action " + type);
                buffer.reset(player, 1.0);
            }
        } else {
            buffer.decrease(player, 0.25);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        lastSprint.remove(event.getPlayer().getUniqueId());
        lastSneak.remove(event.getPlayer().getUniqueId());
    }
}
