/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.modules;

import coolcostupit.openjs.logging.pluginLogger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Map<WatchKey, Path> WATCH_KEYS = new ConcurrentHashMap<>();
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
            Files.writeString(disabledScriptsFile.toPath(), json.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save disabled scripts list: " + e.getMessage(), pluginLogger.RED);
        }
    }

    public static synchronized void initializeManager(JavaPlugin plugin) {
        if (initialized) return;
        initialized = true;
        SCRIPT_CACHE.clear();
        logger = sharedClass.logger;
        scriptsFolder = getScriptFolder(plugin);
        sharedClass.scriptFolder = scriptsFolder;

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

    private static File getMainScript(File file) {
        if (file == null) return null;
        if (isJavascript(file.getName()) && file.getParentFile().equals(scriptsFolder)) return file;

        File current = file.isDirectory() ? file : file.getParentFile();

        while (current != null && !current.equals(scriptsFolder)) {
            File mainLower = new File(current, "main.js");
            if (mainLower.exists()) return mainLower;

            File mainUpper = new File(current, "Main.js");
            if (mainUpper.exists()) return mainUpper;

            current = current.getParentFile();
        }

        return null;
    }

    private static void registerRecursiveWatcher(Path start) throws IOException {
        try {
            Files.walk(start)
                    .filter(Files::isDirectory)
                    .forEach(path -> {
                        try {
                            WatchKey key = path.register(
                                    watchService,
                                    StandardWatchEventKinds.ENTRY_CREATE,
                                    StandardWatchEventKinds.ENTRY_DELETE,
                                    StandardWatchEventKinds.ENTRY_MODIFY
                            );
                            WATCH_KEYS.put(key, path);
                        } catch (IOException e) {
                            logger.log(Level.SEVERE, "Restricted path: " + path.toAbsolutePath(), pluginLogger.RED);
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
                        Path dir = WATCH_KEYS.get(key);
                        if (dir == null) continue;

                        for (WatchEvent<?> event : key.pollEvents()) {
                            WatchEvent.Kind<?> kind = event.kind();
                            Path changed = dir.resolve((Path) event.context());
                            File affected = changed.toFile();

                            if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                            handleFileEvent(kind, affected);
                        }

                        if (!key.reset()) {
                            WATCH_KEYS.remove(key);
                        }
                    } catch (InterruptedException e) {
                        logger.log(Level.INFO, "An error caused a watcher interruption: " + e.getMessage(), pluginLogger.RED);
                    }
                }
            });

        } catch (IOException e) {
            logger.log(Level.INFO, "Failed to start script watcher: " + e.getMessage(), pluginLogger.RED);
        }
    }

    private static void handleFileEvent(WatchEvent.Kind<?> kind, File file) {
        try {
            if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                if (file.isDirectory()) {
                    try {
                        registerRecursiveWatcher(file.toPath());
                    } catch (IOException ignored) {
                    }
                }
                onScriptAdded(file);
            }

            if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                onScriptRemoved(file);
            }

            if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                onScriptFileChanged(file);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error handling file event for " + file.getAbsolutePath() + ": " + e.getMessage(), pluginLogger.RED);
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
        if (sharedClass.configUtil.getConfigFromBuffer("AutoReloadScriptsOnChange", true)) {
            if (isJavascript(file.getName())) {
                File MainScript = getMainScript(file);
                if (MainScript != null && isScriptEnabled(MainScript)) {
                    sharedClass.scriptApi.loadScript(MainScript, false);
                }
            }
        }
    }

    public static void onScriptRemoved(File file) {
        removeCodeCache(file);
        if (isJavascript(file.getName())) {
            SCRIPT_CACHE.entrySet().removeIf(e -> e.getValue().equals(file));
            if (isScriptEnabled(file)) {
                sharedClass.scriptApi.unloadScript(getRelativePath(file));
            }
            if (!file.exists()) {
                removeDisabledScript(file);
            }
        }
    }

    public static void onScriptFileChanged(File file) {
        updateCacheFor(file);
        cacheCode(file);
        if (sharedClass.configUtil.getConfigFromBuffer("AutoReloadScriptsOnChange", true)) {
            if (isJavascript(file.getName())) {
                File script = getMainScript(file);
                if (script != null && isScriptEnabled(script)) {
                    scriptWrapper.ScriptLoadResult result = sharedClass.scriptApi.loadScript(script, false);
                }
            }
        }
    }

    private static void updateCacheFor(File file) {
        File main = getMainScript(file);
        if (main != null) {
            String key = getRelativePath(main);
            if (key != null && !SCRIPT_CACHE.containsKey(key)) {
                SCRIPT_CACHE.put(key, main);
            }
        }
    }

    public static File stringToScript(String relativePath) {
        return Objects.requireNonNullElseGet(SCRIPT_CACHE.get(relativePath), () -> new File(scriptsFolder.getAbsolutePath(), relativePath));
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
            logger.log(Level.SEVERE, "Failed to cache script code: " + file.getAbsolutePath(), pluginLogger.RED);
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

    public static boolean isRelativePath(String path) {
        if (path == null || path.isEmpty()) return false;
        return !Paths.get(path).isAbsolute();
    }
}
