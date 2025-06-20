package coolcostupit.openjs.modules;

import coolcostupit.openjs.logging.pluginLogger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

public class scriptTaskerApi {
    private final scriptWrapper ScriptWrapper;
    private final @NotNull PluginManager pluginManager;
    private final pluginLogger Logger;

    public scriptTaskerApi(scriptWrapper scriptWrapper) {
        this.ScriptWrapper = scriptWrapper;
        this.pluginManager = Bukkit.getPluginManager();
        this.Logger = sharedClass.logger;
    }

    public void wait(String scriptName, Number seconds) {
        if (Bukkit.isPrimaryThread()) {
            Logger.log(Level.WARNING, "[" + scriptName + "] Calling task.wait() on the main server thread can cause lag or freeze the server!", pluginLogger.ORANGE);
        }
        try {
            long millis = (long) (seconds.doubleValue() * 1000);
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void waitForScript(String scriptName) {
        while (!ScriptWrapper.isJavascriptFileRunning(scriptName)) {
            try {
                //noinspection BusyWait
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void waitForPlugin(String pluginName, String scriptName) {
        Plugin plugin = pluginManager.getPlugin(pluginName);

        if (plugin == null) {
            Logger.log(Level.WARNING, "[" + scriptName + "] Plugin \"" + pluginName + "\" does not exist.", pluginLogger.ORANGE);
            return;
        }

        if (plugin.isEnabled()) {
            return; // Already loaded
        }

        while (!plugin.isEnabled()) {
            try {
                //noinspection BusyWait
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
