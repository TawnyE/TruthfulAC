package ret.tawny.truthful.config.api;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import ret.tawny.truthful.TruthfulPlugin;

public final class Configuration {

    private final FileConfiguration config;

    public Configuration(final TruthfulPlugin plugin) {
        plugin.saveDefaultConfig();
        this.config = plugin.getConfig();
    }

    public boolean isCheckEnabled(String checkType, String checkOrder) {
        return this.config.getBoolean("checks." + checkType + "." + checkOrder + ".enabled", true);
    }

    public boolean isLagbacks() {
        return this.config.getBoolean("options.lagback", true);
    }

    public String getAlertMessage() {
        return color(this.config.getString("messages.alert", "&8[&cTruthful&8] &c%player% &ffailed &c%check% &8(&fVL:%vl%&8) &7%debug%"));
    }

    public String getBrandMessage() {
        return color(this.config.getString("messages.brand", "&8[&cTruthful&8] &7Client Brand: &c%player% &7using &f%brand%"));
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}