package coolcostupit.openjs.modules;

import coolcostupit.openjs.logging.pluginLogger;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class PlaceHolderApiJS {
    private static final Map<String, PlaceholderData> registeredPlaceholders = new HashMap<>();

    public static class PlaceholderData {
        public final Object handler;
        public final ScriptEngine engine;
        public final String scriptName;

        public PlaceholderData(Object handler, ScriptEngine engine, String scriptName) {
            this.handler = handler;
            this.engine = engine;
            this.scriptName = scriptName;
        }
    }

    public PlaceHolderApiJS() {
    }

    public String parseString(Player player, @NotNull String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }

    public void registerPlaceholder(String prefix, Object handler, String scriptName, ScriptEngine engine) {
        if (registeredPlaceholders.containsKey(prefix)) {
            sharedClass.logger.log(Level.WARNING, "[" + scriptName + "] Placeholder %" + sharedClass.Identifier + "_" + prefix + "% already exists and will be overwritten.", pluginLogger.ORANGE);
        }
        registeredPlaceholders.put(prefix, new PlaceholderData(handler, engine, scriptName));
        sharedClass.logger.log(Level.INFO, "[" + scriptName + "] Placeholder %" + sharedClass.Identifier + "_" + prefix + "% has been registered.", pluginLogger.GREEN);
    }

    public void unregisterPlaceholder(String scriptName) {
        registeredPlaceholders.entrySet().removeIf(entry -> {
            boolean match = entry.getValue().scriptName.equals(scriptName);
            if (match) {
                sharedClass.logger.log(Level.INFO, "[" + scriptName + "] Placeholder [" + entry.getKey() + "] has been unregistered.", pluginLogger.RED);
            }
            return match;
        });
    }

    public void unregisterAllPlaceholders() {
        for (Map.Entry<String, PlaceholderData> entry : registeredPlaceholders.entrySet()) {
            String prefix = entry.getKey();
            String scriptName = entry.getValue().scriptName;
            sharedClass.logger.log(Level.INFO, "[" + scriptName + "] Placeholder [" + prefix + "] has been unregistered.", pluginLogger.RED);
        }
        registeredPlaceholders.clear();
    }

    public String invokePrefix(String prefix, Player player) {
        PlaceholderData data = registeredPlaceholders.get(prefix);
        if (data == null) return null;
        try {
            return (String) ((Invocable) data.engine).invokeMethod(data.handler, "onRequest", player);
        } catch (Exception e) {
            sharedClass.logger.log(Level.SEVERE, "[" + data.scriptName + "] Error invoking placeholder [" + prefix + "]: " + e.getMessage(), pluginLogger.RED);
            return null;
        }
    }
}
