package ret.tawny.truthful.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.gui.handlers.GuiClickHandler;
import ret.tawny.truthful.gui.menus.*;
import ret.tawny.truthful.checks.api.data.CheckType;

public final class GuiManager {

    private final GuiClickHandler clickHandler;
    private final java.util.Set<UUID> openPlayerInfoAdmins = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public GuiManager() {
        this.clickHandler = new GuiClickHandler();
        startUpdateTask();
    }

    public void openMainMenu(Player player) { MainMenu.open(player); }
    public void openCategoryMenu(Player player) { CategoryMenu.open(player); }
    public void openCheckTypeMenu(Player player, String category) { CheckTypeMenu.open(player, category); }
    public void openIndividualChecksMenu(Player player, CheckType type) { CheckConfigMenu.open(player, type); }
    public void openPlayerSelectionMenu(Player admin, String type) { PlayerSelectMenu.open(admin, type); }
    public void openLogs(Player admin, String targetName) { LogsMenu.open(admin, targetName); }
    public void openPlayerInfoGui(Player admin, Player target) {
        openPlayerInfoAdmins.add(admin.getUniqueId());
        PlayerInfoMenu.open(admin, target);
    }
    public void openPluginInfoMenu(Player player) { PluginInfoMenu.open(player); }

    private void startUpdateTask() {
        // High-speed 2-tick (100ms) timer strictly updating open PlayerInfo menus.
        // Skips work completely when no admin has a PlayerInfo GUI open.
        Truthful.getInstance().getServerScheduler().runGlobalTimer(() -> {
            if (openPlayerInfoAdmins.isEmpty()) return;

            for (java.util.Iterator<UUID> it = openPlayerInfoAdmins.iterator(); it.hasNext();) {
                UUID adminId = it.next();
                final Player admin = Bukkit.getPlayer(adminId);
                if (admin == null || !admin.isOnline()) {
                    it.remove();
                    continue;
                }

                Truthful.getInstance().getServerScheduler().runRegion(admin, () -> {
                    try {
                        if (!admin.isOnline()) {
                            openPlayerInfoAdmins.remove(adminId);
                            return;
                        }

                        Inventory top = admin.getOpenInventory().getTopInventory();
                        if (top == null || !(top.getHolder() instanceof GuiHolder h) || h.getMenuType() != GuiHolder.MenuType.PLAYER_INFO) {
                            openPlayerInfoAdmins.remove(adminId);
                            return;
                        }

                        if (h.getTargetName() != null) {
                            Player target = Bukkit.getPlayer(h.getTargetName());
                            if (target != null && target.isOnline()) {
                                PlayerInfoMenu.update(top, target);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
        }, 2L, 2L);
    }
}