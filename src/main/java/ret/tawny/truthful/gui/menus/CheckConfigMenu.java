package ret.tawny.truthful.gui.menus;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.gui.GuiConstants;
import ret.tawny.truthful.gui.GuiHolder;
import ret.tawny.truthful.gui.GuiItemFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CheckConfigMenu {

    public static String getTitle(CheckType type) {
        String pluginName = Truthful.getInstance().getConfiguration().getPluginDisplayName();
        return GuiConstants.PRIMARY + pluginName + " " + GuiConstants.DARK + "> " + GuiConstants.MUTED + type.getName();
    }

    public static void open(Player player, CheckType type) {
        open(player, type, 0);
    }

    public static void open(Player player, CheckType type, int page) {
        GuiHolder holder = new GuiHolder(GuiHolder.MenuType.CHECK_CONFIG, null, type, null, page);
        Inventory inv = Bukkit.createInventory(holder, 54, getTitle(type) + " " + GuiConstants.DARK + "(Page " + (page + 1) + ")");
        GuiItemFactory.fillGradientBorder(inv);

        List<Check> checks = new ArrayList<>();
        for (Check check : Truthful.getInstance().getCheckManager().getCollection()) {
            if (check.getType() == type) checks.add(check);
        }
        checks.sort(Comparator.comparingInt(Check::getOrder));

        int[] slots = { 10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43 };

        int start = page * slots.length;
        int end = Math.min(start + slots.length, checks.size());
        int slotIndex = 0;

        for (int i = start; i < end; i++) {
            Check check = checks.get(i);
            boolean enabled = check.isEnabled();
            boolean punish = Truthful.getInstance().getConfiguration()
                    .isPunishmentEnabled(check.getType().name(), String.valueOf(check.getOrder()));

            Material mat = enabled
                    ? GuiConstants.getMat("LIME_STAINED_GLASS_PANE", "STAINED_GLASS_PANE")
                    : GuiConstants.getMat("RED_STAINED_GLASS_PANE", "STAINED_GLASS_PANE");

            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(GuiConstants.DARK + GuiConstants.SYM_LINE + " " + GuiConstants.MUTED + "Check   " + (enabled ? GuiConstants.SUCCESS + "Enabled" : GuiConstants.ERROR + "Disabled"));
            lore.add(GuiConstants.DARK + GuiConstants.SYM_LINE + " " + GuiConstants.MUTED + "Punish  " + (punish ? GuiConstants.SUCCESS + "On" : GuiConstants.ERROR + "Off"));
            if (!check.getTags().isEmpty()) {
                lore.add(GuiConstants.DARK + GuiConstants.SYM_LINE + " " + GuiConstants.MUTED + "Tags    " + GuiConstants.SECONDARY + String.join(", ", check.getTags()));
            }
            lore.add("");
            lore.add(GuiConstants.SUCCESS + "Left-Click " + GuiConstants.DARK + "to toggle state");
            lore.add(GuiConstants.SECONDARY + "Right-Click " + GuiConstants.DARK + "for check settings");

            inv.setItem(slots[slotIndex++], GuiItemFactory.create(mat, (enabled ? GuiConstants.SUCCESS : GuiConstants.MUTED) + check.getFormattedName(), lore));
        }

        GuiItemFactory.fillEmpty(inv, slots, slotIndex);

        boolean allEnabled = true;
        for (Check c : checks) {
            if (!c.isEnabled()) { allEnabled = false; break; }
        }
        inv.setItem(49, GuiItemFactory.createToggleAll(allEnabled, type.getName() + " checks"));
        inv.setItem(45, GuiItemFactory.createBackButton(CategoryMenu.getCategoryForType(type)));

        if (page > 0) {
            inv.setItem(46, GuiItemFactory.create(Material.ARROW, GuiConstants.SECONDARY + "Previous Page", GuiConstants.MUTED + "Go to page " + page));
        }
        if (end < checks.size()) {
            inv.setItem(53, GuiItemFactory.create(Material.ARROW, GuiConstants.SECONDARY + "Next Page", GuiConstants.MUTED + "Go to page " + (page + 2)));
        }

        player.openInventory(inv);
    }

    public static void toggleAllForType(Player player, CheckType type) {
        boolean anyDisabled = false;
        for (Check check : Truthful.getInstance().getCheckManager().getCollection()) {
            if (check.getType() == type && !check.isEnabled()) {
                anyDisabled = true;
                break;
            }
        }
        boolean newState = anyDisabled;

        for (Check check : Truthful.getInstance().getCheckManager().getCollection()) {
            if (check.getType() == type) {
                check.setEnabled(newState);
                Truthful.getInstance().getConfiguration()
                        .setCheckEnabled(check.getType().name(), String.valueOf(check.getOrder()), newState);
            }
        }
    }

    public static void toggleGroundPunches(Player player) {
        boolean newState = !Truthful.getInstance().getConfiguration().shouldCountGroundPunches();
        Truthful.getInstance().getConfiguration().setCountGroundPunches(newState);
    }
}