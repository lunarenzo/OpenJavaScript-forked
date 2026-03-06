/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.modules;

import coolcostupit.openjs.foliascheduler.ServerImplementation;
import coolcostupit.openjs.foliascheduler.TaskImplementation;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import coolcostupit.openjs.foliascheduler.FoliaCompatibility;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

public class FoliaSupport {
    private static Boolean isFolia = null;

    private enum TaskType {
        BUKKIT,
        FOLIA,
        THREADPOOL
    }

    private static final Map<Integer, Object> activeTasks = new ConcurrentHashMap<>();
    private static final Map<Integer, TaskType> taskTypes = new ConcurrentHashMap<>();
    private static final AtomicInteger nextTaskId = new AtomicInteger(1);
    private static ExecutorService threadPool;
    private static BukkitScheduler bukkitScheduler;
    private static JavaPlugin plugin;

    @FunctionalInterface
    private interface SyncScheduler { int run(Runnable fn); }
    private static SyncScheduler syncScheduler;

    public static void init() {
        threadPool = Executors.newCachedThreadPool();
        sharedClass.TaskThreadPool = threadPool;
        plugin = sharedClass.plugin;

        if (isFolia()) {
            ServerImplementation foliaServer = new FoliaCompatibility(plugin).getServerImplementation();
            syncScheduler      = (fn)          -> addTask(foliaServer.global().run(fn), TaskType.FOLIA);
        } else {
            bukkitScheduler = Bukkit.getScheduler();
            syncScheduler      = (fn)          -> addTask(bukkitScheduler.runTask(plugin, fn), TaskType.BUKKIT);
        }
    }


    public static boolean isFolia() {
        if (isFolia == null) {
            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                isFolia = true;
            } catch (ClassNotFoundException e) {
                isFolia = false;
            }
        }
        return isFolia;
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


    public static int runEntityTask(JavaPlugin plugin, Entity entity, Runnable function) {
        if (isFolia()) {
            ServerImplementation scheduler = new FoliaCompatibility(plugin).getServerImplementation();
            TaskImplementation<?> foliaTask = scheduler.entity(entity).run(function);
            return addTask(foliaTask, TaskType.FOLIA);

        } else {
            // Bukkit: just run Runnable on main thread
            BukkitTask task = Bukkit.getScheduler().runTask(plugin, function);
            return addTask(task, TaskType.BUKKIT);
        }
    }


    public static int DelayTask(JavaPlugin plugin, Runnable function, long delay) {
        Future<?> task = threadPool.submit(() -> {
            try {
                Thread.sleep(delay * 50L);
                function.run();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        return addTask(task, TaskType.THREADPOOL);
    }

    // Asynchronous task
    public static int runTask(JavaPlugin plugin, Runnable function) {
        return addTask(threadPool.submit(function), TaskType.THREADPOOL);
    }

    //TODO: Deprecate
    public static int runThreadPoolTask(Runnable function) {
        return addTask(threadPool.submit(function), TaskType.THREADPOOL);
    }

    public static int runTaskSynchronously(JavaPlugin plugin, Runnable function) {
        return syncScheduler.run(function);
    }

    public static void runTasklessSynchronously(JavaPlugin plugin, Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        runTaskSynchronously(plugin, task);
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