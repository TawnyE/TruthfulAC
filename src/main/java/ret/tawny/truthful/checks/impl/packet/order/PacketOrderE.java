package ret.tawny.truthful.checks.impl.packet.order;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;

@CheckData(order = 'E', type = CheckType.PACKET_ORDER)
public final class PacketOrderE extends Check {

    private final CheckBuffer buffer = new CheckBuffer(5.0);

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;

        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;

        final Player player = (Player) event.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null) return;

        // Logic: Inventory Aura
        // You cannot attack entities while an inventory (Chest, Inv, Crafting) is open.
        // We check if the inventory has been open for at least 5 ticks to avoid "Close->Attack" latency flags.

        if (data.isInventoryOpen()) {
            // How long has it been open?
            int openTime = data.getTicksTracked() - data.getInventoryOpenTick();

            if (openTime > 5) {
                if (buffer.increase(player, 2.0) > 4.0) {
                    flag(data, "Inventory Aura (Attacking inside GUI)");
                    buffer.reset(player, 2.0);
                }
            }
            data.setInventoryOpen(false);
        } else {
            buffer.decrease(player, 0.5);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}