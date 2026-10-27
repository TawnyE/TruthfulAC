package ret.tawny.truthful.utils.world;

import ret.tawny.truthful.data.PlayerData;


public final class HeadHitUtils {

    private HeadHitUtils() {}

    /**
     * Returns true if the player is currently under a block
     * or within the vanilla head-hit release window.
     */
    public static boolean isInHeadHitWindow(PlayerData data) {
        if (data.isUnderBlock()) {
            return true;
        }

        int lastUnder = data.getLastUnderBlockTick();
        if (lastUnder <= 0) {
            return false;
        }

        int ticksSince = data.getTicksTracked() - lastUnder;
        return ticksSince >= 0 && ticksSince <= 3;
    }

    /**
     * Returns horizontal leniency to apply during head-hit windows.
     */
    public static double getHorizontalLeniency(PlayerData data) {
        if (data.isUnderBlock()) {
            return 0.15;
        }

        int ticksSince = data.getTicksTracked() - data.getLastUnderBlockTick();
        if (ticksSince == 1) return 0.12;
        if (ticksSince == 2) return 0.08;
        if (ticksSince == 3) return 0.04;

        return 0.0;
    }
}
