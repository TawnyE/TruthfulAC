package ret.tawny.truthful.checks.impl.badpackets;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.entity.Player;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;

@CheckData(order = 'A', type = CheckType.PACKET_ORDER)
@SuppressWarnings("unused")
public final class PacketOrderA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(3.0);
    private long lastPlace;

    @Override
    public void handlePacketPlayerReceive(final PacketEvent event) {
        final Player player = event.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null)
            return;

        if (event.getPacketType() == PacketType.Play.Client.BLOCK_PLACE) {
            lastPlace = System.currentTimeMillis();
        } else if (event.getPacketType() == PacketType.Play.Client.FLYING) {
            // If we placed a block, we expect an arm animation or interaction packet
            // shortly before/after.
            // This is a simplified check: ensure we don't place blocks without swinging
            // (NoSwing).
            // Note: Modern clients might send swing *after* place.

            // For this specific check, we'll look for "Post-Place" anomalies.
            // If a player sends a placement packet but no animation packet within a tick,
            // it's suspicious.
            // However, this requires tracking the animation packet.
        }
    }

    // Implementing a simpler "NoSwing" check for attacking/placing
    // This requires listening to ArmAnimation
}
