package ret.tawny.truthful.commands.impl;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.config.api.Configuration;
import ret.tawny.truthful.managers.BanwaveManager;

import java.util.List;

public final class CommandBanwave implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        final Configuration config = Truthful.getInstance().getConfiguration();

        if (!sender.hasPermission("truthful.admin")) {
            sender.sendMessage(config.getNoPermissionMessage());
            return true;
        }

        if (!config.isBanwaveEnabled()) {
            sender.sendMessage(config.getBanwaveNotEnabledMessage());
            return true;
        }

        final BanwaveManager manager = Truthful.getInstance().getBanwaveManager();

        if (args.length == 0) {
            sender.sendMessage(config.getBanwaveUsageMessage());
            sender.sendMessage(config.getBanwaveScheduleMessage()
                    .replace("%enabled%", String.valueOf(config.isBanwaveAutoStartEnabled()))
                    .replace("%day%", config.getBanwaveAutoStartDay().name())
                    .replace("%time%", config.getBanwaveAutoStartTime().toString()));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "add":
                if (args.length < 2) {
                    sender.sendMessage(config.getBanwaveUsageMessage());
                    return true;
                }
                if (manager.addPlayer(args[1])) {
                    sender.sendMessage(config.getBanwaveQueuedMessage().replace("%player%", args[1]));
                } else {
                    sender.sendMessage(config.getBanwaveDuplicateMessage().replace("%player%", args[1]));
                }
                return true;

            case "remove":
                if (args.length < 2) {
                    sender.sendMessage(config.getBanwaveUsageMessage());
                    return true;
                }
                if (manager.removePlayer(args[1])) {
                    sender.sendMessage(config.getBanwaveRemovedMessage().replace("%player%", args[1]));
                } else {
                    sender.sendMessage(config.getBanwaveDuplicateMessage().replace("%player%", args[1]));
                }
                return true;

            case "list":
                List<String> queued = manager.getQueuedPlayers();
                sender.sendMessage(config.getBanwaveListHeader().replace("%queued%", String.valueOf(queued.size())));
                for (int i = 0; i < queued.size(); i++) {
                    sender.sendMessage(config.getBanwaveListEntry()
                            .replace("%position%", String.valueOf(i + 1))
                            .replace("%player%", queued.get(i)));
                }
                return true;

            case "start":
                if (manager.getQueuedCount() == 0) {
                    sender.sendMessage(config.getBanwaveEmptyMessage());
                    return true;
                }
                if (manager.start()) sender.sendMessage(config.getBanwaveStartedMessage());
                else sender.sendMessage(config.getBanwaveStatusMessage()
                        .replace("%running%", "true")
                        .replace("%queued%", String.valueOf(manager.getQueuedCount())));
                return true;

            case "stop":
                if (manager.stop()) sender.sendMessage(config.getBanwaveStoppedMessage());
                else sender.sendMessage(config.getBanwaveStatusMessage()
                        .replace("%running%", "false")
                        .replace("%queued%", String.valueOf(manager.getQueuedCount())));
                return true;

            case "clear":
                manager.clearQueue();
                sender.sendMessage(config.getBanwaveClearedMessage());
                return true;

            case "status":
                sender.sendMessage(config.getBanwaveStatusMessage()
                        .replace("%running%", String.valueOf(manager.isRunning()))
                        .replace("%queued%", String.valueOf(manager.getQueuedCount())));
                sender.sendMessage(config.getBanwaveScheduleMessage()
                        .replace("%enabled%", String.valueOf(config.isBanwaveAutoStartEnabled()))
                        .replace("%day%", config.getBanwaveAutoStartDay().name())
                        .replace("%time%", config.getBanwaveAutoStartTime().toString()));
                return true;

            default:
                sender.sendMessage(config.getBanwaveUsageMessage());
                return true;
        }
    }
}
