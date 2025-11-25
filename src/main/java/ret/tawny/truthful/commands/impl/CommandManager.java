package ret.tawny.truthful.commands.impl;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.database.LogManager;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class CommandManager implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("truthful.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "menu":
            case "gui":
                if (sender instanceof Player) {
                    Truthful.getInstance().getGuiManager().openMainMenu((Player) sender);
                } else {
                    sender.sendMessage("§cPlayers only.");
                }
                break;

            case "exempt":
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /truthful exempt <player>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage("§cPlayer not found.");
                    return true;
                }
                PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(target);
                if (data != null) {
                    boolean newState = !data.isExempt();
                    data.setExempt(newState);
                    sender.sendMessage(newState ? "§aPlayer is now exempt." : "§cPlayer is no longer exempt.");
                }
                break;

            // --- NEW: ATTRIBUTE/INFO COMMAND ---
            case "info":
            case "attributes":
            case "attr":
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /truthful info <player>");
                    return true;
                }
                handleInfo(sender, args[1]);
                break;

            case "logs":
            case "history":
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /truthful logs <player>");
                    return true;
                }
                handleLogs(sender, args[1]);
                break;

            case "export":
                sender.sendMessage("§7Exporting database to CSV...");
                Bukkit.getScheduler().runTaskAsynchronously(Truthful.getInstance().getPlugin(), () -> {
                    File file = Truthful.getInstance().getLogManager().exportToCsv();
                    if (file != null) {
                        sender.sendMessage("§aExport successful!");
                        sender.sendMessage("§7File: §f" + file.getAbsolutePath());
                    } else {
                        sender.sendMessage("§cExport failed. Check console.");
                    }
                });
                break;

            default:
                sendHelp(sender);
                break;
        }
        return true;
    }

    private void handleInfo(CommandSender sender, String targetName) {
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            sender.sendMessage("§cPlayer not found.");
            return;
        }
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(target);
        if (data == null) {
            sender.sendMessage("§cNo data found for player.");
            return;
        }

        boolean isBedrock = Truthful.getInstance().isBedrockPlayer(target);

        sender.sendMessage("§8§m--------------------------------");
        sender.sendMessage("§c§lPlayer Attributes: §f" + target.getName());
        sender.sendMessage("");
        sender.sendMessage(" §8» §7Brand: §c" + (data.getClientBrand() == null ? "Unknown" : data.getClientBrand()));
        sender.sendMessage(" §8» §7Platform: §f" + (isBedrock ? "Bedrock" : "Java"));
        sender.sendMessage(" §8» §7Ping: §f" + data.getPing() + "ms");
        sender.sendMessage(" §8» §7Violations: §f" + data.getVl());
        sender.sendMessage("");
        sender.sendMessage(" §8» §7Physics State:");
        sender.sendMessage("    §7Ground: " + (data.isOnGround() ? "§aTrue" : "§cFalse"));
        sender.sendMessage("    §7Liquid: " + (data.isInLiquid() ? "§aTrue" : "§cFalse"));
        sender.sendMessage("    §7Climbable: " + (data.isOnClimbable() ? "§aTrue" : "§cFalse"));
        sender.sendMessage("    §7Near Vehicle: " + (data.isNearVehicle() ? "§aTrue" : "§cFalse"));
        sender.sendMessage("    §7Webs: " + (data.isInWeb() ? "§aTrue" : "§cFalse"));
        sender.sendMessage("§8§m--------------------------------");
    }

    private void handleLogs(CommandSender sender, String targetName) {
        sender.sendMessage("§7Fetching logs for §c" + targetName + "§7...");
        Bukkit.getScheduler().runTaskAsynchronously(Truthful.getInstance().getPlugin(), () -> {
            List<LogManager.LogEntry> logs = Truthful.getInstance().getLogManager().getLogs(targetName, 10);
            if (logs.isEmpty()) {
                sender.sendMessage("§cNo logs found.");
                return;
            }
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm");
            sender.sendMessage("§8§m---------------------------");
            for (LogManager.LogEntry log : logs) {
                sender.sendMessage(String.format("§8[§7%s§8] §c%s §8(§fVL:%d§8) §7%s",
                        sdf.format(new Date(log.timestamp)),
                        log.check,
                        log.vl,
                        log.data
                ));
            }
            sender.sendMessage("§8§m---------------------------");
        });
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§c§lTruthful Anti-Cheat");
        sender.sendMessage("§7/truthful info <player> §8- §fView Client Brand & Attributes");
        sender.sendMessage("§7/truthful menu §8- §fOpen GUI");
        sender.sendMessage("§7/truthful exempt <player> §8- §fToggle check exemption");
        sender.sendMessage("§7/truthful logs <player> §8- §fView recent violations");
        sender.sendMessage("§7/truthful export §8- §fSave all logs to CSV file");
    }
}