/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.utility;

import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.modules.sharedClass;

public class JavascriptHelper {
    private static final String OLD_CLASS_IMPORTER = "const importClass = className => _InternalModules.importClass(className);";
    private static final String NEW_CLASS_IMPORTER = "const importClass = Java.type;";
    private static final String MAIN_JAVASCRIPT_CODE = String.format("""
                var _gc = false;
                var __unloadBinds = [];
                
                function _unloadThis() {
                  const keys = Object.keys(this);
                  for (let i = 0; i < keys.length; i++) {
                    this[keys[i]] = null;
                  }
                  _gc = true;
                };
                
                function __runUnloadBinds() {
                    for (var i = 0; i < __unloadBinds.length; i++) {
                        try {
                            __unloadBinds[i]();
                        } catch (e) {
                            if (typeof log !== "undefined" && log.error) {
                                log.error("Error in unload bind: " + e);
                            }
                        }
                    }
                    __unloadBinds = null;
                }
                
                const toArray = args => Array.prototype.slice.call(args);
                const toJavaList = data => Java.to(data, 'java.util.List');
                const requireScript = relativePath => _InternalModules.requireScript(relativePath);
                const removeCommand = commandName => _InternalModules.unregisterCommand(commandName.toLowerCase());
                const addCommand = (commandName, commandHandler, permission = "") => {
                  if (typeof permission !== "string") permission = "";
                  _InternalModules.registerCommand(commandName.toLowerCase(), commandHandler, permission);
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
                
                Array.from = function(iterable, mapFn, thisArg) {
                    var arr = [];
                    if (iterable == null) return arr;
            
                    if (typeof iterable.iterator === "function") {
                        var it = iterable.iterator();
                        while (it.hasNext()) arr.push(it.next());
                    } else if (typeof iterable.size === "function" && typeof iterable.get === "function") {
                        for (var i = 0; i < iterable.size(); i++) arr.push(iterable.get(i));
                    } else if (iterable.length !== undefined) {
                        var len = iterable.length >>> 0;
                        for (var i = 0; i < len; i++) arr.push(iterable[i]);
                    }
            
                    if (typeof mapFn === "function") {
                        return arr.map(mapFn, thisArg);
                    }
                    return arr;
                };
                
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
                const getMethods = (clazz) => {
                  const methods = clazz.getMethods();
                  for (let i = 0; i < methods.length; i++) {
                    const method = methods[i];
                    const paramTypes = method.getParameterTypes();
                    let paramStr = "";
                    for (let j = 0; j < paramTypes.length; j++) {
                      paramStr += paramTypes[j].getName();
                      if (j < paramTypes.length - 1) paramStr += ", ";
                    }
                    log.info("Method name: " + method.getName());
                    log.info("Method parameters: " + (paramStr || "none"));
                  }
                };
                const DiskApi = Object.freeze({
                  loadFile(fileName, async, global) {
                    DiskStorage.loadFile(fileName, async, __currentScriptId, global);
                  },
                  saveFile(fileName, async, global) {
                    DiskStorage.saveFile(fileName, async, __currentScriptId, global);
                  },
                  getVar(fileName, valueName, fallbackValue, global) {
                    let rawData = DiskStorage.getValue(__currentScriptId, global, fileName, valueName, fallbackValue)
                    if (rawData) {
                        return JSON.parse(rawData)
                    } else {
                        return fallbackValue
                    }
                  },
                  setVar(fileName, valueName, value, global) {
                    DiskStorage.setValue(__currentScriptId, global, fileName, valueName, JSON.stringify(value));
                  }
                });
                
                const waitForScript = function(scriptName) {
                    _task.waitForScript(scriptName);
                };
                
                const task = Object.freeze({
                  wait(seconds) {
                    const continueRunning = _task.wait(__currentScriptId, scriptEngine, parseFloat(seconds));
                    if (!continueRunning) {
                      throw new Error('%s');
                    }
                  },
                  waitForScript: waitForScript,
                  waitForPlugin(pluginName) {
                    _task.waitForPlugin(pluginName, __currentScriptId);
                  },
                  cancel(taskId) {
                    _task.cancel(__currentScriptId, taskId);
                  },
                  spawn(func) {
                    return _task.spawn(__currentScriptId, scriptEngine, { f: func });
                  },
                  main(func) {
                    return _task.main(__currentScriptId, scriptEngine, { f: func });
                  },
                  thread(func) {
                    return _task.thread(__currentScriptId, scriptEngine, { f: func });
                  },
                  entitySchedule(entity, func) {
                    return _task.entitySchedule(__currentScriptId, scriptEngine, entity, { f: func });
                  },
                  delay(delay, func) {
                    return _task.delay(__currentScriptId, scriptEngine, parseFloat(delay), { f: func });
                  },
                  repeat(delay, period, func) {
                    return _task.repeat(__currentScriptId, scriptEngine, parseFloat(delay), parseFloat(period), { f: func });
                  },
                  createListener(javaInterface, handlerObj, gcSet) {
                    let isActive = true;
                    let wrappedHandler = {};
                
                    if (gcSet) {
                      for (const key in handlerObj) {
                        const original = handlerObj[key];
                        if (typeof original === "function") {
                          wrappedHandler[key] = function() {
                            if (gcSet[key] && !isActive) return;
                            return original.apply(this, arguments);
                          };
                        } else {
                          wrappedHandler[key] = original;
                        }
                      }
                    } else {
                      wrappedHandler = handlerObj;
                      log.warn("No garbage collection instructions provided, all methods will never cleanup!");
                    }
                
                    const listener = _task.createListener(__currentScriptId, scriptEngine, javaInterface, wrappedHandler);
                
                    _task.setListenerCleanup(__currentScriptId, scriptEngine, listener, {
                      f: () => {
                        isActive = false;
                      }
                    });
                
                    return listener;
                  },
                  bindToUnload(fn) {
                    if (typeof fn !== "function") {
                        throw new TypeError("bindToUnload expects a function");
                    }
                    __unloadBinds.push(fn);
                  },
                  latch() {
                    const _latch = _task.createLatch(__currentScriptId, scriptEngine);
                    return {
                        wait() { return _latch.waitFor(); },
                        listen(fn) { _latch.listen({ f: fn }); },
                        invoke(value) { _latch.invoke(value); },
                        destroy() { _latch.destroy(); },
                        connect(fn) { _latch.connect({ f: fn }); },
                        fire(value) { return _latch.fire(value); },
                        get invoked() { return _latch.isInvoked(); }
                    };
                  },
                  threadType() {
                    return _task.getThreadType();
                  }
                });
                
                const registerEvent = function(eventClass, handler) {
                    var wrappedHandler;
                    if (typeof handler === 'function') {
                        wrappedHandler = { handleEvent: handler };
                    } else if (handler && typeof handler.handleEvent === 'function') {
                        wrappedHandler = handler;
                    } else {
                        log.error('Invalid handler: must be a function or an object with a handleEvent method.');
                    }
                    return _InternalModules.registerEvent(eventClass, wrappedHandler);
                };
                const unregisterEvent = function(Listener) {
                    _InternalModules.unregisterListenerInternal(Listener)
                };
                """, pluginLogger.yieldKill);
    public static String JAVASCRIPT_CODE = MAIN_JAVASCRIPT_CODE;
    public static void initialize() {
        if (sharedClass.configUtil.getConfigFromBuffer("UseOldClassImporter", false)) {
            JAVASCRIPT_CODE = OLD_CLASS_IMPORTER + MAIN_JAVASCRIPT_CODE;
        } else {
            JAVASCRIPT_CODE = NEW_CLASS_IMPORTER + MAIN_JAVASCRIPT_CODE;
        }
    }
}