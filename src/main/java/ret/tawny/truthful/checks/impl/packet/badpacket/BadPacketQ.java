package ret.tawny.truthful.checks.impl.packet.badpacket;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
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
 * BadPacketQ - hotbar slot past 8. The bar ends at 8. Cancelled on sight.
 */
@CheckData(order = 'Q', type = CheckType.BAD_PACKET)
public final class BadPacketQ extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public boolean rejectsPackets() { return true; }

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.HELD_ITEM_CHANGE) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isServerFrozen()) return;

        int slot;
        try {
            slot = new WrapperPlayClientHeldItemChange(event).getSlot();
        } catch (Throwable ignored) {
            return;
        }

        if (slot > 8) {
            // Direct rejection honors the per-check "cancel" switch; observe
            // mode still flags below.
            if (mayCancelPackets()) event.setCancelled(true);
            if (buffer.increase(player, 2.0) > 2.0) {
                flag(data, String.format("Oversized slot %d", slot));
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
