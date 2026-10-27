package ret.tawny.truthful.checks.impl.world.scaffold;

import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.ExemptionType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.world.BlockPropertyRegistry;
import ret.tawny.truthful.wrapper.impl.client.action.PlayerBlockPlacePacketWrapper;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ScaffoldMotion(C) - tower physics metronome. A legal jump-tower spends a
 * full jump arc (about eleven ticks) per level, so feet-column placements
 * that rise the equivalent of two jump-strength-scaled jumps inside sixteen
 * ticks mean the player is re-placing the moment the block bump shoves it
 * up. Feet-column placements record (tick, y) samples; only sustained
 * ground contact (two or more ground ticks) resets the window, so the
 * bounce-through landing tick of a tower never hides a metronome while
 * every legit fast-rise source - boost potions, levitation, slow falling,
 * liquid, climbables, riptide, pistons, slime, geysers, wind charges and
 * mace smashes - is skipped outright.
 */
@CheckData(order = 'C', type = CheckType.SCAFFOLD)
public final class ScaffoldMotion extends Check {

    private static final int HISTORY_TICKS = 24;
    private static final int WINDOW_TICKS = 16;
    private static final double FLAG_BUFFER = 4.0D;

    private final CheckBuffer buffer = new CheckBuffer(FLAG_BUFFER);

    private static final class Sample {
        final int tick;
        final double y;

        Sample(final int tick, final double y) {
            this.tick = tick;
            this.y = y;
        }
    }

    private static final class State {
        final ArrayDeque<Sample> history = new ArrayDeque<>();
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    @Override
    public void handleRelMove(final RelMovePacketWrapper wrapper) {
        final PlayerData data = wrapper.getPlayerData();
        if (data == null) return;

        final Player player = data.getPlayer();
        final PlayerBlockPlacePacketWrapper place = data.getCurrentBlockPlacement();
        if (place == null || place.getBlockPosition() == null || place.getBlockFace() == null) {
            buffer.decrease(player, 0.05D);
            return;
        }

        if (data.isAllowFlight() || data.isFlying() || data.isInsideVehicle()
                || data.getTicksSinceTeleport() < 5
                || data.isRiptiding()
                || data.isInLiquid() || data.isOnClimbable()
                || data.getTicksTracked() - data.getLastPistonTick() < 8
                || data.getTicksTracked() - data.getLastSlimeTick() < 8
                || data.getTicksTracked() - data.getPositionTracker().getLastGeyserTick() < 8
                || data.isExempt(ExemptionType.WIND_CHARGE) || data.isExempt(ExemptionType.MACE_SMASH)
                || data.getPotionLevel(PotionEffectType.LEVITATION) > 0
                || data.getPotionLevel(PotionEffectType.SLOW_FALLING) > 0) {
            buffer.decrease(player, 0.05D);
            return;
        }

        final Vector3i clicked = place.getBlockPosition();
        final BlockFace face = place.getBlockFace();
        final boolean replaceable = isReplaceable(
                data.getWorldCache().getBlockId(clicked.getX(), clicked.getY(), clicked.getZ()));
        final int targetX = replaceable ? clicked.getX() : clicked.getX() + face.getModX();
        final int targetY = replaceable ? clicked.getY() : clicked.getY() + face.getModY();
        final int targetZ = replaceable ? clicked.getZ() : clicked.getZ() + face.getModZ();

        if (!data.getWorldCache().isChunkLoaded(targetX >> 4, targetZ >> 4)) {
            buffer.decrease(player, 0.05D);
            return;
        }

        final int feetX = (int) Math.floor(data.getX());
        final int feetZ = (int) Math.floor(data.getZ());
        final int feetY = (int) Math.floor(data.getY());
        if (targetX != feetX || targetZ != feetZ
                || targetY < feetY - 1 || targetY > feetY + 1) {
            buffer.decrease(player, 0.2D);
            return;
        }

        final State st = states.computeIfAbsent(player.getUniqueId(), k -> new State());
        final int tick = data.getTicksTracked();
        final double y = data.getY();

        if (data.getGroundTicks() >= 2) {
            st.history.clear();
        }

        st.history.addLast(new Sample(tick, y));
        while (!st.history.isEmpty() && tick - st.history.peekFirst().tick > HISTORY_TICKS) {
            st.history.pollFirst();
        }

        final double threshold = 2.0D * (Math.max(0.42D, data.getJumpStrength()) / 0.42D) + 0.5D;
        double gain = 0.0D;
        int span = 0;
        for (final Sample sample : st.history) {
            final int dt = tick - sample.tick;
            if (dt > WINDOW_TICKS) continue;
            final double g = y - sample.y;
            if (g >= threshold && g >= gain) {
                gain = g;
                span = dt;
            }
        }

        if (span > 0) {
            st.history.clear();
            if (buffer.increase(player, 1.5D) > FLAG_BUFFER) {
                flag(data, String.format("tower gain=%.2f/%.2f in %dt", gain, threshold, span));
                buffer.reset(player, 2.0D);
            }
            return;
        }

        buffer.decrease(player, 0.2D);
    }

    private static boolean isReplaceable(final int globalId) {
        if (globalId == 0) return true;
        if (BlockPropertyRegistry.isLiquid(globalId)) return true;
        final String name = BlockPropertyRegistry.getName(globalId);
        return name.equals("SNOW") || name.equals("GRASS") || name.equals("SHORT_GRASS")
                || name.equals("TALL_GRASS") || name.equals("FERN") || name.equals("LARGE_FERN")
                || name.equals("DEAD_BUSH") || name.equals("FIRE") || name.endsWith("_AIR")
                || name.endsWith("_FIRE");
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
        buffer.remove(event.getPlayer());
    }
}
