package ret.tawny.truthful.checks.impl.packet.badpacket;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import org.bukkit.GameMode;
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
 * BadPacketZ - spectate packets outside spectator mode. Only spectators get
 * to teleport their camera to entities. Anyone else sending these is up to
 * something.
 */
@CheckData(order = 'Z', type = CheckType.BAD_PACKET)
public final class BadPacketZ extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public boolean rejectsPackets() { return true; }

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.SPECTATE) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isServerFrozen()) return;

        // Server-trusted gamemode cache - a live getGameMode() here ran on
        // the packet thread (Folia-illegal, racy on Paper).
        GameMode mode = data.getGameMode();

        if (mode != GameMode.SPECTATOR) {
            // Direct rejection honors the per-check "cancel" switch; observe
            // mode still flags below.
            if (mayCancelPackets()) event.setCancelled(true);
            if (buffer.increase(player, 2.0) > 2.0) {
                flag(data, "Spectate outside spectator");
                buffer.reset(player, 1.0);
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
