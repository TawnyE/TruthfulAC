package ret.tawny.truthful.checks.impl.packet.badpacket;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
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
 * BadPacketA - spoofed abilities. Flying without the server ever allowing
 * flight. Creative and spectator grant it, everyone else flapping around
 * up there is lying.
 */
@CheckData(order = 'A', type = CheckType.BAD_PACKET)
public final class BadPacketA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        // Runs on every packet on purpose: the state read is two cached
        // booleans, and a fly hacker should trip this within a second.
        if (!(event.getPlayer() instanceof Player player)) return;
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isServerFrozen()) return;

        boolean flying;
        boolean allowed;
        try {
            flying = data.isFlying();
            allowed = data.isAllowFlight();
        } catch (Throwable ignored) {
            return;
        }

        if (flying && !allowed) {
            // Real flight is airborne by definition. Click/placement storms
            // can sample a transient flying-cache blip while the player is
            // standing on the ground - never count those.
            if (data.isGliding() || data.getAirTicks() < 5) {
                buffer.decrease(player, 0.5);
                return;
            }
            if (buffer.increase(player, 1.5) > 4.0) {
                flag(data, "Flying without permission");
                buffer.reset(player, 2.0);
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
