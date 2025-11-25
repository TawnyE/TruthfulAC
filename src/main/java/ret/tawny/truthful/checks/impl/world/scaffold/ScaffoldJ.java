package ret.tawny.truthful.checks.impl.world.scaffold;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;

@CheckData(order = 'J', type = CheckType.SCAFFOLD)
@SuppressWarnings("unused")
public final class ScaffoldJ extends Check {

    private final CheckBuffer buffer = new CheckBuffer(6.0);
    private float lastPlacePitch = 0f;
    private int lastPlaceTick = 0;

    @Override
    public void handlePacketPlayerReceive(final PacketEvent event) {
        // In 1.8 Protocol, BLOCK_PLACE is sent for interactions (Air Click) too.
        if (!event.getPacketType().equals(PacketType.Play.Client.BLOCK_PLACE)) return;

        final Player player = event.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null) return;

        // --- FIX 1: Must be holding a block ---
        // If holding a sword or air, you are interacting, not scaffolding.
        // getItemInHand() is deprecated but maintains cross-version compatibility best here.
        try {
            @SuppressWarnings("deprecation")
            ItemStack hand = player.getItemInHand();
            if (hand == null || !hand.getType().isBlock()) {
                return;
            }
        } catch (Exception e) {
            return;
        }

        // --- FIX 2: Must be moving ---
        // If standing still, static pitch is normal (you aren't moving your mouse).
        if (data.getDeltaXZ() < 0.1 && Math.abs(data.getDeltaY()) < 0.1) {
            buffer.decrease(player, 0.1);
            return;
        }

        // --- FIX 3: Ignore same-tick packets (Burst Clicks) ---
        // If you click 15 CPS, you send multiple packets per tick.
        // Pitch doesn't change between packets in the same tick.
        if (data.getTicksTracked() == lastPlaceTick) {
            return;
        }

        float pitch = data.getPitch();
        float yaw = data.getYaw();

        // Check 1: Static Pitch (Rotation Lock)
        // If pitch is IDENTICAL to last place packet, it's robotic.
        // Ignore 0.0 and 90.0 (common snapping points).
        if (Math.abs(pitch - lastPlacePitch) == 0.0 && pitch != 0.0 && pitch != 90.0 && Math.abs(pitch) < 89.9) {
            if (buffer.increase(player, 1.0) > 6.0) {
                flag(data, "Rotation Lock (Static Pitch). P: " + pitch);
                // Manual teleport removed
            }
        } else {
            buffer.decrease(player, 0.25);
        }

        // Check 2: Axis Snapping (Yaw)
        // Cheats often snap to 45, 90, 135, 180 degrees.
        if (yaw % 45.0 == 0.0 && data.getDeltaXZ() > 0.1) {
            if (buffer.increase(player, 1.0) > 6.0) {
                flag(data, "Rotation Lock (Axis Snap). Y: " + yaw);
            }
        }

        this.lastPlacePitch = pitch;
        this.lastPlaceTick = data.getTicksTracked();
    }
}