/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.modules;

import coolcostupit.openjs.ServiceManager.ServiceLoader;
import coolcostupit.openjs.events.ScriptLoadedEvent;
import coolcostupit.openjs.events.ScriptUnloadedEvent;
import coolcostupit.openjs.logging.ScriptLogger;
import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.ServiceObjects.ScriptClassObject;
import coolcostupit.openjs.utility.*;
import org.bukkit.plugin.java.JavaPlugin;

import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.*;
import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

import static org.bukkit.Bukkit.getLogger;
import static org.bukkit.Bukkit.getPluginManager;

// this is the main stuff, but I haven't added many to no comments because I was way too focused when coding all that
// TODO: This is flooded with so much stupid stuff I did; Refactor and fix inconsistencies (especially with script logging)
// Currently before folder support has been added, scripts logged errors and infos by their name, this should change.
// WARNS and ERRORS should show the relative path, so devs know which script is causing the error
// INFOS should show the computed name (on super-scripts it will be their folder, anything else will be relative path)

public class scriptWrapper {
    private boolean scriptsReady = false;
    private boolean hasInit = false;
    private final Map<String, Future<?>> scriptFutures = new HashMap<>();
    private final Map<String, ScriptEngine> scriptEngines = new HashMap<>();
    private static final Map<String, List<Runnable>> cleanUpMethods = new ConcurrentHashMap<>();
    private final JavaPlugin plugin;
    private final pluginLogger Logger;
    private final PublicVarManager PublicVarManager;
    private final configurationUtil configUtil;
    public final List<String> runningScripts = new ArrayList<>();
    public final ExecutorService executorService;
    private final scriptTaskerApi taskApi;

    public scriptWrapper(JavaPlugin plugin, configurationUtil configUtil) {
        this.plugin = plugin;
        this.Logger = new pluginLogger(plugin, configUtil);
        this.PublicVarManager = new PublicVarManager();
        this.configUtil = configUtil;
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.taskApi = new scriptTaskerApi(this);

        // Experimental flag to enable ECMAScript 6.0
        System.setProperty("nashorn.args", "--language=es6");

        // Initialize script system on first use
        if (!hasInit) {
            hasInit = true;
            FoliaSupport.ScheduleTask(plugin, () -> scriptsReady = true, 20L);
        }
    }

    public boolean isJavascriptFileRunning(String fileName) {
        return runningScripts.contains(fileName);
    }

    public static void addToCleanupMap(String scriptName, Runnable method) {
        cleanUpMethods.computeIfAbsent(scriptName, k -> new ArrayList<>()).add(method);
    }

    private static void invokeScriptCleanup(String scriptName) {
        List<Runnable> tasks = cleanUpMethods.remove(scriptName);
        if (tasks != null) {
            for (Runnable cleanup : tasks) {
                try {
                    cleanup.run();
                } catch (Exception e) {
                    sharedClass.logger.logScriptError("External plugin methods cleanup failed:", scriptName);
                    sharedClass.logger.logScriptError(e, scriptName);
                }
            }
        }
    }

    public void unloadAllScripts() {
        for (Map.Entry<String, File> entry : scriptManager.getScriptCache().entrySet()) {
            unloadScript(entry.getKey());
            //File scriptFile = entry.getValue();
        }
    }

    private Method getMethod(Class<?> clazz, String methodName) throws NoSuchMethodException {
        try {
            return clazz.getDeclaredMethod(methodName);
        } catch (NoSuchMethodException e) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass == null) {
                throw e;
            } else {
                return getMethod(superClass, methodName);
            }
        }
    }

    public void checkDisabledScripts() {
        for (Map.Entry<String, File> entry : scriptManager.getScriptCache().entrySet()) {
            String scriptName = entry.getKey();
            File scriptFile = entry.getValue();
            if (scriptManager.isScriptDisabled(scriptFile) && !scriptFile.exists()) {
                scriptManager.removeDisabledScript(scriptFile);
                Logger.log(Level.INFO, "Removed non-existent script " + scriptName + " from disabled scripts list.", pluginLogger.BLUE);
            }
        }
    }

    public void unloadScript(String scriptName) {
        if (!runningScripts.contains(scriptName)) {
            return;
        }
        ScriptEngine engine = scriptEngines.get(scriptName);
        scriptTaskerApi.cancelTasksFromScript(scriptName);

        if (engine != null) {
            if (engine instanceof Invocable invocable) {
                try {
                    invocable.invokeFunction("__runUnloadBinds");
                } catch (ScriptException e) {
                    Logger.logScriptError("There was a problem running onUnload:", scriptName);
                    Logger.logScriptError(e, scriptName);
                } catch (NoSuchMethodException ignored) {} // its optional so yea, if it does not exist, then ignore
            }
        }

        invokeScriptCleanup(scriptName);
        taskApi.clearListeners(scriptName);
        InternalSystems.unregisterListenersFromScript(scriptName);
        sharedClass.DiskStorageApi.saveCaches(scriptName); // ASYNC ?=> yields
        Future<?> future = scriptFutures.remove(scriptName);

        if (future != null) {
            future.cancel(true);
        }

        scriptEngines.remove(scriptName);
        runningScripts.remove(scriptName);

        if (engine != null) {

            if (engine instanceof Invocable invocable) {
                try {
                    invocable.invokeFunction("_unloadThis");
                } catch (NoSuchMethodException | ScriptException e) {
                    Logger.scriptlog(Level.WARNING, scriptName, "Failed to garbage collect: " + e.getMessage(), pluginLogger.RED);
                }
            }

            engine.getBindings(ScriptContext.ENGINE_SCOPE).clear();

            if (engine instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) engine).close();
                } catch (Exception e) {
                    Logger.scriptlog(Level.SEVERE, scriptName, "Failed to close script engine: " + e.getMessage(), pluginLogger.RED);
                }
            }

            Logger.scriptlog(Level.INFO, scriptName, "has been unloaded!", pluginLogger.LIGHT_BLUE);
        }

        if (plugin.isEnabled()) {
            FoliaSupport.runTaskSynchronously(plugin, () -> plugin.getServer().getPluginManager().callEvent(new ScriptUnloadedEvent(scriptName)));
        }
    }

    //TODO: deprecate this
    public String preprocessScript(File scriptFile, ScriptEngine scriptEngine) throws IOException {
        String sourceCode = scriptManager.readCode(scriptManager.getRelativePath(scriptFile));
        List<String> imports = new ArrayList<>();

        if (sourceCode == null) {
            Logger.scriptlog(Level.WARNING, scriptManager.getScriptName(scriptFile), "Script source code is null!", pluginLogger.ORANGE);
            return "";
        }

        if (sourceCode.contains("//!import ")) {
            try (BufferedReader reader = new BufferedReader(new StringReader(sourceCode))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("//!import ")) {
                        imports.add(line.substring(9).trim());
                    }
                }
            }
        }
        boolean LoggedWarning = false;

        for (String importStatement : imports) {
            try {
                if (!LoggedWarning) {
                    Logger.scriptlog(Level.WARNING, scriptFile.getName(), "Using //!import is deprecated and will be removed in future versions. Please use import('xx') instead.", pluginLogger.ORANGE);
                    LoggedWarning = true;
                }
                Class<?> clazz = Class.forName(importStatement);
                String simpleName = clazz.getSimpleName();
                scriptEngine.put(simpleName, clazz);
            } catch (ClassNotFoundException e) {
                Logger.scriptlog(Level.WARNING, scriptFile.getName(), "Class not found for import: " + importStatement, pluginLogger.ORANGE);
            }
        }

        // Internal exception catcher
        return """
        (function() {
            try {
                %s
            } catch (e) {
                _internalPluginLogger.internalException(currentScriptName, "Exception: " + e);
                if (e && e.stack) _internalPluginLogger.internalException(currentScriptName, "Stack: " + e.stack);
            }
        })();
        """.formatted(sourceCode);
    }

    public List<String> getNotLoadedScripts() {
        File scriptsFolder = new File(plugin.getDataFolder(), "scripts");
        List<String> notLoadedScripts = new ArrayList<>();
        if (scriptsFolder.exists() && scriptsFolder.isDirectory()) {
            for (File scriptFile : Objects.requireNonNull(scriptsFolder.listFiles())) {
                if (scriptFile.isFile() && scriptFile.getName().endsWith(".js") && !scriptManager.getScriptCache().containsKey(scriptFile.getName())) {
                    notLoadedScripts.add(scriptFile.getName());
                }
            }
        }
        return notLoadedScripts;
    }

    @SuppressWarnings("all")
    public static class ScriptLoadResult { // this is ancient from 1.0.0 Alpha, may need to look into it
        private final boolean success;
        private final String message;

        public ScriptLoadResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    } // Took a look at it, somehow it is perfect! (but ancient)

    public ScriptLoadResult loadScript(File scriptFile, boolean calledFromScript) {
        if (scriptFile.isFile() && scriptFile.getName().endsWith(".js")) {
            if (calledFromScript) {
                if (!hasInit || !scriptsReady) {
                    return new ScriptLoadResult(false, "Do not manually load scripts while they are being initialized!");
                }
            } else {
                if (FlagInterpreter.hasFlag(scriptFile, "loadManually")) {
                    return new ScriptLoadResult(false, "Script file will only load manually");
                }
            }

            String RelativePath = scriptManager.getRelativePath(scriptFile);
            String ScriptName = scriptManager.getScriptName(scriptFile);

            if (scriptManager.isScriptLoading(RelativePath)) {
                return new ScriptLoadResult(false, "Script is already loading.");
            }

            // HEY, this is the main process of loading any script.
            // Currently, there is no sandboxing/security. This will not change in the future
            // It should be up to the developer what they want to create with OpenJS; just like it is with Plugins.
            // Those have no enforced security, everything is up to the developer
            // OK thank you for reading, have a nice day!
            scriptManager.setScriptLoading(RelativePath, true);
            unloadScriptSynced(RelativePath);
            ScriptEngine localScriptEngine = coolcostupit.openjs.modules.ScriptEngine.getEngine();
            scriptEngines.put(RelativePath, localScriptEngine);
            ScriptClassObject scriptClass = new ScriptClassObject(RelativePath);

            // Initialize the custom in-built stuff
            localScriptEngine.put("plugin", plugin);
            localScriptEngine.put("log", new ScriptLogger(getLogger(), scriptClass));
            localScriptEngine.put("scriptManager", this); // TODO: Try to lazy-load this, loading it on every script is memory intensive
            localScriptEngine.put("scriptEngine", localScriptEngine);
            localScriptEngine.put("currentScriptName", ScriptName);
            localScriptEngine.put("__currentScriptId", RelativePath); // internal use only
            localScriptEngine.put("DiskStorage", sharedClass.DiskStorageApi);
            localScriptEngine.put("publicVarManager", PublicVarManager);
            localScriptEngine.put("_task", taskApi); // See class: JavascriptHelper
            localScriptEngine.put("_libImporter", sharedClass.LibImporterApi);
            localScriptEngine.put("_internalPluginLogger", Logger);
            localScriptEngine.put("IsFoliaServer", FoliaSupport.isFolia());
            localScriptEngine.put("script", scriptClass);
            localScriptEngine.put("Services", new ServiceLoader(localScriptEngine, ScriptName, scriptClass));
            localScriptEngine.put("_InternalModules", new InternalSystems(ScriptName, localScriptEngine, scriptClass));

            Future<?> future = executorService.submit(() -> {
                try {
                    localScriptEngine.eval(JavascriptHelper.JAVASCRIPT_CODE);
                    String processedScript = preprocessScript(scriptFile, localScriptEngine);
                    localScriptEngine.eval(processedScript);
                    if (configUtil.getConfigFromBuffer("PrintScriptActivations", true)) {
                        Logger.log(Level.INFO, "Loaded the script " + ScriptName, pluginLogger.GREEN);
                    }
                    FoliaSupport.runTaskSynchronously(plugin, () -> plugin.getServer().getPluginManager().callEvent(new ScriptLoadedEvent(ScriptName)));
                } catch (IOException | ScriptException e) {
                    Logger.scriptlog(Level.WARNING,  ScriptName, "Failed to load script " + e.getMessage(), pluginLogger.ORANGE);
                }
            });

            if (scriptManager.isScriptDisabled(scriptFile)) {
                scriptManager.setScriptEnabled(scriptFile);
            }

            if (!runningScripts.contains(RelativePath)) {
                runningScripts.add(RelativePath);
            }

            scriptFutures.put(RelativePath, future);
            // TODO: Separate the "finally" with an 120 seconds timeout
            FoliaSupport.runTask(plugin, () -> {
                try {
                    future.get(1, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    Logger.scriptlog(Level.WARNING, ScriptName, "Script is taking long to load!", pluginLogger.ORANGE);
                } catch (Exception e) {
                    Logger.scriptlog(Level.SEVERE, ScriptName, "Error while loading script: " + e.getMessage(), pluginLogger.ORANGE);
                } finally {
                    scriptManager.setScriptLoading(RelativePath, false);
                }
            });

            return new ScriptLoadResult(true, "Script loaded successfully.");
        }
        return new ScriptLoadResult(false, "Invalid script file.");
    }

    public void loadScriptSynced(File scriptFile) {
        Future<?> future = executorService.submit(() -> loadScript(scriptFile, false));
        try {
            future.get();
        } catch (Exception e) {
            Logger.scriptlog(Level.SEVERE, scriptManager.getScriptName(scriptFile), "Error while loading script asynchronously: " + e.getMessage(), pluginLogger.ORANGE);
        }
    }

    public void unloadScriptSynced(String scriptName) {
        Future<?> future = executorService.submit(() -> unloadScript(scriptName));
        try {
            future.get();
        } catch (Exception e) {
            Logger.scriptlog(Level.SEVERE, scriptName, "Error while unloading script asynchronously: " + e.getMessage(), pluginLogger.ORANGE);
        }
    }

    public void loadScripts() {
        unloadAllScripts();

        for (Map.Entry<String, File> entry : scriptManager.getScriptCache().entrySet()) {
            File scriptFile = entry.getValue();
            if (!scriptManager.isScriptEnabled(scriptFile)) continue;

            if (scriptManager.isMainScript(scriptFile)) {
                File scriptPack = scriptFile.getParentFile();
                String pluginExtractor = scriptPackManager.getPluginExtractor(scriptPack);

                if (pluginExtractor != null && !getPluginManager().isPluginEnabled(pluginExtractor)) {
                    try {
                        scriptManager.recursiveDelete(scriptPack);
                    } catch (Exception e) {
                        Logger.log(Level.WARNING, "Failed to delete script pack " + scriptPack.getName() + ": " + e.getMessage(), pluginLogger.RED);
                    }
                    Logger.log(Level.WARNING, "Uninstalled script pack " + scriptPack.getName(), pluginLogger.ORANGE);
                    continue;
                }
            }

            loadScriptSynced(scriptFile);
        }
    }
}
