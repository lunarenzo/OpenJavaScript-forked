/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.modules;

import coolcostupit.openjs.logging.pluginLogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class scriptTaskerApi {
    private final scriptWrapper ScriptWrapper;
    private final @NotNull PluginManager pluginManager;
    private final pluginLogger Logger;
    private static final Map<Object, ListenerEntry> listenerCleanupMap = new ConcurrentHashMap<>();
    public static final Map<Integer, String> globalTaskOwnerMap = new java.util.concurrent.ConcurrentHashMap<>();
    public static final Map<String, java.util.Set<Integer>> scriptTasksMap = new java.util.concurrent.ConcurrentHashMap<>();

    private static class ListenerEntry {
        public final String scriptName;
        public final Object cleanup;
        public final ScriptEngine scriptEngine;

        public ListenerEntry(String scriptName, ScriptEngine scriptEngine, Object cleanup) {
            this.scriptName = scriptName;
            this.cleanup = cleanup;
            this.scriptEngine = scriptEngine;
        }
    }

    public scriptTaskerApi(scriptWrapper scriptWrapper) {
        this.ScriptWrapper = scriptWrapper;
        this.pluginManager = Bukkit.getPluginManager();
        this.Logger = sharedClass.logger;
    }

    private abstract class AutoCleanTask implements Runnable {
        private final String scriptName;
        private final ScriptEngine engine;
        private final Object handler;
        private volatile int taskId = -1;
        private volatile boolean finished = false;

        AutoCleanTask(String name, ScriptEngine eng, Object h) {
            this.scriptName = name; this.engine = eng; this.handler = h;
        }

        public void setTaskId(int id) {
            this.taskId = id;
            if (finished) untrackTask(id);
        }

        @Override
        public void run() {
            try {
                ((Invocable) engine).invokeMethod(handler, "f");
            } catch (Exception e) {
                Logger.scriptlog(Level.WARNING, scriptName, e.getMessage(), pluginLogger.RED);
            } finally {
                finished = true;
                if (taskId != -1) untrackTask(taskId);
            }
        }
    }

    private void trackTask(String scriptName, int taskId) {
        if (taskId <= 0) return;
        globalTaskOwnerMap.put(taskId, scriptName);
        scriptTasksMap.computeIfAbsent(scriptName, k -> ConcurrentHashMap.newKeySet()).add(taskId);
    }

    private void untrackTask(int taskId) {
        String owner = globalTaskOwnerMap.remove(taskId);
        if (owner != null) {
            Set<Integer> scriptTasks = scriptTasksMap.get(owner);
            if (scriptTasks != null) {
                scriptTasks.remove(taskId);
                if (scriptTasks.isEmpty()) scriptTasksMap.remove(owner);
            }
        }
    }

    public Boolean wait(String scriptName, ScriptEngine scriptEngine, Number seconds) {
        double sec = seconds.doubleValue();

        if (sec <= 0) return Boolean.TRUE;

        if (Bukkit.isPrimaryThread()) {
            Logger.log(
                    Level.WARNING,
                    "[" + scriptName + "] Calling task.wait(" + sec + "s) on the main server thread can cause lag or freeze the server!",
                    pluginLogger.ORANGE
            );
        }

        long millis = (long) (sec * 1000);
        if (millis < 0) millis = 0;

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Logger.log(Level.INFO, "[" + scriptName + "] interrupting task.wait(" + seconds + ")", pluginLogger.LIGHT_BLUE);
            return Boolean.FALSE;
        }

        return Boolean.TRUE;
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

    public int spawn(String scriptName, ScriptEngine engine, Object handler) {
        AutoCleanTask task = new AutoCleanTask(scriptName, engine, handler) {};
        int id = FoliaSupport.runTask(sharedClass.plugin, task);
        trackTask(scriptName, id);
        task.setTaskId(id);
        return id;
    }

    public int delay(String scriptName, ScriptEngine engine, Number delay, Object handler) {
        AutoCleanTask task = new AutoCleanTask(scriptName, engine, handler) {};
        int id = FoliaSupport.DelayTask(sharedClass.plugin, task, (long)(delay.doubleValue() * 20));
        trackTask(scriptName, id);
        task.setTaskId(id);
        return id;
    }

    public int repeat(String scriptName, ScriptEngine engine, Number delay, Number period, Object handler) {
        Runnable task = () -> {
            try { ((Invocable) engine).invokeMethod(handler, "f"); }
            catch (Exception e) { Logger.scriptlog(Level.WARNING, scriptName, e.getMessage(), pluginLogger.RED); }
        };
        int id = FoliaSupport.ScheduleRepeatingTask(sharedClass.plugin, task,
                (long)(delay.doubleValue() * 20), (long)(period.doubleValue() * 20));
        trackTask(scriptName, id);
        return id;
    }

    public int thread(String scriptName, ScriptEngine engine, Object handler) {
        AutoCleanTask task = new AutoCleanTask(scriptName, engine, handler) {};
        int id = FoliaSupport.runThreadPoolTask(task);
        trackTask(scriptName, id);
        task.setTaskId(id);
        return id;
    }

    public int main(String scriptName, ScriptEngine engine, Object handler) {
        AutoCleanTask task = new AutoCleanTask(scriptName, engine, handler) {};
        int id = FoliaSupport.runTaskSynchronously(sharedClass.plugin, task);
        trackTask(scriptName, id);
        task.setTaskId(id);
        return id;
    }

    public int entitySchedule(String scriptName, ScriptEngine engine, Entity entity, Object handler) {
        AutoCleanTask task = new AutoCleanTask(scriptName, engine, handler) {};
        int id = FoliaSupport.runEntityTask(sharedClass.plugin, entity, task);
        trackTask(scriptName, id);
        task.setTaskId(id);
        return id;
    }

    public void cleanupListener(String scriptName, ScriptEngine scriptEngine, Object handler) {
        try {
            Logger.log(Level.INFO, "[" + scriptName + "] Listener cleanup executed.", pluginLogger.LIGHT_BLUE);
            ((Invocable) scriptEngine).invokeMethod(handler, "f");
        } catch (ScriptException | NoSuchMethodException e) {
            Logger.scriptlog(Level.WARNING, scriptName, "Listener cleanup failed: " + e.getMessage(), pluginLogger.RED);
        }
    }

    public void cancel(String callingScript, Object thing) {
        if (thing instanceof Number) {
            int taskId = ((Number) thing).intValue();
            FoliaSupport.CancelTask(taskId);
            untrackTask(taskId);
            return;
        }

        ListenerEntry entry = listenerCleanupMap.remove(thing);
        if (entry != null) cleanupListener(entry.scriptName, entry.scriptEngine, entry.cleanup);
    }

    static public void cancelTasksFromScript(String scriptName) {
        Set<Integer> taskIds = scriptTasksMap.remove(scriptName);
        if (taskIds != null) {
            for (int taskId : taskIds) {
                FoliaSupport.CancelTask(taskId);
                globalTaskOwnerMap.remove(taskId);
            }
        }
    }

    public void clearListeners(String scriptName) {
        List<Object> toRemove = new ArrayList<>();

        for (Map.Entry<Object, ListenerEntry> entryValue : listenerCleanupMap.entrySet()) {
            ListenerEntry entry = entryValue.getValue();
            if (entry.scriptName.equals(scriptName)) {
                cleanupListener(entry.scriptName, entry.scriptEngine, entry.cleanup);
                toRemove.add(entryValue.getKey());
            }
        }

        toRemove.forEach(listenerCleanupMap::remove);
    }

    public <T> Object createListener(String scriptName, ScriptEngine engine, Class<T> interfaceClass, Object jsHandler) {
        if (!(engine instanceof Invocable)) {
            Logger.log(Level.WARNING, "Script engine is not invocable. Cannot bind handler.", pluginLogger.RED);
            return null;
        }

        Invocable inv = (Invocable) engine;

        Object proxy = Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class<?>[]{interfaceClass},
                (p, method, args) -> {
                    try {
                        return inv.invokeMethod(jsHandler, method.getName(), args);
                    } catch (NoSuchMethodException e) {
                        if (method.getReturnType().isPrimitive()) {
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == char.class) return '\0';
                            return 0;
                        }
                        return null;
                    } catch (Exception e) {
                        Logger.log(Level.SEVERE, "Listener handler error: " + e.getMessage(), pluginLogger.RED);
                        return null;
                    }
                }
        );

        return proxy;
    }

    public void setListenerCleanup(String scriptName, ScriptEngine scriptEngine, Object proxy, Object cleanup) {
        if (cleanup != null) {
            listenerCleanupMap.put(proxy, new ListenerEntry(scriptName, scriptEngine, cleanup));
        } else {
            Logger.log(Level.WARNING, "[" + scriptName + "] Listener created without cleanup function. This may cause memory leaks.", pluginLogger.ORANGE);
        }
    }
}
