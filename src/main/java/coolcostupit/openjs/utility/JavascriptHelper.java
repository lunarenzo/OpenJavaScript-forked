package coolcostupit.openjs.utility;

public class JavascriptHelper {
    private static final String MAIN_JAVASCRIPT_CODE = """
                function toArray(args) {
                    return Array.prototype.slice.call(args);
                }
                function toJavaList(data) {
                    return Java.to(data, 'java.util.List');
                }
                function addCommand(commandName, commandHandler, tabCompleter) {
                    scriptManager.registerCommand(commandName, commandHandler, currentScriptName, scriptEngine);
                }
                function LoadScript(scriptName) {
                    var result = scriptManager.loadScript(new java.io.File(plugin.getDataFolder() + '/scripts/' + scriptName), true);
                    var success = result.isSuccess();
                    var err = result.getMessage();
                    if (!success) {
                        log.error(err);
                    }
                }
                function UnloadScript(scriptName) {
                    scriptManager.unloadScript(scriptName);
                }
                function setShared(key, value) {
                    publicVarManager.setPublicVar(key, value);
                }
                function getShared(key) {
                    try {
                        return publicVarManager.getPublicVar(key);
                    } catch (e) {
                        log.warn('Failed to get public variable: ' + e.message);
                        return null;
                    }
                }
                function loadVar(varName, defaultVar, global) {
                    return JSON.parse(variableStorage.getStoredVar(currentScriptName, varName, defaultVar, global));
                }
                function saveVar(varName, variable, global) {
                    variableStorage.setStoredVar(currentScriptName, varName, JSON.stringify(variable), global);
                }
                function getMethod(Package, MethodName, ExpectedParameters) {
                    var Methods = Package.getMethods()
                    for (var i = 0; i < Methods.length; i++) {
                        var method = Methods[i];
                        if (method.getName() == MethodName) {
                            if (ExpectedParameters) {
                                var paramCount = method.getParameterCount()
                                if (paramCount == ExpectedParameters.length) {
                                    var paramTypes = method.getParameterTypes();
                                    for (var j = 0; j < paramCount; j++) {
                                        if (paramTypes[j].getName() != ExpectedParameters[j]) {
                                            return method
                                        }
                                    }
                                }
                            } else {
                                return method
                            }
                        }
                    }
                }
                """;

    public static String JAVASCRIPT_CODE = MAIN_JAVASCRIPT_CODE;

    public static void updateSource(configurationUtil configUtil) {
        JAVASCRIPT_CODE = MAIN_JAVASCRIPT_CODE +
                (configUtil.getConfigFromBuffer("LoadCustomEventsHandler", true)
                        ?
                        "function registerEvent(eventClass, handler) {" +
                        "    scriptManager.registerEvent(eventClass, handler, currentScriptName, scriptEngine);" +
                        "}"
                        : "") +
                (configUtil.getConfigFromBuffer("LoadCustomScheduler", true)
                        ?
                        "function registerSchedule(delay, period, handler, method) {" +
                        "    scriptManager.registerSchedule(delay, period, handler, scriptEngine, method, currentScriptName);" +
                        "}"
                        : "");
    }
}