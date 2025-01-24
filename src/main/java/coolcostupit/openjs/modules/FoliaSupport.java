package coolcostupit.openjs.modules;

import coolcostupit.openjs.foliascheduler.ServerImplementation;
import coolcostupit.openjs.foliascheduler.folia.FoliaTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import coolcostupit.openjs.foliascheduler.FoliaCompatibility;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class FoliaSupport {
    private static Boolean cached = null;
    private static Boolean isFoliaChecked = null;

    // A map to store active tasks with their IDs
    private static final Map<Integer, Object> activeTasks = new HashMap<>();
    private static int nextTaskId = 1; // Custom task ID generator

    // Check if the server is running on Folia and cache the result
    public static boolean isFolia() {
        if (cached == null) {
            if (isFoliaChecked == null) {
                boolean foliaDetected = false;
                try {
                    // Check if Folia-specific methods exist
                    Method getRegionScheduler = Bukkit.getServer().getClass().getMethod("getRegionScheduler");
                    foliaDetected = getRegionScheduler != null;
                } catch (NoSuchMethodException | SecurityException ignored) {
                    // Folia-specific methods not found, assume non-Folia server
                }
                isFoliaChecked = (Boolean) foliaDetected;
            }
            cached = (Boolean) true;
        }
        return isFoliaChecked;
    }

    // Schedule a delayed task and return its custom task ID
    public static int ScheduleTask(JavaPlugin plugin, Runnable function, long delay) {
        Object task;
        if (isFolia()) {
            ServerImplementation scheduler = new FoliaCompatibility(plugin).getServerImplementation();
            task = scheduler.async().runDelayed(function, delay);
        } else {
            task = Bukkit.getScheduler().runTaskLater(plugin, function, delay);
        }
        return addTask(task);
    }

    // Run a task immediately and return its custom task ID
    public static int runTask(JavaPlugin plugin, Runnable function) {
        Object task;
        if (isFolia()) {
            ServerImplementation scheduler = new FoliaCompatibility(plugin).getServerImplementation();
            task = scheduler.async().runNow(function);
        } else {
            task = Bukkit.getScheduler().runTask(plugin, function);
        }
        return addTask(task);
    }

    // Run a synchronous task and return its custom task ID
    public static int runTaskSynchronously(JavaPlugin plugin, Runnable function) {
        Object task;
        if (isFolia()) {
            ServerImplementation scheduler = new FoliaCompatibility(plugin).getServerImplementation();
            task = scheduler.global().run(function);
        } else {
            task = Bukkit.getScheduler().runTask(plugin, function);
        }
        return addTask(task);
    }

    // Schedule a repeating task and return its custom task ID
    public static int ScheduleRepeatingTask(JavaPlugin plugin, Runnable function, long delay, long period) {
        Object task;
        if (isFolia()) {
            ServerImplementation scheduler = new FoliaCompatibility(plugin).getServerImplementation();
            task = scheduler.async().runAtFixedRate(function, delay, period);
        } else {
            task = Bukkit.getScheduler().runTaskTimer(plugin, function, delay, period);
        }
        return addTask(task);
    }

    // Cancel a task by its custom task ID
    public static boolean CancelTask(int taskId) {
        Object task = activeTasks.remove((Integer) taskId);
        if (task == null) {
            return false; // Task not found
        }

        try {
            if (isFolia()) {
                ((FoliaTask<?>) task).cancel();
            } else {
                ((BukkitTask) task).cancel();
            }
            return true;
        } catch (Exception e) {
            return false; // Task cancellation failed
        }
    }

    // Add a task to the map and return its custom task ID
    private static int addTask(Object task) {
        Integer taskId = (Integer) nextTaskId++;
        activeTasks.put(taskId, task);
        return taskId;
    }
}
