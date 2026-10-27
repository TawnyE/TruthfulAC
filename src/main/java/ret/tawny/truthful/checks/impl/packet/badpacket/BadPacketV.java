package ret.tawny.truthful.checks.impl.packet.badpacket;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSteerVehicle;
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
 * BadPacketV - spoofed steer values. Forward and sideways live between -1
 * and 1. NaN or anything past 2 isn't steering, it's packet forgery.
 */
@CheckData(order = 'V', type = CheckType.BAD_PACKET)
public final class BadPacketV extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.STEER_VEHICLE) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isServerFrozen()) return;

        float forward;
        float sideways;
        try {
            WrapperPlayClientSteerVehicle steer = new WrapperPlayClientSteerVehicle(event);
            forward = steer.getForward();
            sideways = steer.getSideways();
        } catch (Throwable ignored) {
            return;
        }

        if (!Float.isFinite(forward) || !Float.isFinite(sideways)
                || Math.abs(forward) > 2.0F || Math.abs(sideways) > 2.0F) {
            if (buffer.increase(player, 2.0) > 2.0) {
                flag(data, "Spoofed steer values");
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
