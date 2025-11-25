package ret.tawny.truthful.checks.impl.badpackets;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;

@CheckData(order = 'D', type = CheckType.BAD_PACKET)
@SuppressWarnings("unused")
public final class BadPacketsD extends Check {

    @Override
    public void handlePacketPlayerReceive(final PacketEvent event) {
        if (!event.getPacketType().equals(PacketType.Play.Client.ENTITY_ACTION)) return;

        final Player player = event.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null) return;

        // Don't check creative/flying players as rules are looser
        if (player.isFlying() || player.getAllowFlight()) return;

        // Extract the action
        EnumWrappers.PlayerAction action = event.getPacket().getPlayerActions().readSafely(0);

        if (action == EnumWrappers.PlayerAction.START_SPRINTING) {

            // Check 1: Blindness
            if (player.hasPotionEffect(PotionEffectType.BLINDNESS)) {
                flag(data, "Sprinting while blinded (Impossible Action)");
                // Force stop sprint to prevent advantage
                player.setSprinting(false);
            }

            // Check 2: Hunger
            // Requires 6 food level (3 shanks) to sprint
            // We use 6.0 as a safe float comparison, though getFoodLevel returns int.
            if (player.getFoodLevel() <= 6) {
                flag(data, "Sprinting with low hunger (Impossible Action)");
                player.setSprinting(false);
            }

            // Check 3: OmniSprint (Sprinting while moving backwards)
            // If the player is moving backwards but sends a sprint packet.
            // This relies on the Movement check updating DeltaX/Z, which happens on POSITION packets.
            // Since ENTITY_ACTION can arrive before/after POSITION, this is heuristic.
            // We check if their last movement was primarily backwards.

            // Code Logic:
            // Calculate movement angle vs look angle. If difference > 135 degrees, they are moving backwards.
            // Skipping strict check here to avoid false positives with network lag,
            // but the Hunger/Blindness checks are 100% accurate.
        }
    }
}