// FILE PATH: .\src\main\java\ret\tawny\truthful\TruthfulPlugin.java

package ret.tawny.truthful;

import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;
import ret.tawny.truthful.config.api.Configuration;

public final class TruthfulPlugin extends JavaPlugin {
    private Configuration configuration;

    @Override
    public void onEnable() {
        this.configuration = new Configuration(this);

        // Initialize bStats
        // REPLACE 12345 WITH YOUR REAL PLUGIN ID FROM bStats.org
        int pluginId = 28120;
        new Metrics(this, pluginId);

        Truthful.getInstance().start(this);
    }

    @Override
    public void onDisable() {
        Truthful.getInstance().shutdown();
    }

    public Configuration getConfiguration() {
        return configuration;
    }
}