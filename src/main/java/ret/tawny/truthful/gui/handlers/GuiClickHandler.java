package ret.tawny.truthful.gui.handlers;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.config.api.Configuration;
import ret.tawny.truthful.gui.GuiConstants;
import ret.tawny.truthful.gui.GuiHolder;
import ret.tawny.truthful.gui.menus.*;

public final class GuiClickHandler implements Listener {

    public GuiClickHandler() {
        Bukkit.getPluginManager().registerEvents(this, Truthful.getInstance().getPlugin());
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof GuiHolder holder)) return;

        // Cancel event IMMEDIATELY - item theft is impossible
        e.setCancelled(true);

        if (e.getCurrentItem() == null || !e.getCurrentItem().hasItemMeta()) return;

        Player player = (Player) e.getWhoClicked();
        ItemStack item = e.getCurrentItem();
        // Meta can exist without a display name; stripColor(null) NPEs.
        if (!item.getItemMeta().hasDisplayName()) return;
        String name = ChatColor.stripColor(item.getItemMeta().getDisplayName());
        if (name == null) name = "";
        Material mat = item.getType();
        String matName = mat.name();

        if (matName.contains("GLASS_PANE") && (name == null || name.trim().isEmpty())) return;

        if (matName.contains("ARROW") && name.contains("Back")) {
            playSound(player, Sound.UI_BUTTON_CLICK);
            handleBack(player, holder);
            return;
        }

        if (matName.contains("ARROW") && (name.contains("Next Page") || name.contains("Previous Page"))) {
            playSound(player, Sound.UI_BUTTON_CLICK);
            handlePagination(player, holder, name);
            return;
        }

        switch (holder.getMenuType()) {
            case MAIN -> {
                playSound(player, Sound.UI_BUTTON_CLICK);
                handleDashboard(player, matName, name);
            }
            case CATEGORIES -> handleCategories(player, matName, name);
            case CHECK_TYPES -> {
                CheckType type = getCheckTypeByName(name);
                if (type != null) {
                    playSound(player, Sound.UI_BUTTON_CLICK);
                    CheckConfigMenu.open(player, type);
                }
            }
            case CHECK_CONFIG -> handleCheckConfig(player, holder, e.isRightClick(), name);
            case CHECK_DETAILS -> handleCheckDetails(player, holder, name);
            case PLAYER_SELECT -> {
                if (matName.contains("HEAD") || matName.contains("SKULL")) {
                    playSound(player, Sound.UI_BUTTON_CLICK);
                    if ("Logs".equalsIgnoreCase(holder.getCategory())) {
                        LogsMenu.open(player, name);
                    } else {
                        Player target = Bukkit.getPlayer(name);
                        if (target != null) PlayerInfoMenu.open(player, target);
                        else player.sendMessage(GuiConstants.ERROR + "Player offline.");
                    }
                }
            }
            case PLUGIN_INFO -> handleAbout(player, matName, name);
            default -> {}
        }
    }

    private void handleDashboard(Player player, String matName, String name) {
        if (matName.contains("COMPARATOR")) CategoryMenu.open(player);
        else if (matName.contains("ENDER_EYE") || name.contains("Inspector")) PlayerSelectMenu.open(player, "Info");
        else if (matName.contains("WRITABLE_BOOK") || name.contains("Logs")) PlayerSelectMenu.open(player, "Logs");
        else if (matName.contains("BOOK") && (name.contains("About") || name.contains("Credits"))) PluginInfoMenu.open(player);
    }

    private void handleAbout(Player player, String matName, String name) {
        if ((matName.contains("KNOWLEDGE") || matName.contains("BOOK")) && name.contains("Discord")) {
            PluginInfoMenu.sendDiscordLink(player);
        }
    }

    private void handleCategories(Player player, String matName, String name) {
        if ((matName.contains("EMERALD") || matName.contains("REDSTONE_BLOCK")) && name.contains("Toggle All")) {
            playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
            CategoryMenu.toggleAllChecks(player);
            CategoryMenu.open(player);
            return;
        }

        playSound(player, Sound.UI_BUTTON_CLICK);
        if (matName.contains("FEATHER")) CheckTypeMenu.open(player, "Movement");
        else if (matName.contains("SWORD")) CheckTypeMenu.open(player, "Combat");
        else if (matName.contains("GRASS")) CheckTypeMenu.open(player, "World");
        else if (matName.contains("REPEATER") || matName.contains("DIODE")) CheckTypeMenu.open(player, "Packet");
        else if (matName.contains("COMPASS")) CheckTypeMenu.open(player, "Bot");
        else if (matName.contains("BEDROCK")) CheckTypeMenu.open(player, "Bedrock");
    }

    private void handleCheckConfig(Player player, GuiHolder holder, boolean isRightClick, String name) {
        if (name.contains("Toggle All")) {
            CheckType type = holder.getCheckType();
            if (type != null) {
                playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
                CheckConfigMenu.toggleAllForType(player, type);
                CheckConfigMenu.open(player, type, holder.getPage());
            }
            return;
        }

        Check check = null;
        for (Check c : Truthful.getInstance().getCheckManager().getCollection()) {
            if (ChatColor.stripColor(c.getFormattedName()).equalsIgnoreCase(name)) {
                check = c;
                break;
            }
        }

        if (check != null) {
            if (isRightClick) {
                // Right-Click -> Open Check Details
                playSound(player, Sound.UI_BUTTON_CLICK);
                new CheckDetailsMenu(check).open(player);
            } else {
                // Left-Click -> Toggle Enabled/Disabled instantly
                playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
                boolean newState = !check.isEnabled();
                check.setEnabled(newState);
                Truthful.getInstance().getConfiguration()
                        .setCheckEnabled(check.getType().name(), String.valueOf(check.getOrder()), newState);
                CheckConfigMenu.open(player, holder.getCheckType(), holder.getPage());
            }
        }
    }

    private void handleCheckDetails(Player player, GuiHolder holder, String name) {
        Check check = CheckDetailsMenu.getCheckFromTitle(holder.getTargetName());
        if (check == null) return;

        Configuration config = Truthful.getInstance().getConfiguration();
        String typeName = check.getType().name();
        String orderStr = String.valueOf(check.getOrder());

        if (name.contains("Check Status")) {
            playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
            boolean newState = !check.isEnabled();
            check.setEnabled(newState);
            config.setCheckEnabled(typeName, orderStr, newState);
            new CheckDetailsMenu(check).open(player);
        } else if (name.contains("Lagback Status")) {
            playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
            // Three-state cycler: inherit -> true -> false -> inherit.
            // inherit pulls the global lagback profile, true/false force.
            String currentRaw = config.getCheckLagbackRaw(typeName, orderStr);
            String next;
            if (currentRaw == null || currentRaw.equalsIgnoreCase("inherit")) {
                next = "true";
            } else if (currentRaw.equalsIgnoreCase("true")) {
                next = "false";
            } else {
                next = "inherit";
            }
            config.setCheckLagbackRaw(typeName, orderStr, next);
            new CheckDetailsMenu(check).open(player);
        } else if (name.contains("Punishment Status")) {
            playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
            boolean newState = !config.isPunishmentEnabled(typeName, orderStr);
            config.setPunishmentEnabled(typeName, orderStr, newState);
            new CheckDetailsMenu(check).open(player);
        } else if (name.contains("Packet Rejection")) {
            // Observe-only switch for checks that reject packets directly.
            // Independent of punishment/lagback: off = flags/alerts fire but
            // the packet passes through.
            playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
            boolean newState = !config.isCheckCancelEnabled(typeName, orderStr);
            config.setCheckCancelEnabled(typeName, orderStr, newState);
            new CheckDetailsMenu(check).open(player);
        }
    }

    private void handlePagination(Player player, GuiHolder holder, String itemName) {
        int nextPage = itemName.contains("Next") ? holder.getPage() + 1 : holder.getPage() - 1;
        if (holder.getMenuType() == GuiHolder.MenuType.CHECK_CONFIG) {
            CheckConfigMenu.open(player, holder.getCheckType(), nextPage);
        } else if (holder.getMenuType() == GuiHolder.MenuType.PLAYER_SELECT) {
            PlayerSelectMenu.open(player, holder.getCategory(), nextPage);
        }
    }

    private void handleBack(Player player, GuiHolder holder) {
        switch (holder.getMenuType()) {
            case CATEGORIES, PLAYER_SELECT, PLUGIN_INFO -> MainMenu.open(player);
            case CHECK_TYPES -> CategoryMenu.open(player);
            case CHECK_CONFIG -> {
                if (holder.getCheckType() != null) {
                    String cat = CategoryMenu.getCategoryForType(holder.getCheckType());
                    if (cat != null) CheckTypeMenu.open(player, cat);
                    else CategoryMenu.open(player);
                } else CategoryMenu.open(player);
            }
            case CHECK_DETAILS -> {
                Check check = CheckDetailsMenu.getCheckFromTitle(holder.getTargetName());
                if (check != null) CheckConfigMenu.open(player, check.getType());
                else CategoryMenu.open(player);
            }
            default -> MainMenu.open(player);
        }
    }

    private void playSound(Player player, Sound sound) {
        try { player.playSound(player.getLocation(), sound, 0.5f, 1.0f); } catch (Throwable ignored) {}
    }

    private CheckType getCheckTypeByName(String name) {
        for (CheckType type : CheckType.values()) {
            if (type.getName().equalsIgnoreCase(name)) return type;
        }
        return null;
    }
}