package coolcostupit.openjs.modules;

import coolcostupit.openjs.logging.pluginLogger;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

public class pApiExtension extends PlaceholderExpansion {
    private final JavaPlugin plugin;
    private final pluginLogger logger;

    public pApiExtension(JavaPlugin plugin, pluginLogger Logger) {
        this.plugin = plugin;
        this.logger = Logger;
    }

    @Override
    public @NotNull String getIdentifier() {
        return plugin.getName().toLowerCase();
    }

    @Override
    public @NotNull String getAuthor() {
        return plugin.getDescription().getAuthors().get(1);
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        logger.log(Level.INFO, params, pluginLogger.GREEN);
        return null;
    }
}