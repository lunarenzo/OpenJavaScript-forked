package coolcostupit.openjs.modules;

import coolcostupit.openjs.logging.pluginLogger;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class PlaceHolderApiJS {
    public static Map<String, List<Listener>> registeredPlaceholders = new HashMap<>();

    public PlaceHolderApiJS() {

    }

    public String parseString(Player player, @NotNull String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }

    public void registerPlaceholder(String placeholderPrefix, Object handler, String scriptName, ScriptEngine scriptEngine) {
        PlaceholderExpansion expansion = new PlaceholderExpansion() {
            @Override
            public @NotNull String getIdentifier() {
                return placeholderPrefix;
            }

            @Override
            public @NotNull String getAuthor() {
                return sharedClass.PluginDescription.getAuthors().get(0);
            }

            @Override
            public @NotNull String getVersion() {
                return sharedClass.PluginDescription.getVersion();
            }

            @Override
            public String onPlaceholderRequest(Player player, @NotNull String params) {
                try {
                    return (String) ((Invocable) scriptEngine).invokeMethod(handler, "onRequest", player, params);
                } catch (Exception e) {
                    sharedClass.logger.log(Level.SEVERE, "["+scriptName+"] PlaceholderAPI Error: " + e.getMessage(), pluginLogger.RED);
                    return null;
                }
            }
        };

        expansion.register();
    }
}
