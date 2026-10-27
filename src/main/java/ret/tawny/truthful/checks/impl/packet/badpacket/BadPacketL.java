package ret.tawny.truthful.checks.impl.packet.badpacket;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
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
 * BadPacketL - teleport flood. The far-position disabler works by forcing
 * the server to correct constantly. Past a dozen corrections in 5 seconds
 * nobody is playing legit anymore, pearls and chorus included.
 */
@CheckData(order = 'L', type = CheckType.BAD_PACKET)
public final class BadPacketL extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.TELEPORT_CONFIRM) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isServerFrozen()) return;

        if (data.getInvalidTeleportConfirms() > 3) {
            if (buffer.increase(player, 2.0) > 4.0) {
                flag(data, "Invalid teleport confirmation (ID not pending x" + data.getInvalidTeleportConfirms() + ")");
                buffer.reset(player, 2.0);
            }
            return;
        }

        boolean flooding;
        try {
            flooding = data.getTeleportQueue().isFlooding();
        } catch (Throwable ignored) {
            return;
        }

        if (flooding) {
            if (buffer.increase(player, 1.5) > 6.0) {
                flag(data, "Teleport flood");
                buffer.reset(player, 3.0);
            }
        } else {
            buffer.decrease(player, 0.5);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}
