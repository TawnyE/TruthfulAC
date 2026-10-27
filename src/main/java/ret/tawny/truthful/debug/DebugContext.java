package ret.tawny.truthful.debug;

import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.data.world.CompensatedWorld;
import ret.tawny.truthful.utils.world.BlockPropertyRegistry;
import ret.tawny.truthful.utils.world.WorldUtils;

/**
 * DebugContext - rich per-flag diagnostic state, built only on flag paths
 * (never the hot loop). One string that answers "what was the player doing,
 * what did the engine believe, and what world was it standing on".
 */
public final class DebugContext {

    private DebugContext() {}

    public static String build(PlayerData d) {
        StringBuilder sb = new StringBuilder(160);
        int now = d.getTicksTracked();

        // --- motion inputs / states ---
        sb.append(String.format("in=[s:%b,j:%b,sh:%b]", sprint(d), jump(d), shift(d)));
        sb.append(String.format(" trk=[spr:%b,snk:%b]", d.isSprinting(), d.isSneaking()));
        sb.append(String.format(" gnd=[srv:%b,cli:%b,last:%b,air:%d]",
                d.isServerGround(), d.isClientGround(), d.isLastGround(), d.getAirTicks()));
        sb.append(String.format(" mom=[lX:%.4f,lY:%.4f,lZ:%.4f]",
                d.getLastDeltaX(), d.getLastDeltaY(), d.getLastDeltaZ()));

        // --- transitions (ticks since) ---
        sb.append(" t=[");
        sb.append("jmp:").append(now - d.getActionTracker().getLastUseItemTick() > 100000 ? "-" : jumpAge(d, now));
        sb.append(",glide:").append(now - d.getLastGlideTick());
        if (d.getLastGlideTick() == -100) sb.append("(never)");
        sb.append(",vel:").append(now - d.getLastVelocityTick());
        sb.append(",dmg:").append(now - d.getLastDamageTick());
        sb.append(",atk:").append(now - d.getLastAttackPacketTick());
        sb.append(",veh:").append(now - d.getLastVehicleTick());
        sb.append(",pi:").append(now - d.getLastPistonTick());
        sb.append(",plc:").append(now - d.getLastBlockPlaceTick());
        sb.append("]");

        // --- regime flags ---
        boolean gliding = d.isGliding();
        boolean liquid = d.isInLiquid();
        boolean climb = d.isOnClimbable();
        boolean web = d.isInWeb();
        sb.append(" rg=[gld:").append(gliding)
          .append(",liq:").append(liquid)
          .append(",clb:").append(climb)
          .append(",web:").append(web)
          .append(",veh:").append(d.isInsideVehicle())
          .append(",kbF:").append(hasFreshKb(d))
          .append("]");

        // --- knockback detail ---
        if (d.hasVelocity()) {
            org.bukkit.util.Vector v = d.getVelocities().getFreshVelocityVector(3);
            if (v != null) {
                sb.append(String.format(" kb=[f:%.3f,%.3f,%.3f]", v.getX(), v.getY(), v.getZ()));
            }
        }

        // --- bounce impact ---
        if (now - d.getPositionTracker().getBounceImpactTick() <= 3) {
            sb.append(String.format(" bn=%.4f", d.getPositionTracker().getBounceImpactY()));
        }

        // --- entity support ---
        double sup = d.getPositionTracker().getNearEntitySupportY();
        if (!Double.isNaN(sup)) sb.append(String.format(" entSup=%.2f", sup));

        return sb.toString();
    }

    /** 3x3 horizontal + 2-deep vertical block column around the player. */
    public static String blocks(PlayerData d) {
        StringBuilder sb = new StringBuilder(120);
        CompensatedWorld w = d.getWorldCache();
        int x = (int) Math.floor(d.getX());
        int y = (int) Math.floor(d.getY());
        int z = (int) Math.floor(d.getZ());
        try {
            sb.append(" feet=").append(n(BlockPropertyRegistry.getNameFast(w.getBlockId(x, y, z))));
            sb.append(" b1=").append(n(BlockPropertyRegistry.getNameFast(w.getBlockId(x, y - 1, z))));
            sb.append(" b2=").append(n(BlockPropertyRegistry.getNameFast(w.getBlockId(x, y - 2, z))));
            sb.append(" n=").append(n(BlockPropertyRegistry.getNameFast(w.getBlockId(x, y, z - 1))));
            sb.append(" s=").append(n(BlockPropertyRegistry.getNameFast(w.getBlockId(x, y, z + 1))));
            sb.append(" e=").append(n(BlockPropertyRegistry.getNameFast(w.getBlockId(x + 1, y, z))));
            sb.append(" w=").append(n(BlockPropertyRegistry.getNameFast(w.getBlockId(x - 1, y, z))));
            sb.append(" supProbe=").append(WorldUtils.hasSupportBelow(d, 0.10D));
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static String n(String s) {
        if (s == null) return "?";
        return s.length() > 18 ? s.substring(0, 18) : s;
    }

    private static boolean sprint(PlayerData d) {
        boolean known = d.getActionTracker().isInputKnown(d.getTicksTracked());
        return known ? d.getActionTracker().isInputSprint() : d.isSprinting();
    }

    private static boolean jump(PlayerData d) {
        boolean known = d.getActionTracker().isInputKnown(d.getTicksTracked());
        return known && d.getActionTracker().isInputJump();
    }

    private static boolean shift(PlayerData d) {
        boolean known = d.getActionTracker().isInputKnown(d.getTicksTracked());
        return known ? d.getActionTracker().isInputShift() : d.isSneaking();
    }

    private static String jumpAge(PlayerData d, int now) {
        return String.valueOf(now - d.getLastFireworkTick());
    }

    private static boolean hasFreshKb(PlayerData d) {
        org.bukkit.util.Vector v = d.getVelocities().getFreshVelocityVector(1);
        return v != null && v.lengthSquared() > 1.0E-6;
    }
}
