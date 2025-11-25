package ret.tawny.truthful.checks.impl.badpackets;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.entity.Player;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;

@CheckData(order = 'C', type = CheckType.BAD_PACKET)
@SuppressWarnings("unused")
public final class TransactionListener extends Check {

    @Override
    public void handlePacketPlayerReceive(final PacketEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.TRANSACTION) {
            final Player player = event.getPlayer();
            final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

            if (data == null)
                return;

            short id = event.getPacket().getShorts().read(0);
            data.handleTransaction(id);
        }
    }
}
