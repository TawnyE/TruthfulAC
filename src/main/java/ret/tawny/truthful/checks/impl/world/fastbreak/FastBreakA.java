package ret.tawny.truthful.checks.impl.world.fastbreak;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@CheckData(order = 'A', type = CheckType.FAST_BREAK)
@SuppressWarnings("unused")
public final class FastBreakA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private final Map<UUID, Long> startTimes = new HashMap<>();

    @Override
    public void handlePacketPlayerReceive(final PacketEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.BLOCK_DIG) {
            final Player player = event.getPlayer();
            final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

            if (data == null || player.getGameMode().name().contains("CREATIVE"))
                return;

            EnumWrappers.PlayerDigType type = event.getPacket().getPlayerDigTypes().read(0);

            if (type == EnumWrappers.PlayerDigType.START_DESTROY_BLOCK) {
                startTimes.put(player.getUniqueId(), System.currentTimeMillis());
            } else if (type == EnumWrappers.PlayerDigType.STOP_DESTROY_BLOCK) {
                Long startTime = startTimes.remove(player.getUniqueId());
                if (startTime != null) {
                    long duration = System.currentTimeMillis() - startTime;

                    // Calculate expected time
                    // This is a simplified calculation.
                    // We need block hardness.
                    // Note: In a real anti-cheat, we'd need precise tool/enchantment calculations.

                    // Placeholder logic:
                    if (duration < 50) { // Impossible to break anything in < 50ms (1 tick) unless instant break
                        // Check for instant break conditions (high efficiency + weak block)
                        // If not instant break, flag.

                        if (buffer.increase(player, 1.0) > 5.0) {
                            flag(data, "FastBreak (Instant): " + duration + "ms");
                            buffer.reset(player, 2.0);
                        }
                    }
                }
            } else if (type == EnumWrappers.PlayerDigType.ABORT_DESTROY_BLOCK) {
                startTimes.remove(player.getUniqueId());
            }
        }
    }
}
