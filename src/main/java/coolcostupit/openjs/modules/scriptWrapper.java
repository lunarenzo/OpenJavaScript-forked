package coolcostupit.openjs.modules;

import coolcostupit.openjs.events.ScriptLoadedEvent;
import coolcostupit.openjs.events.ScriptUnloadedEvent;
import coolcostupit.openjs.logging.ScriptLogger;
import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.utility.VariableStorage;
import coolcostupit.openjs.utility.configurationUtil;
import coolcostupit.openjs.utility.FlagInterpreter;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;

import static org.bukkit.Bukkit.*;

// this is the main stuff, but I haven't added many to no comments because I was way too focused when coding all that
public class scriptWrapper {
    private boolean scriptsReady = false;
    private boolean hasInit = false;
    private final Map<String, List<Listener>> eventListenersMap = new HashMap<>();
    private final Map<String, List<Integer>> scriptTasksMap = new HashMap<>();
    private final Map<String, Future<?>> scriptFutures = new HashMap<>();
    private final Map<String, ScriptEngine> scriptEngines = new HashMap<>();
    private final JavaPlugin plugin;
    private final File disabledScriptsFile;
    private final pluginLogger pluginLogger;
    private final PublicVarManager PublicVarManager;
    private final configurationUtil configUtil;
    private final VariableStorage variableStorage;
    public final List<String> disabledScripts = new ArrayList<>();
    public final List<String> activeFiles = new ArrayList<>();
    public final List<String> runningScripts = new ArrayList<>();
    public final ExecutorService executorService;

    public scriptWrapper(JavaPlugin plugin, configurationUtil configUtil) {
        this.plugin = plugin;
        this.pluginLogger = new pluginLogger(plugin, configUtil);
        this.PublicVarManager = new PublicVarManager();
        this.configUtil = configUtil;
        this.variableStorage = new VariableStorage(plugin);
        this.executorService = Executors.newCachedThreadPool();

        // Initialize script system on first use
        if (!hasInit) {
            hasInit = true;
            FoliaSupport.ScheduleTask(plugin, () -> scriptsReady = true, 20L);
            //plugin.getServer().getScheduler().runTaskLater(plugin, () -> scriptsReady = true, 20L);
        }

        File scriptsFolder = new File(plugin.getDataFolder(), "scripts");
        boolean scriptsFolderCreated = scriptsFolder.mkdirs(); // Check the return value

        disabledScriptsFile = new File(plugin.getDataFolder(), "disabledscripts.json");
        if (!disabledScriptsFile.exists()) {
            try {
                boolean fileCreated = disabledScriptsFile.createNewFile(); // Check the return value
                if (fileCreated) {
                    try (FileWriter writer = new FileWriter(disabledScriptsFile)) {
                        writer.write("[]");
                    }
                }
            } catch (IOException e) {
                pluginLogger.log(Level.SEVERE, "Failed to create disabledscripts.json." + e.getMessage(), coolcostupit.openjs.logging.pluginLogger.RED);
            }
        }

        if (!scriptsFolderCreated && !scriptsFolder.exists()) {
            pluginLogger.log(Level.WARNING, "Failed to create scripts folder.", coolcostupit.openjs.logging.pluginLogger.ORANGE);
        }
    }

    public boolean isJavascriptFileActive(String fileName) {
        return activeFiles.contains(fileName);
    }

    public boolean isJavascriptFileRunning(String fileName) {
        return runningScripts.contains(fileName);
    }

    public List<Listener> getEventListenersFromScript(String scriptName) {
        return eventListenersMap.getOrDefault(scriptName, null);
    }

    public void unregisterListener(Listener listener, String scriptName) {
        HandlerList.unregisterAll(listener);
        List<Listener> listeners = eventListenersMap.get(scriptName);
        if (listeners != null) {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                eventListenersMap.remove(scriptName);
            }
        }
    }

    public void unregisterListenersFromScript(String scriptName) {
        List<Listener> activeListeners = getEventListenersFromScript(scriptName);
        if (activeListeners != null) {
            List<Listener> listenersToRemove = new ArrayList<>(activeListeners);
            for (Listener listener : listenersToRemove) {
                unregisterListener(listener, scriptName);
            }
        }
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

    public void unloadAllScripts() {
        for (String scriptName : new ArrayList<>(activeFiles)) {
            unloadScript(scriptName);
        }
    }

    public void unregisterAllTasks() {
        for (List<Integer> taskIds : scriptTasksMap.values()) {
            for (int taskId : taskIds) {
                getServer().getScheduler().cancelTask(taskId);
            }
        }
        scriptTasksMap.clear();
    }

    public void loadDisabledScripts() {
        try (FileReader reader = new FileReader(disabledScriptsFile)) {
            JSONParser parser = new JSONParser();
            JSONArray jsonArray = (JSONArray) parser.parse(reader);
            for (Object obj : jsonArray) {
                disabledScripts.add((String) obj);
            }
        } catch (IOException | ParseException e) {
            pluginLogger.log(Level.SEVERE, "Failed to load disabled scripts." + e.getMessage(), coolcostupit.openjs.logging.pluginLogger.RED);
        }
    }

    public void unregisterAllListeners() {
        for (Map.Entry<String, List<Listener>> entry : eventListenersMap.entrySet()) {
            List<Listener> listeners = entry.getValue();
            for (Listener listener : listeners) {
                HandlerList.unregisterAll(listener);
            }
        }
        eventListenersMap.clear();
    }

    @SuppressWarnings("all")
    public void saveDisabledScripts() {
        try (FileWriter writer = new FileWriter(disabledScriptsFile)) {
            JSONArray jsonArray = new JSONArray();
            jsonArray.addAll(disabledScripts);
            writer.write(jsonArray.toJSONString());
        } catch (IOException e) {
            pluginLogger.log(Level.SEVERE, "Failed to save disabled scripts." + e.getMessage(), coolcostupit.openjs.logging.pluginLogger.RED);
        }
    }

    public void checkDisabledScripts() {
        File scriptsFolder = new File(plugin.getDataFolder(), "scripts");
        List<String> scriptsInFolder = new ArrayList<>();

        if (scriptsFolder.exists() && scriptsFolder.isDirectory()) {
            for (File scriptFile : Objects.requireNonNull(scriptsFolder.listFiles())) {
                if (scriptFile.isFile() && scriptFile.getName().endsWith(".js")) {
                    scriptsInFolder.add(scriptFile.getName());
                }
            }
        }

        boolean modified = false;
        Iterator<String> iterator = disabledScripts.iterator();
        while (iterator.hasNext()) {
            String scriptName = iterator.next();
            if (!scriptsInFolder.contains(scriptName)) {
                iterator.remove();
                modified = true;
                pluginLogger.log(Level.INFO, "Removed non-existent script " + scriptName + " from disabled scripts list.", coolcostupit.openjs.logging.pluginLogger.BLUE);
            }
        }

        if (modified) {
            saveDisabledScripts();
        }
    }

    public void unloadScript(String scriptName) {
        unregisterListenersFromScript(scriptName);
        unregisterTasksFromScript(scriptName);

        Future<?> future = scriptFutures.remove(scriptName);
        if (future != null) {
            future.cancel(true);
        }

        ScriptEngine engine = scriptEngines.remove(scriptName);
        runningScripts.remove(scriptName);
        if (engine != null) {
            engine.getBindings(ScriptContext.ENGINE_SCOPE).clear();
        }

        if (plugin.isEnabled()) {
            // Folia fallback
            FoliaSupport.runTaskSynchronously(plugin, () -> plugin.getServer().getPluginManager().callEvent(new ScriptUnloadedEvent(scriptName)));
            //plugin.getServer().getScheduler().runTask(plugin, () -> plugin.getServer().getPluginManager().callEvent(new ScriptUnloadedEvent(scriptName)));
        }
    }

    public String preprocessScript(File scriptFile, ScriptEngine scriptEngine) throws IOException {
        if (!configUtil.getConfigFromBuffer("UseCustomInterpreter", true)) {
            return new String(Files.readAllBytes(scriptFile.toPath()));
        }

        StringBuilder scriptContent = new StringBuilder();
        List<String> imports = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(scriptFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("//!import ")) {
                    String importLine = line.substring(9).trim();
                    imports.add(importLine);
                } else {
                    scriptContent.append(line).append("\n");
                }
            }
        }

        StringBuilder finalScript = new StringBuilder();

        for (String importStatement : imports) {
            try {
                Class<?> clazz = Class.forName(importStatement);
                String simpleName = clazz.getSimpleName();
                scriptEngine.put(simpleName, clazz);
            } catch (ClassNotFoundException e) {
                pluginLogger.log(Level.WARNING, "Class not found for import: " + importStatement, coolcostupit.openjs.logging.pluginLogger.ORANGE);
            }
        }

        finalScript.append(scriptContent);
        return finalScript.toString();
    }

    public List<String> getNotLoadedScripts() {
        File scriptsFolder = new File(plugin.getDataFolder(), "scripts");
        List<String> notLoadedScripts = new ArrayList<>();
        if (scriptsFolder.exists() && scriptsFolder.isDirectory()) {
            for (File scriptFile : Objects.requireNonNull(scriptsFolder.listFiles())) {
                if (scriptFile.isFile() && scriptFile.getName().endsWith(".js") && !activeFiles.contains(scriptFile.getName()) && !disabledScripts.contains(scriptFile.getName())) {
                    notLoadedScripts.add(scriptFile.getName());
                }
            }
        }
        return notLoadedScripts;
    }

    @SuppressWarnings("unused")
    public static class ScriptLoadResult {
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
        if (scriptFile.isFile() && scriptFile.getName().endsWith(".js") && !disabledScripts.contains(scriptFile.getName())) {
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

            unloadScript(scriptFile.getName());

            ScriptEngine localScriptEngine = coolcostupit.openjs.modules.ScriptEngine.getEngine();
            scriptEngines.put(scriptFile.getName(), localScriptEngine);

            // Initialize the custom in-built stuff
            localScriptEngine.put("plugin", plugin);
            localScriptEngine.put("scriptManager", this);
            localScriptEngine.put("scriptEngine", localScriptEngine);
            localScriptEngine.put("currentScriptName", scriptFile.getName());
            localScriptEngine.put("log", new ScriptLogger(getLogger(), scriptFile.getName()));
            localScriptEngine.put("variableStorage", variableStorage);
            localScriptEngine.put("publicVarManager", PublicVarManager);
            localScriptEngine.put("waitForScript", (Consumer<String>) this::waitForScript);

            Future<?> future = executorService.submit(() -> {
                try {
                    if (configUtil.getConfigFromBuffer("AllowFeatureFlags", true)) {
                        if (FlagInterpreter.hasFlag(scriptFile, "waitForInit")) {
                            localScriptEngine.eval("scriptManager.waitForInit()");
                        }
                    }

                    localScriptEngine.eval(
                              "function LoadScript(scriptName) {" +
                                    "     var result = scriptManager.loadScript(new java.io.File(plugin.getDataFolder() + '/scripts/' + scriptName), true);" +
                                    "     var success = result.isSuccess();" +
                                    "     var err = result.getMessage();" +
                                    "     if (!success) {" +
                                    "         log.error(err);" +
                                    "     }" +
                                    " }" +
                                    "function UnloadScript(scriptName) {" +
                                    "    scriptManager.unloadScript(scriptName);" +
                                    "}" +
                                    "function setShared(key, value) {" +
                                    "    publicVarManager.setPublicVar(key, value);" +
                                    "}" +
                                    "function getShared(key) {" +
                                    "    try {" +
                                    "        return publicVarManager.getPublicVar(key);" +
                                    "    } catch (e) {" +
                                    "        log.warn('Failed to get public variable: ' + e.message);" +
                                    "        return null;" +
                                    "    }" +
                                    "}" +
                                    "function setPublicVar(key, value) {" +  //deprecated
                                    "    log.warn('setPublicVar is deprecated, please use setShared instead!');" +
                                    "    publicVarManager.setPublicVar(key, value);" +
                                    "}" +
                                    "function getPublicVar(key) {" +  //deprecated
                                    "    log.warn('getPublicVar is deprecated, please use getShared instead!');" +
                                    "    try {" +
                                    "        return publicVarManager.getPublicVar(key);" +
                                    "    } catch (e) {" +
                                    "        log.warn('Failed to get public variable: ' + e.message);" +
                                    "        return null;" +
                                    "    }" +
                                    "}" +
                                    "function loadVar(varName, defaultVar, global) {" +
                                    "    return JSON.parse(variableStorage.getStoredVar(currentScriptName, varName, defaultVar, global));" +
                                    "}" +
                                    "function saveVar(varName, variable, global) {" +
                                    "    variableStorage.setStoredVar(currentScriptName, varName, JSON.stringify(variable), global);" +
                                    "}"+
                                    "function getStoredVar(varName, defaultVar, global) {" + //deprecated
                                    "log.warn('setPublicVar is deprecated, please use loadVar instead!');" +
                                    "    return JSON.parse(variableStorage.getStoredVar(currentScriptName, varName, defaultVar, global));" +
                                    "}" +
                                    "function setStoredVar(varName, variable, global) {" +  //deprecated
                                    "    log.warn('setStoredVar is deprecated, please use saveVar instead!');" +
                                    "    variableStorage.setStoredVar(currentScriptName, varName, JSON.stringify(variable), global);" +
                                    "}"
                    );

                    if (configUtil.getConfigFromBuffer("LoadCustomEventsHandler", true)) {
                        localScriptEngine.eval("function registerEvent(eventClass, handler) {" +
                                "    scriptManager.registerEvent(eventClass, handler, currentScriptName, scriptEngine);" +
                                "}");
                    }

                    if (configUtil.getConfigFromBuffer("LoadCustomScheduler", true)) {
                        localScriptEngine.eval("function registerSchedule(delay, period, handler, method) {" +
                                "    scriptManager.registerSchedule(delay, period, handler, scriptEngine, method, currentScriptName);" +
                                "}");
                    }

                    String processedScript = preprocessScript(scriptFile, localScriptEngine);
                    localScriptEngine.eval(processedScript);
                    if (configUtil.getConfigFromBuffer("PrintScriptActivations", true)) {
                        pluginLogger.log(Level.INFO, "Loaded the script " + scriptFile.getName(), coolcostupit.openjs.logging.pluginLogger.GREEN);
                    }
                    FoliaSupport.runTaskSynchronously(plugin, () -> //plugin.getServer().getScheduler().runTask(plugin, () ->
                            plugin.getServer().getPluginManager().callEvent(new ScriptLoadedEvent(scriptFile.getName())));
                } catch (IOException | ScriptException e) {
                    pluginLogger.log(Level.WARNING, "Failed to load script " + scriptFile.getName() + ". " + e.getMessage(), coolcostupit.openjs.logging.pluginLogger.ORANGE);
                }
            });

            if (!activeFiles.contains(scriptFile.getName())) {
                activeFiles.add(scriptFile.getName());
            }

            if (!runningScripts.contains(scriptFile.getName())) {
                runningScripts.add(scriptFile.getName());
            }

            scriptFutures.put(scriptFile.getName(), future);
            return new ScriptLoadResult(true, "Script loaded successfully.");
        }
        return new ScriptLoadResult(false, "Invalid script file.");
    }

    public void loadScripts() {
        File scriptsFolder = new File(plugin.getDataFolder(), "scripts");
        unloadAllScripts(); // simple fix, unload all scripts before loading them again, this is why I love programming
        if (scriptsFolder.exists() && scriptsFolder.isDirectory()) {
            List<Future<?>> futures = new ArrayList<>();

            for (File scriptFile : Objects.requireNonNull(scriptsFolder.listFiles())) {
                Future<?> future = executorService.submit(() -> loadScript(scriptFile, false));
                futures.add(future);
            }

            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    pluginLogger.log(Level.WARNING, "An error occurred while waiting for script loading tasks to complete: " + e.getMessage(), coolcostupit.openjs.logging.pluginLogger.ORANGE);
                }
            }
        }
    }


    // In-Build script functions: (HELPERS)
    @SuppressWarnings("unused")
    public void waitForScript(String scriptName) {
        while (!isJavascriptFileRunning(scriptName)) {
            try {
                //noinspection BusyWait
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
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

    @SuppressWarnings("all")
    public void registerSchedule(String scriptName, long delay, long period, Object handler, ScriptEngine scriptEngine, String methodName) {
        Runnable task = () -> {
            try {
                ((Invocable) scriptEngine).invokeMethod(handler, methodName);
            } catch (ScriptException | NoSuchMethodException e) {
                pluginLogger.log(Level.SEVERE, "["+scriptName+"] " + e.getMessage(), coolcostupit.openjs.logging.pluginLogger.RED);
            }
        };

        int taskId;
        if (period > 0) {
            taskId = FoliaSupport.ScheduleRepeatingTask(plugin, task, delay, period);
            //taskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, task, delay, period);
        } else {
            taskId = FoliaSupport.ScheduleTask(plugin, task, delay);
            //taskId = plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, task, delay);
        }

        scriptTasksMap.computeIfAbsent(scriptName, k -> new ArrayList<>()).add(Integer.valueOf(taskId));
    }

    @SuppressWarnings("unused")
    public void registerEvent(String eventClassName, Object handler, String scriptName, ScriptEngine scriptEngine) {
        try {
            Class<?> eventClass = Class.forName(eventClassName);
            if (Event.class.isAssignableFrom(eventClass)) {
                @SuppressWarnings("unchecked")
                Class<? extends Event> eventClassCasted = (Class<? extends Event>) eventClass;
                Listener listener = new EventListenerWrapper(scriptEngine, handler, plugin);
                getServer().getPluginManager().registerEvent(eventClassCasted, listener, EventPriority.NORMAL, (l, e) -> {
                    try {
                        ((Invocable) scriptEngine).invokeMethod(handler, "handleEvent", e);
                    } catch (ScriptException | NoSuchMethodException ex) {
                        pluginLogger.log(Level.SEVERE, "["+scriptName+"] " + ex.getMessage(), coolcostupit.openjs.logging.pluginLogger.RED);
                    }
                }, plugin);

                eventListenersMap.computeIfAbsent(scriptName, k -> new ArrayList<>()).add(listener);
            } else {
                pluginLogger.log(Level.WARNING, "Class " + eventClassName + " is not an Event.", coolcostupit.openjs.logging.pluginLogger.ORANGE);
            }
        } catch (ClassNotFoundException e) {
            pluginLogger.log(Level.WARNING, "Failed to register event " + eventClassName + ": " + e.getMessage(), coolcostupit.openjs.logging.pluginLogger.ORANGE);
        }
    }
}
