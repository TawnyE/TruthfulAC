package ret.tawny.truthful.gui.menus;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.config.api.Configuration;
import ret.tawny.truthful.gui.GuiConstants;
import ret.tawny.truthful.gui.GuiHolder;
import ret.tawny.truthful.gui.GuiItemFactory;

import java.util.ArrayList;
import java.util.List;

public final class CheckDetailsMenu {

    private final Check check;

    public CheckDetailsMenu(Check check) {
        this.check = check;
    }

    public static String getTitle(Check check) {
        String pluginName = Truthful.getInstance().getConfiguration().getPluginDisplayName();
        return GuiConstants.PRIMARY + pluginName + " " + GuiConstants.DARK + "> " + GuiConstants.MUTED + check.getFormattedName();
    }

    public void open(Player player) {
        GuiHolder holder = new GuiHolder(GuiHolder.MenuType.CHECK_DETAILS, check.getFormattedName(), check.getType(), null, 0);
        Inventory inv = Bukkit.createInventory(holder, 27, getTitle(this.check));
        GuiItemFactory.fillGradientBorder(inv);

        Configuration config = Truthful.getInstance().getConfiguration();
        boolean checkEnabled = this.check.isEnabled();
        String lagbackRaw = config.getCheckLagbackRaw(this.check.getType().name(), String.valueOf(this.check.getOrder()));
        boolean lagbackEnabled = config.isCheckLagbackEnabled(this.check.getType().name(), String.valueOf(this.check.getOrder()));
        boolean punishmentEnabled = config.isPunishmentEnabled(this.check.getType().name(), String.valueOf(this.check.getOrder()));

        Material woolMat = checkEnabled ? GuiConstants.getMat("GREEN_WOOL", "WOOL") : GuiConstants.getMat("RED_WOOL", "WOOL");
        String checkStatusStr = checkEnabled ? GuiConstants.SUCCESS + "Enabled" : GuiConstants.ERROR + "Disabled";

        List<String> checkLore = new ArrayList<>();
        checkLore.add("");
        checkLore.add(GuiConstants.metric("Status", checkStatusStr));
        if (!this.check.getTags().isEmpty()) {
            checkLore.add(GuiConstants.metric("Tags", GuiConstants.SECONDARY + String.join(", ", this.check.getTags())));
        }
        checkLore.add("");
        checkLore.add(GuiConstants.SECONDARY + "Click to toggle");

        ItemStack checkItem = GuiItemFactory.create(woolMat, (checkEnabled ? GuiConstants.SUCCESS : GuiConstants.ERROR) + GuiConstants.BOLD + "Check Status", checkLore);

        boolean inherit = lagbackRaw == null || lagbackRaw.equalsIgnoreCase("inherit");
        Material lagbackMat = inherit ? GuiConstants.getMat("ANVIL") : lagbackEnabled ? GuiConstants.getMat("SLIME_BLOCK") : GuiConstants.getMat("MAGMA_BLOCK");
        String lagbackStatusStr = inherit ? "&7Inherit" : lagbackEnabled ? GuiConstants.SUCCESS + "On" : GuiConstants.ERROR + "Off";

        List<String> lagbackLore = new ArrayList<>();
        lagbackLore.add("");
        lagbackLore.add(GuiConstants.metric("Status", lagbackStatusStr));
        lagbackLore.add(GuiConstants.metric("Lagback VL", String.valueOf(config.getCheckLagbackVl(this.check.getType().name(), String.valueOf(this.check.getOrder())))));
        lagbackLore.add("");
        lagbackLore.add(GuiConstants.SECONDARY + "Click to toggle");

        ItemStack lagbackItem = GuiItemFactory.create(lagbackMat, (lagbackEnabled ? GuiConstants.SUCCESS : GuiConstants.ERROR) + GuiConstants.BOLD + "Lagback Status", lagbackLore);

        Material punishmentMat = punishmentEnabled ? GuiConstants.getMat("DIAMOND_SWORD") : GuiConstants.getMat("WOODEN_SWORD", "WOOD_SWORD");
        String punishmentStatusStr = punishmentEnabled ? GuiConstants.SUCCESS + "On" : GuiConstants.ERROR + "Off";

        List<String> punishmentLore = new ArrayList<>();
        punishmentLore.add("");
        punishmentLore.add(GuiConstants.metric("Status", punishmentStatusStr));
        punishmentLore.add(GuiConstants.metric("Punish VL", String.valueOf(config.getPunishmentVl(this.check.getType().name(), String.valueOf(this.check.getOrder())))));
        punishmentLore.add("");
        punishmentLore.add(GuiConstants.SECONDARY + "Click to toggle");

        ItemStack punishmentItem = GuiItemFactory.create(punishmentMat, (punishmentEnabled ? GuiConstants.SUCCESS : GuiConstants.ERROR) + GuiConstants.BOLD + "Punishment Status", punishmentLore);

        ItemStack backItem = GuiItemFactory.createBackButton(this.check.getType().getName() + " checks");

        inv.setItem(10, checkItem);
        inv.setItem(12, lagbackItem);
        inv.setItem(14, punishmentItem);
        if (this.check.rejectsPackets()) {
            boolean cancelOn = config.isCheckCancelEnabled(
                    this.check.getType().name(), String.valueOf(this.check.getOrder()));

            Material cancelMat = cancelOn ? GuiConstants.getMat("BARRIER") : GuiConstants.getMat("GLOWSTONE");
            String cancelStatusStr = cancelOn
                    ? GuiConstants.SUCCESS + "Reject (security default)"
                    : GuiConstants.ERROR + "Observe only";

            List<String> cancelLore = new ArrayList<>();
            cancelLore.add("");
            cancelLore.add(GuiConstants.metric("Mode", cancelStatusStr));
            cancelLore.add(GuiConstants.MUTED + "Direct packet rejection,");
            cancelLore.add(GuiConstants.MUTED + "independent of punishment");
            cancelLore.add(GuiConstants.MUTED + "and lagback switches.");
            cancelLore.add("");
            cancelLore.add(GuiConstants.SECONDARY + "Click to toggle");

            ItemStack cancelItem = GuiItemFactory.create(cancelMat,
                    (cancelOn ? GuiConstants.SUCCESS : GuiConstants.ERROR) + GuiConstants.BOLD + "Packet Rejection",
                    cancelLore);
            inv.setItem(16, cancelItem);
            inv.setItem(22, backItem);
        } else {
            inv.setItem(16, backItem);
        }

        player.openInventory(inv);
    }

    public static Check getCheckFromTitle(String checkName) {
        if (checkName == null) return null;
        String cleanName = ChatColor.stripColor(checkName).trim();
        for (Check check : Truthful.getInstance().getCheckManager().getCollection()) {
            if (ChatColor.stripColor(check.getFormattedName()).equalsIgnoreCase(cleanName)) {
                return check;
            }
        }
        return null;
    }
}