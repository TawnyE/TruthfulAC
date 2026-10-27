package ret.tawny.truthful.checks.impl.world.fastbreak;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.checks.impl.world.WorldCheckSupport;
import ret.tawny.truthful.data.PlayerData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@CheckData(order = 'A', type = CheckType.FAST_BREAK)
public final class FastBreakA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private final Map<UUID, DigInfo> digMap = new ConcurrentHashMap<>();

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_DIGGING) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (WorldCheckSupport.skipBasic(data, player)) return;

        WrapperPlayClientPlayerDigging digging = new WrapperPlayClientPlayerDigging(event);
        DiggingAction action = digging.getAction();

        if (action == DiggingAction.START_DIGGING) {
            digMap.put(player.getUniqueId(), new DigInfo(data.getTicksTracked(), digging.getBlockPosition()));
            return;
        }

        if (action == DiggingAction.CANCELLED_DIGGING) {
            digMap.remove(player.getUniqueId());
            return;
        }

        if (action != DiggingAction.FINISHED_DIGGING) return;

        DigInfo info = digMap.remove(player.getUniqueId());
        if (info == null) return;

        int elapsed = data.getTicksTracked() - info.startTick;
        String block = data.getWorldCache().getBlockState(info.pos.getX(), info.pos.getY(), info.pos.getZ()).getType().getName().toLowerCase();

        int required = requiredTicks(block, data);
        if (elapsed < required) {
            if (buffer.increase(player, 1.5D) > 15.0D) {
                flag(data, String.format("FastBreak elapsed=%d req=%d block=%s", elapsed, required, block));
                buffer.reset(player, 5.0D);
            }
        } else buffer.decrease(player, 0.5D);
    }

    private int requiredTicks(String name, PlayerData data) {
        if (name.contains("grass") || name.contains("flower") || name.contains("torch")
                || name.contains("sapling") || name.contains("crop") || name.contains("mushroom")) {
            return 0;
        }

        // Conservative per-hardness minimums. The old values were so low
        // (obsidian 20 ticks vs ~188 bare-handed) and the efficiency/haste
        // deductions so blunt (flat -3 drove every requirement to zero) that
        // the check could not fire on a real fast-break. Requirements now
        // scale multiplicatively and hard blocks keep a real floor.
        double req = 2.0;
        if (name.contains("stone") || name.contains("deepslate") || name.contains("ore")
                || name.contains("brick") || name.contains("cobble")) {
            req = 3.0;
        }
        if (name.contains("ender_chest") || name.contains("anvil") || name.contains("enchanting_table")) {
            req = 15.0;
        }
        if (name.contains("obsidian") || name.contains("ancient_debris")) {
            req = 30.0;
        }

        int haste = data.getPotionLevel(PotionEffectType.HASTE);
        if (haste > 0) {
            req = req / (1.0D + 0.2D * haste);
        }

        int eff = data.getEnchantLevel("efficiency");
        if (eff >= 5) {
            // Efficiency V insta-mines soft blocks; hard blocks only get a
            // bounded reduction (eff V netherite obsidian is still 25+ ticks).
            req = req <= 3.0D ? 0.0D : Math.max(2.0D, req / 3.0D);
        } else if (eff >= 3) {
            req = Math.max(1.0D, req / 1.5D);
        }

        return (int) Math.floor(req);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        digMap.remove(event.getPlayer().getUniqueId());
    }

    private static final class DigInfo {
        final int startTick;
        final Vector3i pos;
        DigInfo(int startTick, Vector3i pos) { this.startTick = startTick; this.pos = pos; }
    }
}
