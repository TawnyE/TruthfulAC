package ret.tawny.truthful.checks.impl.packet.crasher;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.network.ChannelValidator;

/**
 * CrasherA - CustomPayload crash/exploit protection.
 *
 * Channel names are validated per client protocol version (see
 * {@link ChannelValidator}): a modern, syntactically valid namespaced channel
 * of any length up to the platform bound is accepted - a hardcoded length
 * cutoff used to reject perfectly legal modded channels. Payload size bounds
 * are unchanged and remain the crasher-specific verdict.
 *
 * Direct packet rejection is gated behind the per-check "cancel" switch
 * (default on). With cancel disabled the check is observe-only: it flags and
 * alerts but lets the payload through, so operators can calibrate a
 * complementary packet filter without double enforcement.
 */
@CheckData(order = 'A', type = CheckType.CRASHER)
@SuppressWarnings("unused")
public final class CrasherA extends Check {

    private static final int MAX_PAYLOAD_SIZE = 32767;
    private static final int SUSPICIOUS_PAYLOAD_SIZE = 30000;
    private static final int BOOK_MAX_PAYLOAD = 25000;

    private final CheckBuffer buffer = new CheckBuffer(5.0);

    @Override
    public boolean rejectsPackets() { return true; }

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
            final Player player = (Player) event.getPlayer();
            final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
            if (data == null)
                return;

            if (data.isServerFrozen() || data.getTickFreezeGraceTicks() > 0) {
                return;
            }

            WrapperPlayClientPluginMessage wrapper;
            try {
                wrapper = new WrapperPlayClientPluginMessage(event);
            } catch (Throwable ignored) {
                return;
            }
            String channel = wrapper.getChannelName();
            if (channel == null) {
                return;
            }

            // Check 1: channel syntax per the client's protocol version.
            // Modern channels are identifiers - pattern validation with the
            // platform length bound, never a bare hardcoded cutoff.
            int protocol;
            try {
                com.github.retrooper.packetevents.protocol.player.ClientVersion version =
                        event.getUser().getClientVersion();
                protocol = version == null ? 0 : version.getProtocolVersion();
            } catch (Throwable ignored) {
                protocol = 0;
            }
            // Unmapped/future versions read as modern; only a properly mapped
            // legacy client can be < 393.
            if (!ChannelValidator.isValidChannel(channel, protocol <= 0
                    ? ChannelValidator.MODERN_IDENTIFIER_PROTOCOL : protocol)) {
                rejectPacket(event, data, String.format(
                        "Malformed channel (proto %d): '%s'", protocol,
                        channel.length() > 48 ? channel.substring(0, 48) + "..." : channel));
                return;
            }

            // Check 2: Payload Size
            byte[] payload = wrapper.getData();
            if (payload == null) {
                return;
            }

            int payloadSize = payload.length;
            boolean isBookChannel = channel.contains("book") || channel.contains("MC|BEdit")
                    || channel.contains("MC|BSign");
            int maxAllowedSize = isBookChannel ? BOOK_MAX_PAYLOAD : SUSPICIOUS_PAYLOAD_SIZE;

            // Block massive payloads (crash exploit)
            if (payloadSize > MAX_PAYLOAD_SIZE) {
                rejectPacket(event, data, String.format("Oversized payload (Crasher): %d bytes", payloadSize));
                return;
            }

            // Flag suspicious payloads
            if (payloadSize > maxAllowedSize) {
                if (mayCancelPackets()) {
                    event.setCancelled(true);
                }
                if (buffer.increase(player, 2.0) > 3.0) {
                    flag(data, String.format("Large payload on %s: %d bytes", channel, payloadSize));
                    buffer.reset(player, 1.0);
                }
            } else {
                buffer.decrease(player, 0.1);
            }
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}
