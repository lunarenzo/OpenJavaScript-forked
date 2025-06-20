package coolcostupit.openjs.modules;

import coolcostupit.openjs.logging.pluginLogger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.util.ArrayList;
import java.util.List;
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
        double sec = seconds.doubleValue();

        if (sec <= 0) return; // don't wait if zero or negative

        if (Bukkit.isPrimaryThread()) {
            Logger.log(
                    Level.WARNING,
                    "[" + scriptName + "] Calling task.wait(" + sec + "s) on the main server thread can cause lag or freeze the server!",
                    pluginLogger.ORANGE
            );
        }

        try {
            long millis = (long) (sec * 1000);

            if (millis < 0) millis = 0; // overflow protection

            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IllegalArgumentException e) {
            Logger.log(Level.WARNING, "[" + scriptName + "] Invalid wait time: " + seconds, pluginLogger.RED);
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

    public int spawn(String scriptName, ScriptEngine scriptEngine, Object handler) {
        Runnable task = () -> {
            try {
                ((Invocable) scriptEngine).invokeMethod(handler, "f");
            } catch (ScriptException | NoSuchMethodException e) {
                Logger.log(Level.SEVERE, "[" + scriptName + "] " + e.getMessage(), pluginLogger.RED);
            }
        };
        int taskId = FoliaSupport.runTask(sharedClass.plugin, task);
        ScriptWrapper.scriptTasksMap.computeIfAbsent(scriptName, k -> new ArrayList<>()).add(Integer.valueOf(taskId));

        return taskId;
    }

    public int delay(String scriptName, ScriptEngine scriptEngine, Number Delay, Object handler) {
        double sec = Delay.doubleValue();

        if (sec <= 0) return 0;
        long ticks = (long) (sec * 20); // Convert seconds to ticks

        Runnable task = () -> {
            try {
                ((Invocable) scriptEngine).invokeMethod(handler, "f");
            } catch (ScriptException | NoSuchMethodException e) {
                Logger.log(Level.SEVERE, "[" + scriptName + "] " + e.getMessage(), pluginLogger.RED);
            }
        };

        int taskId = FoliaSupport.ScheduleTask(sharedClass.plugin, task, ticks);
        ScriptWrapper.scriptTasksMap.computeIfAbsent(scriptName, k -> new ArrayList<>()).add(Integer.valueOf(taskId));

        return taskId;
    }

    public int repeat(String scriptName, ScriptEngine scriptEngine, Number Delay, Number Period, Object handler) {
        double delaySec = Delay.doubleValue();
        double periodSec = Period.doubleValue();

        if (delaySec < 0 || periodSec <= 0) {
            Logger.log(Level.WARNING, "[" + scriptName + "] Invalid repeat delay/period values: delay=" + delaySec + ", period=" + periodSec, pluginLogger.RED);
            return 0;
        }

        long delayTicks = (long) (delaySec * 20);   // Delay before first run
        long periodTicks = (long) (periodSec * 20); // Interval between runs

        Runnable task = () -> {
            try {
                ((Invocable) scriptEngine).invokeMethod(handler, "f");
            } catch (ScriptException | NoSuchMethodException e) {
                Logger.log(Level.SEVERE, "[" + scriptName + "] " + e.getMessage(), pluginLogger.RED);
            }
        };

        int taskId = FoliaSupport.ScheduleRepeatingTask(sharedClass.plugin, task, delayTicks, periodTicks);
        ScriptWrapper.scriptTasksMap.computeIfAbsent(scriptName, k -> new ArrayList<>()).add(taskId);

        return taskId;
    }

    public void cancel(String scriptName, int taskId) {
        List<Integer> taskIds = ScriptWrapper.scriptTasksMap.get(scriptName);

        if (taskIds != null && taskIds.remove(Integer.valueOf(taskId))) {
            FoliaSupport.CancelTask(taskId);
            Logger.log(Level.INFO, "[" + scriptName + "] Unregistered task ID " + taskId, pluginLogger.LIGHT_BLUE);

            if (taskIds.isEmpty()) {
                ScriptWrapper.scriptTasksMap.remove(scriptName);
            }
        } else {
            Logger.log(Level.WARNING, "[" + scriptName + "] Tried to unregister unknown task ID " + taskId, pluginLogger.ORANGE);
        }
    }
}
