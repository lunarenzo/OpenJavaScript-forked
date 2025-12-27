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
    private static final Map<String, String> CODE_CACHE = new ConcurrentHashMap<>();
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
                Files.writeString(disabledScriptsFile.toPath(), "[]");
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to create disabledscripts.json", pluginLogger.RED);
            }
            return;
        }

        try {
            String json = Files.readString(disabledScriptsFile.toPath()).trim();
            if (!json.startsWith("[") || !json.endsWith("]")) return;

            json = json.substring(1, json.length() - 1).trim();
            if (json.isEmpty()) return;

            for (String raw : json.split(",")) {
                String entry = raw.trim();
                if (entry.startsWith("\"") && entry.endsWith("\"")) {
                    entry = entry.substring(1, entry.length() - 1);
                }
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
        initializeCodeCache();
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

    private static void registerRecursiveWatcher(Path start) throws IOException {
        try {
            Files.walk(start)
                    .filter(Files::isDirectory)
                    .forEach(path -> {
                        try {
                            path.register(
                                    watchService,
                                    StandardWatchEventKinds.ENTRY_CREATE,
                                    StandardWatchEventKinds.ENTRY_DELETE,
                                    StandardWatchEventKinds.ENTRY_MODIFY
                            );
                        } catch (IOException e) {
                            logger.log(Level.INFO, "Restricted path: " + path.toAbsolutePath(), pluginLogger.RED);
                            logger.log(Level.SEVERE, "Failed to register watcher for path: " + e.getMessage(), pluginLogger.RED);
                        }
                    });
        } catch (IOException e) {
            logger.log(Level.INFO, "Restricted path: " + start.toAbsolutePath(), pluginLogger.RED);
            logger.log(Level.SEVERE, "Failed to register recursive watcher: " + e.getMessage(), pluginLogger.RED);
        }
    }

    private static void startWatcher(JavaPlugin plugin, File scriptsFolder) {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            registerRecursiveWatcher(scriptsFolder.toPath());

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

                        if (!key.reset()) {
                            break;
                        }
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
            if (file.isDirectory()) {
                try {
                    registerRecursiveWatcher(file.toPath());
                } catch (IOException ignored) {}
            }
            onScriptAdded(file);
        }

        if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            onScriptRemoved(file);
        }

        if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
            onScriptFileChanged(file);
        }
    }

    public static boolean isJavascript(String relativePath) {
        return relativePath.endsWith(".js");
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
        logger.log(Level.INFO, "Script added: " + file.getAbsolutePath(), pluginLogger.GREEN);
        if (MainScript != null && isScriptEnabled(MainScript)) {
            sharedClass.scriptApi.loadScript(MainScript, false);
        }
    }

    public static void onScriptRemoved(File file) {
        File script = getMainScript(file);
        removeCodeCache(file);
        if (script != null) {
            SCRIPT_CACHE.entrySet().removeIf(e -> e.getValue().equals(script));
            logger.log(Level.INFO, "Script removed: " + script.getAbsolutePath(), pluginLogger.ORANGE);
            if (isScriptEnabled(script)) {
                sharedClass.scriptApi.unloadScript(getRelativePath(script));
            }
            removeDisabledScript(script);
        }
    }

    public static void onScriptFileChanged(File file) {
        updateCacheFor(file);
        cacheCode(file);
        File script = getMainScript(file);

        if (script != null && isScriptEnabled(script)) {
            logger.log(Level.INFO, "Script changed: " + file.getAbsolutePath(), pluginLogger.LIGHT_BLUE);
            scriptWrapper.ScriptLoadResult result = sharedClass.scriptApi.loadScript(script, false);
            logger.log(Level.INFO, result.getMessage(), pluginLogger.LIGHT_BLUE);
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

    private static void initializeCodeCache() {
        if (scriptsFolder == null || !scriptsFolder.exists()) return;

        try {
            Files.walk(scriptsFolder.toPath()).filter(Files::isRegularFile).filter(path -> isJavascript(path.getFileName().toString())).forEach(path -> cacheCode(path.toFile()));
            logger.log(Level.INFO, "Code cache initialized (" + CODE_CACHE.size() + " scripts cached)", pluginLogger.BLUE);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to initialize code cache: " + e.getMessage(), pluginLogger.RED);
        }
    }

    public static void cacheCode(File file) {
        if (file == null || !file.exists()) return;
        if (!isJavascript(file.getName())) return;

        try {
            String code = Files.readString(file.toPath());
            CODE_CACHE.put(getRelativePath(file), code); // overwrite = update
        } catch (IOException e) {
            logger.log(
                    Level.SEVERE,
                    "Failed to cache script code: " + file.getAbsolutePath(),
                    pluginLogger.RED
            );
        }
    }

    public static String getRelativeParentFolder(File file) {
        try {
            Path base = scriptsFolder.toPath().toAbsolutePath().normalize();
            Path target = file.toPath().toAbsolutePath().normalize();
            Path relative = base.relativize(target);

            if (relative.getNameCount() <= 1) {
                return "";
            }

            Path parent = relative.getParent();
            if (parent == null) return "";

            return parent.toString().replace(File.separatorChar, '/');
        } catch (Exception e) {
            return "";
        }
    }

    public static String readCode(String relativePath) {
        String cached = CODE_CACHE.get(relativePath);
        if (cached != null) {
            return cached;
        }

        // Lazy-load fallback
        File file = new File(scriptsFolder.getAbsolutePath(), relativePath);
        if (file == null || !file.exists()) return null;

        try {
            String code = Files.readString(file.toPath());
            CODE_CACHE.put(relativePath, code);
            return code;
        } catch (IOException e) {
            logger.log(
                    Level.SEVERE,
                    "Failed to read script code: " + relativePath,
                    pluginLogger.RED
            );
            return null;
        }
    }

    public static void removeCodeCache(File file) {
        CODE_CACHE.remove(getRelativePath(file));
    }
}
