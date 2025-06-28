package coolcostupit.openjs.utility;

import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.modules.sharedClass;

public class JavascriptHelper {
    private static final String MAIN_JAVASCRIPT_CODE = String.format("""
                var _gc = false;
                
                function _unloadThis() {
                  const keys = Object.keys(this);
                  for (let i = 0; i < keys.length; i++) {
                    this[keys[i]] = null;
                  }
                  _gc = true;
                };
                
                Object.defineProperty(this, "_unloadThis", {
                    writable: false,
                    configurable: false
                });
                
                const toArray = args => Array.prototype.slice.call(args);
                const toJavaList = data => Java.to(data, 'java.util.List');
                
                const addCommand = (commandName, commandHandler, permission = "") => {
                  if (typeof permission !== "string") permission = "";
                  scriptManager.registerCommand(commandName, commandHandler, currentScriptName, scriptEngine, permission);
                };
                
                const importLib = libName => {
                  return _libImporter.getLib(libName)
                };
                
                const LoadScript = scriptName => {
                  const result = scriptManager.loadScript(new java.io.File(plugin.getDataFolder() + '/scripts/' + scriptName), true);
                  const success = result.isSuccess();
                  const err = result.getMessage();
                  if (!success) {
                    log.error(err);
                  }
                };
                
                const UnloadScript = scriptName => {
                  scriptManager.unloadScript(scriptName);
                };
                
                const setShared = (key, value) => {
                  publicVarManager.setPublicVar(key, value);
                };
                
                const getShared = key => {
                  try {
                    return publicVarManager.getPublicVar(key);
                  } catch (e) {
                    log.warn('Failed to get public variable: ' + e.message);
                    return null;
                  }
                };
                
                const loadVar = (varName, defaultVar, global) =>
                  JSON.parse(variableStorage.getStoredVar(currentScriptName, varName, defaultVar, global));
                
                const saveVar = (varName, variable, global) =>
                  variableStorage.setStoredVar(currentScriptName, varName, JSON.stringify(variable), global);
                
                const getMethod = (Package, MethodName, ExpectedParameters) => {
                  const Methods = Package.getMethods();
                  for (let i = 0; i < Methods.length; i++) {
                    const method = Methods[i];
                    if (method.getName() === MethodName) {
                      if (ExpectedParameters) {
                        const paramCount = method.getParameterCount();
                        if (paramCount === ExpectedParameters.length) {
                          const paramTypes = method.getParameterTypes();
                          let matches = true;
                          for (let j = 0; j < paramCount; j++) {
                            if (paramTypes[j].getName() !== ExpectedParameters[j]) {
                              matches = false;
                              break;
                            }
                          }
                          if (matches) return method;
                        }
                      } else {
                        return method;
                      }
                    }
                  }
                  return null;
                };
                
                const DiskApi = Object.freeze({
                  loadFile(fileName, async, global) {
                    DiskStorage.loadFile(fileName, async, currentScriptName, global);
                  },
                  saveFile(fileName, async, global) {
                    DiskStorage.saveFile(fileName, async, currentScriptName, global);
                  },
                  getVar(fileName, valueName, fallbackValue, global) {
                    return JSON.parse(DiskStorage.getValue(currentScriptName, global, fileName, valueName, fallbackValue));
                  },
                  setVar(fileName, valueName, value, global) {
                    DiskStorage.setValue(currentScriptName, global, fileName, valueName, JSON.stringify(value));
                  }
                });
                
                const waitForScript = _task.waitForScript;
                
                const task = Object.freeze({
                  wait(seconds) {
                    const continueRunning = _task.wait(currentScriptName, scriptEngine, parseFloat(seconds));
                    if (!continueRunning) {
                      throw new Error('%s');
                    }
                  },
                  waitForScript: waitForScript,
                  waitForPlugin(pluginName) {
                    _task.waitForPlugin(pluginName, currentScriptName);
                  },
                  cancel(taskId) {
                    _task.cancel(currentScriptName, taskId);
                  },
                  spawn(func) {
                    return _task.spawn(currentScriptName, scriptEngine, { f: func });
                  },
                  main(func) {
                    return _task.main(currentScriptName, scriptEngine, { f: func });
                  },
                  entitySchedule(entity, func) {
                    return _task.entitySchedule(currentScriptName, scriptEngine, entity, { f: func });
                  },
                  delay(delay, func) {
                    return _task.delay(currentScriptName, scriptEngine, parseFloat(delay), { f: func });
                  },
                  repeat(delay, period, func) {
                    return _task.repeat(currentScriptName, scriptEngine, parseFloat(delay), parseFloat(period), { f: func });
                  }
                });
                """, pluginLogger.yieldKill);

    public static String JAVASCRIPT_CODE = MAIN_JAVASCRIPT_CODE;

    public static void updateSource() {
        JAVASCRIPT_CODE = MAIN_JAVASCRIPT_CODE +
                (sharedClass.configUtil.getConfigFromBuffer("LoadCustomEventsHandler", true)
                        ?
                        "function registerEvent(eventClass, handler) {" +
                        "    scriptManager.registerEvent(eventClass, handler, currentScriptName, scriptEngine);" +
                        "}"
                        : "") +
                (sharedClass.configUtil.getConfigFromBuffer("LoadCustomScheduler", true)
                        ?
                        "function registerSchedule(delay, period, handler, method) {" +
                        "    scriptManager.registerSchedule(currentScriptName, delay, period, handler, scriptEngine, method);" +
                        "}"
                        : "");
    }
}