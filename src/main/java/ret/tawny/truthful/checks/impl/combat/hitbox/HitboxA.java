package ret.tawny.truthful.checks.impl.combat.hitbox;

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
 * HitboxA - the out-of-sight verdict. ReachA's Stage B does the one
 * raycast; when the ray misses EVERY legal view of the target, the miss
 * gets routed here. One judgment, two named outcomes - same split Grim
 * uses.
 */
@CheckData(order = 'A', type = CheckType.HITBOX)
public final class HitboxA extends Check {

    // Don't judge misses at point-blank range where sub-tick motion dominates.
    private static final double MIN_JUDGEMENT_DISTANCE = 0.60D;

    private static volatile HitboxA instance;

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    public HitboxA() {
        super();
        instance = this;
    }

    /**
     * Routed from ReachA Stage B when the attack ray missed every legal view
     * of the target. missDistance = closest the target box ever came to any
     * plausible eye position during the window.
     */
    public static void routeMiss(PlayerData data, double missDistance, long ping, int window, String debug,
                                 boolean targetIsPlayer) {
        HitboxA self = instance;
        if (self == null || data == null) return;

        Player player = data.getPlayer();
        if (player == null) return;

        // Non-player entities (NPCs, mobs, Citizens fake players) have
        // positions we cannot reliably verify: NPC plugins move entities
        // through NMS paths our packet tracker can't always follow, and
        // render offsets differ from packet positions. Grim's blacklist
        // philosophy: never judge what you can't verify. Hitbox misses on
        // non-players are resynced, never flagged. Real-player PvP keeps
        // full strictness.
        if (!targetIsPlayer) {
            self.buffer.decrease(player, 0.5D);
            return;
        }

        // DESYNC SANITY: a miss beyond 3 blocks is a tracking failure,
        // not a hitbox cheat - never flag it.
        if (missDistance > 3.0D) {
            self.buffer.decrease(player, 0.5D);
            return;
        }

        if (missDistance >= MIN_JUDGEMENT_DISTANCE) {
            double over = missDistance - MIN_JUDGEMENT_DISTANCE;
            if (self.buffer.increase(player, 1.0D + over * 1.5D) > 7.0D) {
                self.flag(data, String.format("Hitbox miss=%.3f ping=%d window=%d %s",
                        missDistance, ping, window, debug));
                self.buffer.reset(player, 2.5D);
            }
        } else {
            self.buffer.decrease(player, 0.3D);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}
