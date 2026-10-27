package ret.tawny.truthful.gui.menus;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.potion.PotionEffect;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.gui.GuiConstants;
import ret.tawny.truthful.gui.GuiHolder;
import ret.tawny.truthful.gui.GuiItemFactory;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class PlayerInfoMenu {

    private static final DecimalFormat COORD = new DecimalFormat("0.00");

    public static String getTitle(String playerName) {
        String pluginName = Truthful.getInstance().getConfiguration().getPluginDisplayName();
        return GuiConstants.PRIMARY + pluginName + " " + GuiConstants.DARK + "> " + GuiConstants.MUTED + "Info: " + playerName;
    }

    public static void open(Player admin, Player target) {
        GuiHolder holder = new GuiHolder(GuiHolder.MenuType.PLAYER_INFO, target.getName(), null, null, 0);
        Inventory inv = Bukkit.createInventory(holder, 54, getTitle(target.getName()));
        GuiItemFactory.fillGradientBorder(inv);
        inv.setItem(49, GuiItemFactory.createBackButton("Player Selection"));

        inv.setItem(4, GuiItemFactory.createPlayerHead(target, GuiConstants.ACCENT + GuiConstants.BOLD + target.getName()));

        update(inv, target);
        admin.openInventory(inv);
    }

    public static void update(Inventory inv, Player target) {
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(target);
        if (data == null) return;

        int vl = data.getVl();
        String vlColor = vl == 0 ? GuiConstants.SUCCESS : vl < 10 ? GuiConstants.SECONDARY : vl < 50 ? GuiConstants.WARNING : GuiConstants.ERROR;

        inv.setItem(19, GuiItemFactory.createGlowing(
                GuiConstants.getMat("COMPASS"),
                GuiConstants.SECONDARY + GuiConstants.BOLD + "Position",
                List.of(
                        GuiConstants.metric("X", COORD.format(data.getX())),
                        GuiConstants.metric("Y", COORD.format(data.getY())),
                        GuiConstants.metric("Z", COORD.format(data.getZ())),
                        "",
                        GuiConstants.metric("Yaw", COORD.format(data.getYaw())),
                        GuiConstants.metric("Pitch", COORD.format(data.getPitch())))));

        inv.setItem(20, GuiItemFactory.createGlowing(
                GuiConstants.getMat("OAK_BUTTON", "WOOD_BUTTON"),
                GuiConstants.SUCCESS + GuiConstants.BOLD + "Live KeyPresses",
                formatKeyPressLore(data, target)));

        int ping = (int) data.getPing();
        inv.setItem(21, GuiItemFactory.createGlowing(
                GuiConstants.getMat("CLOCK", "WATCH"),
                GuiConstants.WARNING + GuiConstants.BOLD + "Network",
                List.of(
                        GuiConstants.DARK + GuiConstants.LINE + " " + GuiConstants.MUTED + "Ping " + getPingColor(ping) + ping + "ms",
                        GuiConstants.metric("Timer", String.format("%.2fms", data.getTransactionTimerBalance())),
                        GuiConstants.metric("VL Total", vlColor + vl))));

        boolean groundMismatch = data.isClientGround() != data.isServerGround();
        inv.setItem(22, GuiItemFactory.createGlowing(
                GuiConstants.getMat("GRASS_BLOCK", "GRASS"),
                (groundMismatch ? GuiConstants.WARNING : GuiConstants.SUCCESS) + GuiConstants.BOLD + "Ground State",
                List.of(
                        GuiConstants.metric("Client Ground", data.isClientGround() ? "Yes" : "No"),
                        GuiConstants.metric("Server Ground", data.isServerGround() ? "Yes" : "No"),
                        GuiConstants.metric("Desync", groundMismatch ? "Yes" : "No"),
                        GuiConstants.metric("Air Ticks", String.valueOf(data.getAirTicks())))));

        inv.setItem(23, GuiItemFactory.createGlowing(
                GuiConstants.getMat("ENDER_EYE", "EYE_OF_ENDER"),
                GuiConstants.AQUA + GuiConstants.BOLD + "Sensitivity & Hardware DPI",
                formatSensitivityLore(data)));

        inv.setItem(24, GuiItemFactory.createGlowing(
                GuiConstants.getMat("NAME_TAG"),
                GuiConstants.LIGHT_PURPLE + GuiConstants.BOLD + "Client Metadata",
                List.of(
                        GuiConstants.metric("Brand", data.getClientBrand()),
                        GuiConstants.metric("Version", getClientVersionString(target)))));

        inv.setItem(25, GuiItemFactory.createGlowing(
                GuiConstants.getMat("POTION"),
                GuiConstants.PURPLE + GuiConstants.BOLD + "Effects",
                buildPotionLore(target.getActivePotionEffects())));
    }

    private static List<String> formatKeyPressLore(PlayerData data, Player target) {
        double dvX = data.getDeltaX() - (data.getLastDeltaX() * 0.546D);
        double dvZ = data.getDeltaZ() - (data.getLastDeltaZ() * 0.546D);

        double yawRad = Math.toRadians(data.getYaw());
        double sinYaw = Math.sin(yawRad);
        double cosYaw = Math.cos(yawRad);

        double localForward = -dvX * sinYaw + dvZ * cosYaw;
        double localStrafe  = -dvX * cosYaw - dvZ * sinYaw;

        boolean keyW = localForward > 0.003D;
        boolean keyS = localForward < -0.003D;
        boolean keyA = localStrafe < -0.003D;
        boolean keyD = localStrafe > 0.003D;

        boolean keySpace = (data.getAirTicks() > 0 && data.getAirTicks() <= 4 && data.getDeltaY() > 0.0D) || !data.isServerGround();

        boolean keyShift = data.isSneaking() || (target != null && target.isSneaking());
        boolean keyCtrl  = data.isSprinting();

        int ticksNow = data.getTicksTracked();
        boolean keyLMB = (ticksNow - data.getLastAttackPacketTick() <= 3) || data.isDigging();
        boolean keyRMB = (ticksNow - data.getLastBlockPlaceTick() <= 3) || data.isUsingItem();

        String w = keyW ? "§a§l[W]" : "§8[W]";
        String a = keyA ? "§a§l[A]" : "§8[A]";
        String s = keyS ? "§a§l[S]" : "§8[S]";
        String d = keyD ? "§a§l[D]" : "§8[D]";

        String space = keySpace ? "§a§l[SPACE]" : "§8[SPACE]";
        String shift = keyShift ? "§a§l[SHIFT]" : "§8[SHIFT]";
        String ctrl  = keyCtrl  ? "§a§l[CTRL]"  : "§8[CTRL]";

        String lmb = keyLMB ? "§a§l[LMB]" : "§8[LMB]";
        String rmb = keyRMB ? "§a§l[RMB]" : "§8[RMB]";

        List<String> lore = new ArrayList<>();
        lore.add(GuiConstants.DARK + "Live packet input vector");
        lore.add("");
        lore.add("   " + w);
        lore.add(" " + a + " " + s + " " + d);
        lore.add("");
        lore.add(space + "  " + shift + "  " + ctrl);
        lore.add(lmb + "  " + rmb);
        lore.add("");
        lore.add(GuiConstants.MUTED + "Updates live (100ms)");
        return lore;
    }

    private static List<String> formatSensitivityLore(PlayerData data) {
        int sensPercent = data.getSensitivityPercent();
        List<String> lore = new ArrayList<>();

        if (sensPercent < 0) {
            int samples = data.getSensitivitySampleCount();
            lore.add(GuiConstants.MUTED + "Learning rotation step...");
            lore.add(GuiConstants.DARK + "Progress: " + GuiConstants.HIGHLIGHT + samples + "/8 " + GuiConstants.MUTED + "samples");
            lore.add(GuiConstants.DARK + "Rotate mouse vertically/horizontally.");
            return lore;
        }

        int displayPercent = Math.round(sensPercent);

        double sensVal = displayPercent / 200.0D;
        double f = sensVal * 0.6D + 0.2D;
        double gcdStep = f * f * f * 1.2D;

        double countsPer360 = 360.0D / gcdStep;
        int estimatedDPI;
        double cm360;

        if (countsPer360 > 12000) {
            estimatedDPI = 400;
            cm360 = (countsPer360 * 2.54D) / 400.0D;
        } else if (countsPer360 > 6000) {
            estimatedDPI = 800;
            cm360 = (countsPer360 * 2.54D) / 800.0D;
        } else if (countsPer360 > 3000) {
            estimatedDPI = 1200;
            cm360 = (countsPer360 * 2.54D) / 1200.0D;
        } else if (countsPer360 > 1500) {
            estimatedDPI = 1600;
            cm360 = (countsPer360 * 2.54D) / 1600.0D;
        } else {
            estimatedDPI = 3200;
            cm360 = (countsPer360 * 2.54D) / 3200.0D;
        }

        lore.add(GuiConstants.metric("Sensitivity", displayPercent + "% (" + String.format("%.2fx", f) + ")"));
        lore.add(GuiConstants.metric("GCD Step", String.format("%.6f", gcdStep)));
        lore.add(GuiConstants.metric("Est. Mouse DPI", estimatedDPI + " DPI"));
        lore.add(GuiConstants.metric("Turn Distance", String.format("%.1f cm/360", cm360)));
        lore.add("");
        lore.add(GuiConstants.SUCCESS + GuiConstants.SYM_CHECK + " Valid Vanilla Grid");
        return lore;
    }

    private static List<String> buildPotionLore(Collection<PotionEffect> effects) {
        List<String> lore = new ArrayList<>();
        if (effects.isEmpty()) {
            lore.add(GuiConstants.MUTED + "None");
            return lore;
        }
        for (PotionEffect effect : effects) {
            lore.add(GuiConstants.DARK + GuiConstants.BULLET + " " + GuiConstants.HIGHLIGHT
                    + effect.getType().getName() + " " + (effect.getAmplifier() + 1));
        }
        return lore;
    }

    private static String getClientVersionString(Player player) {
        try {
            ClientVersion version = PacketEvents.getAPI().getPlayerManager().getClientVersion(player);
            if (version != null) return version.name().replace("V_", "").replace("_", ".");
        } catch (Throwable ignored) {}
        return "Unknown";
    }

    private static String getPingColor(int ping) {
        if (ping < 50) return GuiConstants.SUCCESS;
        if (ping < 100) return GuiConstants.SECONDARY;
        if (ping < 200) return GuiConstants.WARNING;
        return GuiConstants.ERROR;
    }
}