package coolcostupit.openjs.modules;

import coolcostupit.openjs.foliascheduler.ServerImplementation;
import coolcostupit.openjs.foliascheduler.TaskImplementation;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import coolcostupit.openjs.foliascheduler.FoliaCompatibility;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

public class FoliaSupport {
    private static Boolean cached = null;
    private static Boolean isFoliaChecked = null;

    private enum TaskType {
        BUKKIT,
        FOLIA,
        THREADPOOL
    }

    private static final Map<Integer, Object> activeTasks = new ConcurrentHashMap<>();
    private static final Map<Integer, TaskType> taskTypes = new ConcurrentHashMap<>();
    private static final AtomicInteger nextTaskId = new AtomicInteger(1);

    // TODO: Make it shut down when plugins disables (that's why it is public)
    public static final ExecutorService threadPool = Executors.newCachedThreadPool();

    public static boolean isFolia() {
        if (cached == null) {
            if (isFoliaChecked == null) {
                boolean foliaDetected = false;
                try {
                    Method getRegionScheduler = Bukkit.getServer().getClass().getMethod("getRegionScheduler");
                    foliaDetected = getRegionScheduler != null;
                } catch (NoSuchMethodException | SecurityException ignored) {}
                isFoliaChecked = foliaDetected;
            }
            cached = true;
        }
        return isFoliaChecked;
    }

    public static int ScheduleTask(JavaPlugin plugin, Runnable function, long delay) {
        Object task;
        if (isFolia()) {
            ServerImplementation scheduler = new FoliaCompatibility(plugin).getServerImplementation();
            task = scheduler.async().runDelayed(function, delay);
            return addTask(task, TaskType.FOLIA);
        } else {
            task = Bukkit.getScheduler().runTaskLater(plugin, function, delay);
            return addTask(task, TaskType.BUKKIT);
        }
    }

    public static int DelayTask(JavaPlugin plugin, Runnable function, long delay) {
        if (isFolia()) {
            Future<?> task = threadPool.submit(() -> {
                try {
                    Thread.sleep(delay * 50L);
                    function.run();
                } catch (InterruptedException ignored) {}
            });
            return addTask(task, TaskType.THREADPOOL);
        } else {
            Object task = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, function, delay);
            return addTask(task, TaskType.BUKKIT);
        }
    }

    public static int runTask(JavaPlugin plugin, Runnable function) {
        if (isFolia()) {
            Future<?> task = threadPool.submit(function);
            return addTask(task, TaskType.THREADPOOL);
        } else {
            Object task = Bukkit.getScheduler().runTaskAsynchronously(plugin, function);
            return addTask(task, TaskType.BUKKIT);
        }
    }

    public static int runTaskSynchronously(JavaPlugin plugin, Runnable function) {
        Object task;
        if (isFolia()) {
            ServerImplementation scheduler = new FoliaCompatibility(plugin).getServerImplementation();
            task = scheduler.global().run(function);
            return addTask(task, TaskType.FOLIA);
        } else {
            task = Bukkit.getScheduler().runTask(plugin, function);
            return addTask(task, TaskType.BUKKIT);
        }
    }

    public static int ScheduleRepeatingTask(JavaPlugin plugin, Runnable function, long delay, long period) {
        Object task;
        if (isFolia()) {
            ServerImplementation scheduler = new FoliaCompatibility(plugin).getServerImplementation();
            task = scheduler.async().runAtFixedRate(function, delay, period);
            return addTask(task, TaskType.FOLIA);
        } else {
            task = Bukkit.getScheduler().runTaskTimer(plugin, function, delay, period);
            return addTask(task, TaskType.BUKKIT);
        }
    }

    public static boolean CancelTask(int taskId) {
        Object task = activeTasks.remove(taskId);
        TaskType type = taskTypes.remove(taskId);

        if (task == null || type == null) return false;

        try {
            switch (type) {
                case FOLIA:
                    ((TaskImplementation<?>) task).cancel();
                    break;
                case BUKKIT:
                    ((BukkitTask) task).cancel();
                    break;
                case THREADPOOL:
                    ((Future<?>) task).cancel(true);
                    break;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int addTask(Object task, TaskType type) {
        int taskId = nextTaskId.getAndIncrement();
        activeTasks.put(taskId, task);
        taskTypes.put(taskId, type);
        return taskId;
    }
}