package ret.tawny.truthful.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.database.LogManager;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class GuiManager implements Listener {

    public GuiManager() {
        Bukkit.getPluginManager().registerEvents(this, Truthful.getInstance().getPlugin());
    }

    public void openMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "§cTruthful §8> §7Menu");

        fillGlass(inv);

        ItemStack info = createItem(Material.BOOK, "§c§lPlugin Info", "§7Version: 2.1", "§7Author: Tawny");
        ItemStack logs = createItem(Material.PAPER, "§6§lView Logs", "§7Click to view recent logs", "§7from current players.");
        ItemStack reload = createItem(Material.REDSTONE_BLOCK, "§c§lReload Config", "§7Click to reload configuration.");

        inv.setItem(11, info);
        inv.setItem(13, logs);
        inv.setItem(15, reload);

        player.openInventory(inv);
    }

    public void openPlayerSelector(Player admin) {
        Inventory inv = Bukkit.createInventory(null, 54, "§cTruthful §8> §7Select Player");

        for (Player target : Bukkit.getOnlinePlayers()) {
            ItemStack skull = createItem(Material.PLAYER_HEAD, "§c" + target.getName(), "§7Click to view history");
            inv.addItem(skull);
        }

        admin.openInventory(inv);
    }

    public void openLogs(Player admin, String targetName) {
        Inventory inv = Bukkit.createInventory(null, 54, "§cLogs: " + targetName);

        // Run Async to prevent lag
        Bukkit.getScheduler().runTaskAsynchronously(Truthful.getInstance().getPlugin(), () -> {
            List<LogManager.LogEntry> logs = Truthful.getInstance().getLogManager().getLogs(targetName, 45);
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm");

            for (LogManager.LogEntry log : logs) {
                String date = sdf.format(new Date(log.timestamp));
                ItemStack item = createItem(Material.PAPER,
                        "§c" + log.check,
                        "§7VL: §f" + log.vl,
                        "§7Time: §f" + date,
                        "§7Data: §f" + log.data
                );
                inv.addItem(item);
            }

            // Re-open sync
            Bukkit.getScheduler().runTask(Truthful.getInstance().getPlugin(), () -> admin.openInventory(inv));
        });
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!e.getView().getTitle().startsWith("§cTruthful")) return;
        e.setCancelled(true);
        if (e.getCurrentItem() == null) return;

        Player p = (Player) e.getWhoClicked();
        String title = e.getView().getTitle();
        ItemStack item = e.getCurrentItem();

        if (title.endsWith("Menu")) {
            if (item.getType() == Material.PAPER) {
                openPlayerSelector(p);
            } else if (item.getType() == Material.REDSTONE_BLOCK) {
                Truthful.getInstance().getPlugin().reloadConfig();
                p.sendMessage("§aConfiguration reloaded.");
                p.closeInventory();
            }
        } else if (title.endsWith("Select Player")) {
            if (item.getType() == Material.PLAYER_HEAD) {
                String target = ChatColor.stripColor(item.getItemMeta().getDisplayName());
                openLogs(p, target);
            }
        }
    }

    private void fillGlass(Inventory inv) {
        ItemStack glass = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, glass);
        }
    }

    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }
}