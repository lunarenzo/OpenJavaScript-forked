package coolcostupit.openjs.modules;

import coolcostupit.openjs.foliascheduler.ServerImplementation;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import coolcostupit.openjs.foliascheduler.FoliaCompatibility;

import java.lang.reflect.Method;

public class FoliaSupport {
    private static Boolean cached = null;
    private static Boolean isFoliaChecked = null;

    //Check if the server is running on Folia and cache the result
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

    public static Object ScheduleTask(JavaPlugin plugin, Runnable function, long delay) {
        if (isFolia()) {
            // Use FoliaScheduler
            ServerImplementation scheduler = new FoliaCompatibility(plugin).getServerImplementation();
            return scheduler.async().runDelayed(function, delay);
        } else {
            // Use normal Bukkit scheduler
            return Bukkit.getScheduler().runTaskLater(plugin, function, delay);
        }
    }

    public static Object runTask(JavaPlugin plugin, Runnable function) {
        if (isFolia()) {
            // Use FoliaScheduler
            ServerImplementation scheduler = new FoliaCompatibility(plugin).getServerImplementation();
            return scheduler.async().runNow(function);
        } else {
            // Use normal Bukkit scheduler
            return Bukkit.getScheduler().runTask(plugin, function);
        }
    }

    public static Object runTaskSynchronously(JavaPlugin plugin, Runnable function) {
        if (isFolia()) {
            // Use FoliaScheduler
            ServerImplementation scheduler = new FoliaCompatibility(plugin).getServerImplementation();
            return scheduler.global().run(function);
        } else {
            // Use normal Bukkit scheduler
            return Bukkit.getScheduler().runTask(plugin, function);
        }
    } 

    public static Object ScheduleRepeatingTask(JavaPlugin plugin, Runnable function, long delay, long period) {
        if (isFolia()) {
            // Use FoliaScheduler
            ServerImplementation scheduler = new FoliaCompatibility(plugin).getServerImplementation();
            return scheduler.async().runAtFixedRate(function, delay, period);
        } else {
            // Use normal Bukkit scheduler
            return Bukkit.getScheduler().runTaskTimer(plugin, function, delay, period);
        }
    }
}
