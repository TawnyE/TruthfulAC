package ret.tawny.truthful.checks.impl.combat.anchor;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.action.PlayerBlockPlacePacketWrapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AnchorAuraA - charge->detonate timing.
 *
 * Fixes over the old version:
 * - No live inventory reads on the packet thread (uses the cached hand state
 *   refreshed on the main thread).
 * - Actually verifies the clicked block is a RESPawn anchor via the compensated
 *   world. Random item interactions can no longer feed this check.
 * - The "currently looking at a block" guard was meaningless before
 *   (currentBlockPlacement is never cleared), so it is replaced by the anchor
 *   verification itself.
 */
@CheckData(order = 'A', type = CheckType.ANCHOR)
public final class AnchorAuraA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private final Map<UUID, Long> lastChargeTime = new ConcurrentHashMap<>();

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT)
            return;

        final Player player = (Player) event.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null) return;

        PlayerBlockPlacePacketWrapper place = data.getCurrentBlockPlacement();
        if (place == null || place.getBlockPosition() == null) return;
        Vector3i pos = place.getBlockPosition();

        String blockName = data.getWorldCache().getBlockState(pos.getX(), pos.getY(), pos.getZ())
                .getType().getName().toUpperCase();
        if (!blockName.equals("RESPAWN_ANCHOR")) {
            return;
        }

        long now = System.currentTimeMillis();

        if (data.isHoldingGlowstone()) {
            // Charging the anchor.
            lastChargeTime.put(player.getUniqueId(), now);
            buffer.decrease(player, 0.05);
            return;
        }

        // Interacting with an anchor without glowstone = detonation tap.
        Long lastCharge = lastChargeTime.get(player.getUniqueId());
        if (lastCharge == null) return;

        long diff = now - lastCharge;
        // Human charge->detonate is click -> release/hotkey -> click: 100ms+.
        // Sub-60ms sustained is macro territory. Buffered for jitter.
        if (diff >= 0 && diff < 60) {
            if (buffer.increase(player, 1.5) > 6.0) {
                flag(data, String.format("Fast Anchor (charge->detonate) delay=%dms", diff));
                buffer.reset(player, 3.0);
            }
        } else {
            buffer.decrease(player, 0.1);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        lastChargeTime.remove(event.getPlayer().getUniqueId());
    }
}
