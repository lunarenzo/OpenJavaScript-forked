package coolcostupit.openjs.utility;

public class JavascriptHelper {
    public static final String JAVASCRIPT_CODE =
            "function toArray(args) {" +
                    "    return Array.prototype.slice.call(args);" +
                    "}" +
                    "function toJavaList(data) {" +
                    "    return Java.to(data, 'java.util.List');" +
                    "}" +
                    "function addCommand(commandName, commandHandler, tabCompleter) {" +
                    "    scriptManager.registerCommand(commandName, commandHandler, currentScriptName, scriptEngine);" +
                    "}" +
                    "function LoadScript(scriptName) {" +
                    "     var result = scriptManager.loadScript(new java.io.File(plugin.getDataFolder() + '/scripts/' + scriptName), true);" +
                    "     var success = result.isSuccess();" +
                    "     var err = result.getMessage();" +
                    "     if (!success) {" +
                    "         log.error(err);" +
                    "     }" +
                    "}" +
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
                    "function loadVar(varName, defaultVar, global) {" +
                    "    return JSON.parse(variableStorage.getStoredVar(currentScriptName, varName, defaultVar, global));" +
                    "}" +
                    "function saveVar(varName, variable, global) {" +
                    "    variableStorage.setStoredVar(currentScriptName, varName, JSON.stringify(variable), global);" +
                    "}";

    private JavascriptHelper() {
        // Prevent instantiation
    }
}