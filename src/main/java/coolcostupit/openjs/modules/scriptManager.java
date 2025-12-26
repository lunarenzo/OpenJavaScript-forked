/*
 * Copyright (c) 2025 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice.
 * Reference from https://github.com/World-of-Eldin/eldin-openjavascript/blob/main/src/main/java/coolcostupit/openjs/utility/ScriptPathUtils.java
 */

package coolcostupit.openjs.modules;

import coolcostupit.openjs.logging.pluginLogger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class scriptManager {
    private static File scriptsFolder;
    private static final Map<String, File> SCRIPT_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> DISABLED_SCRIPTS = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOADING_SCRIPTS = ConcurrentHashMap.newKeySet();
    private static WatchService watchService;
    private static boolean initialized = false;
    private static File disabledScriptsFile;
    private static pluginLogger logger;

    public static File getScriptFolder(JavaPlugin plugin) {
        File folder = new File(plugin.getDataFolder(), "scripts");

        if (!folder.exists()) {
            if (!folder.mkdirs()) {
                logger.log(Level.SEVERE, "Failed to create scripts folder!", pluginLogger.RED);
                Bukkit.getPluginManager().disablePlugin(plugin);
            }
        }
        return folder;
    }

    private static void loadDisabledScripts(JavaPlugin plugin) {
        DISABLED_SCRIPTS.clear();

        disabledScriptsFile = new File(plugin.getDataFolder(), "disabledscripts.json");

        if (!disabledScriptsFile.exists()) {
            try {
                logger.log(Level.INFO, "Creating disabledscripts.json", pluginLogger.BLUE);
                Files.writeString(disabledScriptsFile.toPath(), "[]");
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to create disabledscripts.json", pluginLogger.RED);
            }
            return;
        }

        try {
            String json = Files.readString(disabledScriptsFile.toPath()).trim();
            logger.log(Level.INFO, "Loading disabled scripts: " + json, pluginLogger.BLUE);
            if (!json.startsWith("[") || !json.endsWith("]")) return;

            json = json.substring(1, json.length() - 1).trim();
            if (json.isEmpty()) return;

            for (String raw : json.split(",")) {
                String entry = raw.trim();
                if (entry.startsWith("\"") && entry.endsWith("\"")) {
                    entry = entry.substring(1, entry.length() - 1);
                }
                logger.log(Level.INFO, "Disabled script loaded: " + entry, pluginLogger.BLUE);
                if (!entry.isEmpty()) {
                    DISABLED_SCRIPTS.add(entry);
                }
            }

        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to load disabled scripts", pluginLogger.RED);
        }
    }

    public static synchronized void saveDisabledScripts() {
        if (disabledScriptsFile == null) return;

        try {
            StringBuilder json = new StringBuilder();
            json.append("[");

            Iterator<String> iterator = DISABLED_SCRIPTS.iterator();
            while (iterator.hasNext()) {
                String entry = iterator.next();
                json.append("\"").append(entry).append("\"");
                if (iterator.hasNext()) {
                    json.append(",");
                }
            }

            json.append("]");

            Files.writeString(
                    disabledScriptsFile.toPath(),
                    json.toString(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );

        } catch (IOException e) {
            logger.log(
                    Level.SEVERE,
                    "Failed to save disabled scripts list: " + e.getMessage(),
                    pluginLogger.RED
            );
        }
    }

    public static synchronized void initializeManager(JavaPlugin plugin) {
        if (initialized) return;
        initialized = true;
        SCRIPT_CACHE.clear();
        logger = sharedClass.logger;
        scriptsFolder = getScriptFolder(plugin);

        loadDisabledScripts(plugin);
        scanScripts(scriptsFolder);
        startWatcher(plugin, scriptsFolder);
    }

    private static void scanScripts(File scriptsFolder) {
        File[] files = scriptsFolder.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".js")) {
                String key = getRelativePath(file);
                if (key != null && !SCRIPT_CACHE.containsKey(key)) {
                    SCRIPT_CACHE.put(key, file);
                }
            }

            if (file.isDirectory()) {
                File main = getMainScript(file);
                if (main != null) {
                    String key = getRelativePath(main);
                    if (key != null && !SCRIPT_CACHE.containsKey(key)) {
                        SCRIPT_CACHE.put(key, main);
                    }
                }
            }
        }
    }

    private static File getMainScript(File folder) {
        if (folder.isFile() && folder.getName().endsWith(".js")) {
            return folder;
        }

        File mainLower = new File(folder, "main.js");
        File mainUpper = new File(folder, "Main.js");

        if (mainLower.exists()) return mainLower;
        if (mainUpper.exists()) return mainUpper;
        return null;
    }

    private static void startWatcher(JavaPlugin plugin, File scriptsFolder) {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            scriptsFolder.toPath().register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY
            );

            FoliaSupport.runTask(plugin, () -> {
                while (plugin.isEnabled()) {
                    try {
                        WatchKey key = watchService.take();

                        for (WatchEvent<?> event : key.pollEvents()) {
                            WatchEvent.Kind<?> kind = event.kind();
                            Path changed = (Path) event.context();
                            File affected = new File(scriptsFolder, changed.toString());

                            handleFileEvent(kind, affected);
                        }

                        key.reset();
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            });

        } catch (IOException e) {
            logger.log(Level.INFO, "Failed to start script watcher: " + e.getMessage(), pluginLogger.RED);
        }
    }

    private static void handleFileEvent(WatchEvent.Kind<?> kind, File file) {
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            onScriptAdded(file);
        }

        if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            onScriptRemoved(file);
        }

        if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
            logger.log(Level.INFO, "File modified: " + file.length(), pluginLogger.BLUE);
            onScriptFileChanged(file);
        }
    }

    public static String getRelativePath(File file) {
        try {
            Path base = scriptsFolder.toPath().toAbsolutePath().normalize();
            Path target = file.toPath().toAbsolutePath().normalize();

            String rel = base.relativize(target).toString();
            rel = rel.replace(File.separatorChar, '/');

            return rel;
        } catch (Exception e) {
            return null;
        }
    }

    public static void onScriptAdded(File file) {
        updateCacheFor(file);
        File MainScript = getMainScript(file);
        if (MainScript != null) {
            sharedClass.logger.log(Level.INFO, "Reloading script due to file added: " + getRelativePath(MainScript), pluginLogger.BLUE);
            sharedClass.scriptApi.loadScript(MainScript, false);
        }
    }

    public static void onScriptRemoved(File file) {
        SCRIPT_CACHE.entrySet().removeIf(e -> e.getValue().equals(file));
        if (isScriptEnabled(file)) {
            sharedClass.scriptApi.unloadScript(getRelativePath(file));
        }
        removeDisabledScript(file);
    }

    public static void onScriptFileChanged(File file) {
        updateCacheFor(file);
        File MainScript = getMainScript(file);
        if (MainScript != null) {
            sharedClass.logger.log(Level.INFO, "Reloading script due to file change: " + getRelativePath(MainScript), pluginLogger.BLUE);
            sharedClass.scriptApi.loadScript(MainScript, false);
        }
    }

    private static void updateCacheFor(File file) {
        if (file.isFile() && file.getName().endsWith(".js")) {
            String key = getRelativePath(file);
            if (key != null && !SCRIPT_CACHE.containsKey(key)) {
                SCRIPT_CACHE.put(key, file);
            }
        }
        else if (file.isDirectory()) {
            File main = getMainScript(file);
            if (main != null) {
                String key = getRelativePath(main);
                if (key != null && !SCRIPT_CACHE.containsKey(key)) {
                    SCRIPT_CACHE.put(key, main);
                }
            }
        }
    }

    public static File stringToScript(String relativePath) {
        return SCRIPT_CACHE.get(relativePath);
    }

    public static boolean isScriptLoading(String relativeScriptPath) {
        return LOADING_SCRIPTS.contains(relativeScriptPath);
    }

    public static boolean isScriptDisabled(File scriptFile) {
        String key = getRelativePath(scriptFile);
        return key != null && DISABLED_SCRIPTS.contains(key);
    }

    public static void setScriptDisabled(File scriptFile) {
        String key = getRelativePath(scriptFile);
        if (key != null) DISABLED_SCRIPTS.add(key);
    }
    public static void setScriptLoading(String relativeScriptPath, boolean isLoading) {
        if (isLoading) {
            LOADING_SCRIPTS.add(relativeScriptPath);
        } else {
            LOADING_SCRIPTS.remove(relativeScriptPath);
        }
    }

    public static void setScriptEnabled(File scriptFile) {
        String key = getRelativePath(scriptFile);
        if (key != null) DISABLED_SCRIPTS.remove(key);
    }

    public static boolean isScriptEnabled(File scriptFile) {
        return !isScriptDisabled(scriptFile); // Redundant and stupid? Well, I KNOW ;-;
    }

    public static void removeDisabledScript(File scriptFile) {
        String key = getRelativePath(scriptFile);
        if (key != null) {
            DISABLED_SCRIPTS.remove(key);
        }
    }

    public static Map<String, File> getScriptCache() {
        return new HashMap<>(SCRIPT_CACHE);
    }

    public static String getScriptName(File scriptFile) {
        String rel = getRelativePath(scriptFile);
        if (rel == null) return scriptFile.getName();
        if (rel.endsWith("/Main.js") || rel.endsWith("/main.js")) {
            return rel.substring(0, rel.lastIndexOf('/'));
        }
        return rel;
    }
}
