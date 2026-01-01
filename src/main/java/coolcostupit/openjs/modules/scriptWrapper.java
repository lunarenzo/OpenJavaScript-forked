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
import coolcostupit.openjs.pluginbridges.BridgeLoader;
import coolcostupit.openjs.ServiceObjects.ScriptClassObject;
import coolcostupit.openjs.utility.*;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.*;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

import static org.bukkit.Bukkit.getLogger;

// this is the main stuff, but I haven't added many to no comments because I was way too focused when coding all that
public class scriptWrapper {
    private boolean scriptsReady = false;
    private boolean hasInit = false;
    public final Map<String, List<Integer>> scriptTasksMap = new HashMap<>();
    private final Map<String, Future<?>> scriptFutures = new HashMap<>();
    private final Map<String, ScriptEngine> scriptEngines = new HashMap<>();
    private final Map<String, List<Command>> scriptCommands = new HashMap<>();
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
        this.executorService = Executors.newCachedThreadPool();
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

    public void unregisterTasksFromScript(String scriptName) {
        List<Integer> taskIds = scriptTasksMap.get(scriptName);
        if (taskIds != null) {
            for (int taskId : taskIds) {
                FoliaSupport.CancelTask(taskId);
            }
            scriptTasksMap.remove(scriptName);
        }
    }

    private static void invokeScriptCleanup(String scriptName) {
        List<Runnable> tasks = cleanUpMethods.remove(scriptName);
        if (tasks != null) {
            for (Runnable cleanup : tasks) {
                try {
                    cleanup.run();
                } catch (Exception e) {
                    sharedClass.logger.scriptlog(Level.INFO, scriptName, "External plugin methods cleanup failed: " + e.getMessage(), pluginLogger.ORANGE);
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

    public void unregisterAllTasks() {
        for (List<Integer> taskIds : scriptTasksMap.values()) {
            for (int taskId : taskIds) {
                FoliaSupport.CancelTask(taskId);
            }
        }
        scriptTasksMap.clear();
    }

    public CommandMap getCommandMap() {
        CommandMap commandMap = null;

        try {
            Field f = Bukkit.getPluginManager().getClass().getDeclaredField("commandMap");
            f.setAccessible(true);

            commandMap = (CommandMap) f.get(Bukkit.getPluginManager());
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException | SecurityException e) {
            Logger.log(Level.SEVERE, "Failed to load CommandMap: " + e.getMessage(), pluginLogger.RED);
        }

        return commandMap;
    }

    private void removeCommandFromKnownCommands(String commandName) throws Exception {
        CommandMap commandMap = getCommandMap();

        Field knownCommandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");
        knownCommandsField.setAccessible(true);

        Map<String, Command> knownCommands = (Map<String, Command>) knownCommandsField.get(commandMap);
        knownCommands.remove(commandName); // Remove the command
    }

    private void invokeSyncCommands() {
        try {
            Class<?> serverClass = Bukkit.getServer().getClass();
            Method method = getMethod(serverClass, "syncCommands");
            method.setAccessible(true);
            method.invoke(Bukkit.getServer());
            method.setAccessible(false);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            Logger.log(Level.SEVERE, "Failed to sync commands: " + e.getMessage(), pluginLogger.RED);
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

    // Unregister all commands for a specific script
    public void unregisterCommands(String scriptName) {
        List<Command> commands = scriptCommands.remove(scriptName);
        if (commands != null) {
            try {
                CommandMap commandMap = getCommandMap();

                for (Command dynamicCommand : commands) {
                    // Unregister the command using CommandMap directly
                    removeCommandFromKnownCommands(dynamicCommand.getName());
                    boolean Unregistered = dynamicCommand.unregister(commandMap);
                    if (Unregistered) {
                        if (configUtil.getConfigFromBuffer("LogCustomCommandsActivity", true)) {
                            Logger.scriptlog(Level.INFO, scriptName, "Unregistered command: " + dynamicCommand.getName(), pluginLogger.GREEN);
                        }
                        invokeSyncCommands();
                    } else {
                        Logger.scriptlog(Level.INFO, scriptName, "Failed to unregister command: " + dynamicCommand.getName(), pluginLogger.ORANGE);
                    }
                }
            } catch (Exception e) {
                Logger.scriptlog(Level.SEVERE, scriptName, "Failed to unregister commands: " + e, pluginLogger.RED);
                Logger.scriptlog(Level.SEVERE, scriptName, e.getMessage(), pluginLogger.RED);
            }
        }
    }


    // Unregister all dynamically registered commands
    public void unregisterAllScriptCommands() {
        try {
            for (String scriptName : new ArrayList<>(scriptCommands.keySet())) {
                unregisterCommands(scriptName);
            }
            scriptCommands.clear();
        } catch (Exception e) {
            Logger.log(Level.SEVERE, "Failed to unregister all script commands: " + e.getMessage(), pluginLogger.ORANGE);
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

        invokeScriptCleanup(scriptName);
        taskApi.clearListeners(scriptName);
        InternalSystems.unregisterListenersFromScript(scriptName);
        unregisterCommands(scriptName);
        unregisterTasksFromScript(scriptName);
        sharedClass.DiskStorageApi.saveCaches(scriptName); // ASYNC ?=> yields

        Future<?> future = scriptFutures.remove(scriptName);
        if (future != null) {
            future.cancel(true);
        }

        ScriptEngine engine = scriptEngines.remove(scriptName);
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

            engine = null;
            System.gc(); // I am not even sure if that will help
            Logger.scriptlog(Level.INFO, scriptName, "has been unloaded!", pluginLogger.LIGHT_BLUE);
        }

        if (plugin.isEnabled()) {
            FoliaSupport.runTaskSynchronously(plugin, () -> plugin.getServer().getPluginManager().callEvent(new ScriptUnloadedEvent(scriptName)));
        }
    }

    //TODO: deprecate this
    public String preprocessScript(File scriptFile, ScriptEngine scriptEngine) throws IOException {
        //TODO: Remove in future version
        if (!configUtil.getConfigFromBuffer("UseCustomInterpreter", true)) {
            return scriptManager.readCode(scriptManager.getRelativePath(scriptFile));
        }

        StringBuilder scriptContent = new StringBuilder();
        List<String> imports = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(scriptFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("//!import ")) {
                    String importLine = line.substring(9).trim();
                    imports.add(importLine);
                }
                scriptContent.append(line).append("\n");
            }
        }

        StringBuilder finalScript = new StringBuilder();
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

        finalScript.append(scriptContent);

        // Internal exception catcher
        return """
            try {
                %s
            } catch (e) {
                _internalPluginLogger.internalException(currentScriptName, "Exception: " + e);
                if (e && e.stack)  _internalPluginLogger.internalException(currentScriptName, "Stack: " + e.stack);
            }
            """.formatted(finalScript.toString());
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
    }

    public ScriptLoadResult loadScript(File scriptFile, boolean calledFromScript) {
        if (scriptFile.isFile() && scriptFile.getName().endsWith(".js")) {
            if (calledFromScript) {
                if (!hasInit || !scriptsReady) {
                    return new ScriptLoadResult(false, "Do not manually load scripts while they are being initialized!");
                }
            } else {
                if (configUtil.getConfigFromBuffer("AllowFeatureFlags", true)) {
                    if (FlagInterpreter.hasFlag(scriptFile, "loadManually")) {
                        return new ScriptLoadResult(false, "Script file will only load manually");
                    }
                }
            }

            String RelativePath = scriptManager.getRelativePath(scriptFile);
            String ScriptName = scriptManager.getScriptName(scriptFile);

            if (scriptManager.isScriptLoading(RelativePath)) {
                return new ScriptLoadResult(false, "Script is already loading.");
            }

            scriptManager.setScriptLoading(RelativePath, true);
            unloadScriptAsync(RelativePath);
            ScriptEngine localScriptEngine = coolcostupit.openjs.modules.ScriptEngine.getEngine();
            scriptEngines.put(RelativePath, localScriptEngine);

            // Initialize the custom in-built stuff
            localScriptEngine.put("plugin", plugin);
            localScriptEngine.put("scriptManager", this); // TODO: Try to lazy-load this, loading it on every script is memory intensive
            localScriptEngine.put("scriptEngine", localScriptEngine);
            localScriptEngine.put("currentScriptName", ScriptName);
            localScriptEngine.put("log", new ScriptLogger(getLogger(), ScriptName));
            localScriptEngine.put("DiskStorage", sharedClass.DiskStorageApi);
            localScriptEngine.put("publicVarManager", PublicVarManager);
            localScriptEngine.put("_task", taskApi); // See class: JavascriptHelper
            localScriptEngine.put("_libImporter", sharedClass.LibImporterApi);
            localScriptEngine.put("_internalPluginLogger", Logger);
            localScriptEngine.put("IsFoliaServer", FoliaSupport.isFolia());

            ScriptClassObject scriptClass = new ScriptClassObject(RelativePath);

            localScriptEngine.put("script", scriptClass);
            localScriptEngine.put("Services", new ServiceLoader(localScriptEngine, ScriptName, scriptClass));
            localScriptEngine.put("_InternalModules", new InternalSystems(ScriptName, localScriptEngine, scriptClass));

            Future<?> future = executorService.submit(() -> {
                try {
                    // Developer protections and memory optimization (just a myth but freezing in-build variables should decrease memory overhead)
                    localScriptEngine.eval("""
                    const deepFreeze = function(obj) {
                        if (obj === null || typeof obj !== 'object') return obj;
                        Object.getOwnPropertyNames(obj).forEach(function(name) {
                            var prop = obj[name];
                            if (typeof prop === 'object' && prop !== null && !Object.isFrozen(prop)) {
                                deepFreeze(prop);
                            }
                        });
                        return Object.freeze(obj);
                    }
                    
                    deepFreeze(plugin);
                    deepFreeze(scriptManager);
                    deepFreeze(scriptEngine);
                    deepFreeze(log);
                    deepFreeze(DiskStorage);
                    deepFreeze(publicVarManager);
                    deepFreeze(_task);
                    deepFreeze(_libImporter);
                    
                    Object.defineProperty(this, 'currentScriptName', {
                      value: currentScriptName,
                      writable: false,
                      configurable: false,
                      enumerable: true
                    });
                    
                    Object.defineProperty(this, 'IsFoliaServer', {
                      value: IsFoliaServer,
                      writable: false,
                      configurable: false,
                      enumerable: true
                    });
                    """);

                    if (configUtil.getConfigFromBuffer("AllowFeatureFlags", true)) {
                        if (FlagInterpreter.hasFlag(scriptFile, "waitForInit")) {
                            localScriptEngine.eval("scriptManager.waitForInit()");
                        }
                    }

                    localScriptEngine.eval(JavascriptHelper.JAVASCRIPT_CODE);
                    List BridgesToLoad = FlagInterpreter.getFlags(scriptFile);

                    if (!BridgesToLoad.isEmpty()) {
                        BridgeLoader.loadBridges(BridgesToLoad, ScriptName, localScriptEngine);
                    }

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

    public void loadScriptAsync(File scriptFile, boolean calledFromScript) {
        Future<?> future = executorService.submit(() -> loadScript(scriptFile, false));
        try {
            future.get();
        } catch (Exception e) {
            Logger.scriptlog(Level.SEVERE, scriptManager.getScriptName(scriptFile), "Error while loading script asynchronously: " + e.getMessage(), pluginLogger.ORANGE);
        }
    }

    public void unloadScriptAsync(String scriptName) {
        Future<?> future = executorService.submit(() -> unloadScript(scriptName));
        try {
            future.get();
        } catch (Exception e) {
            Logger.scriptlog(Level.SEVERE, scriptName, "Error while unloading script asynchronously: " + e.getMessage(), pluginLogger.ORANGE);
        }
    }

    public void loadScripts() {
        unloadAllScripts(); // simple fix, unload all scripts before loading them again, this is why I love programming

        for (Map.Entry<String, File> entry : scriptManager.getScriptCache().entrySet()) {
            File scriptFile = entry.getValue();
            if (scriptManager.isScriptEnabled(scriptFile)) {
                loadScriptAsync(scriptFile, false);
            }
        }
    }

    // In-Build script functions: (HELPERS)
    @SuppressWarnings("unused")
    public void registerCommand(String commandName, Object commandHandler, String scriptName, ScriptEngine scriptEngine, @Nullable String permission) {
        try {
            CommandMap commandMap = getCommandMap();
            Command dynamicCommand = new Command(commandName) {
                @Override
                public boolean execute(@NotNull CommandSender sender, @NotNull String label, String[] args) {
                    if (!testPermission(sender)) return true; // permission check, may be redundant
                    try {
                        ((Invocable) scriptEngine).invokeMethod(commandHandler, "onCommand", sender, args);
                    } catch (Exception e) {
                        sender.sendMessage(chatColors.RED + "An error occurred while executing the command: " + e.getMessage());
                        Logger.scriptlog(Level.SEVERE, scriptName, "Error in script command execution for " + commandName + ": " + e.getMessage(), pluginLogger.ORANGE);
                    }
                    return true;
                }

                @Override
                public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, String[] args) {
                    if (commandHandler instanceof Bindings && ((Bindings) commandHandler).containsKey("onTabComplete")) {
                        try {
                            return (List<String>) ((Invocable) scriptEngine).invokeMethod(commandHandler, "onTabComplete", sender, args);
                        } catch (Exception e) {
                            Logger.scriptlog(Level.WARNING, scriptName, "] Error during tab-completion for command " + commandName + ": " + e.getMessage(), pluginLogger.ORANGE);
                        }
                    }
                    return super.tabComplete(sender, alias, args);
                }
            };

            if (permission != null && !permission.isEmpty()) {
                dynamicCommand.setPermission(permission);
            }

            commandMap.register(plugin.getName(), dynamicCommand);
            scriptCommands.computeIfAbsent(scriptName, k -> new ArrayList<>()).add(dynamicCommand);
            invokeSyncCommands(); // Update command map for tab completion

            if (configUtil.getConfigFromBuffer("LogCustomCommandsActivity", true)) {
                Logger.log(Level.INFO, "[" + scriptName + "] Registered command: " + commandName, pluginLogger.GREEN);
            }
        } catch (Exception e) {
            Logger.scriptlog(Level.SEVERE, scriptName, "Failed to register command " + commandName + ": " + e.getMessage(), pluginLogger.RED);
        }
    }

    @SuppressWarnings("unused")
    public void waitForInit() {
        while (!scriptsReady) {
            try {
                //noinspection BusyWait
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
