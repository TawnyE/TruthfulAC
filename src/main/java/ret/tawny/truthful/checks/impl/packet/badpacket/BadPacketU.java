package ret.tawny.truthful.checks.impl.packet.badpacket;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;

/**
 * BadPacketU - impossible window-click buttons.
 *
 * Vanilla constrains the mouse/drag button id per click type: PICKUP uses 0-2,
 * QUICK_MOVE 0-1, SWAP 0-40 (hotbar + offhand), THROW 0-1, CLONE 2 (creative),
 * etc. Out-of-range ids are directly impossible from a real client.
 */
@CheckData(order = 'U', type = CheckType.BAD_PACKET)
public final class BadPacketU extends Check {

    private final CheckBuffer buffer = new CheckBuffer(5.0);

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.CLICK_WINDOW) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isServerFrozen()) return;

        int button;
        String clickType;
        try {
            WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);
            button = click.getButton();
            clickType = click.getWindowClickType() == null ? "" : click.getWindowClickType().name();
        } catch (Throwable t) {
            return;
        }

        boolean bad = switch (clickType) {
            case "PICKUP" -> button < 0 || button > 2;
            case "QUICK_MOVE" -> button < 0 || button > 1;
            case "SWAP" -> button < 0 || button > 40;
            case "THROW" -> button < 0 || button > 1;
            case "QUICK_CRAFT" -> button < 0 || button > 10;
            case "PICKUP_ALL" -> button != 0;
            case "CLONE" -> button != 2;
            default -> false;
        };

        if (bad) {
            if (buffer.increase(player, 1.0) > 2.0) {
                flag(data, String.format("Invalid click button (type=%s button=%d)", clickType, button));
                buffer.reset(player, 1.0);
            }
        } else {
            buffer.decrease(player, 0.2);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}
