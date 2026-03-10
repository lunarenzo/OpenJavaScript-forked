/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */
package coolcostupit.openjs.Services;

import coolcostupit.openjs.ServiceManager.ScriptService;
import coolcostupit.openjs.ServiceObjects.DialogApiObject;
import coolcostupit.openjs.ServiceObjects.ScriptClassObject;
import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.modules.scriptWrapper;
import coolcostupit.openjs.modules.sharedClass;
import coolcostupit.openjs.utility.ReflectionNames;
import coolcostupit.openjs.utility.scriptUtils;
import org.bukkit.entity.Player;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class DialogApiService implements ScriptService {

    public static class DialogApiWrapper {

        private final ScriptEngine engine;
        private final String scriptName;
        private final Set<DialogApiObject> openDialogs = Collections.newSetFromMap(new ConcurrentHashMap<>());

        public DialogApiWrapper(ScriptEngine engine, String scriptName) {
            this.engine = engine;
            this.scriptName = scriptName;
        }

        public DialogApiObject createDialog(Player player) {
            DialogApiObject dialog = DialogApiObject.create(player);
            openDialogs.add(dialog);
            return dialog;
        }

        public void attachEventHandler(DialogApiObject dialog, Object jsHandler) {
            dialog.onEvent(event -> {
                try {
                    java.util.Map<String, Object> jsEvent = new java.util.LinkedHashMap<>();
                    String eventId = event.getId();
                    jsEvent.put("type",  event.getType());
                    jsEvent.put("id",    eventId);
                    jsEvent.put("value", event.getValue());
                    jsEvent.put("_snapshot", event.getSnapshot());
                    jsEvent.put("cancelled", false);
                    ((Invocable) engine).invokeMethod(jsHandler, "e", jsEvent);
                    if (eventId != null && eventId.equals(DialogApiObject.defaultExitButtonId) && !(Boolean) jsEvent.get("cancelled")) {
                        dialog.close();
                    }
                } catch (Exception e) {
                    sharedClass.logger.logScriptError(e, scriptName);
                }
            });
        }

        public void destroyDialog(DialogApiObject dialog) {
            openDialogs.remove(dialog);
            dialog.destroy();
        }

        public void removeDialog(DialogApiObject dialog) {
            openDialogs.remove(dialog);
        }

        public void cleanup() {
            for (DialogApiObject dialog : openDialogs) {
                try { dialog.close(); } catch (Exception ignored) {}
            }
            openDialogs.clear();
        }
    }

    @Override
    public Object load(String scriptName, ScriptEngine engine, ScriptClassObject scriptClass) {
        try {
            if (!ReflectionNames.dialogApiSupported) {
                sharedClass.logger.scriptlog(Level.WARNING, scriptClass.RelativePath, "Dialog API is not supported on this server version (requires Paper 1.21.6+).", pluginLogger.ORANGE);
            }

            DialogApiWrapper wrapper = new DialogApiWrapper(engine, scriptClass.RelativePath);
            engine.put("__DialogApiWrapper", wrapper);
            Object api = scriptUtils.evalJavascriptArray(engine, scriptName, """
                {
                    create: function(player) {
                        var javaDialog = __DialogApiWrapper.createDialog(player);
                        var dialog = {};
                        var buildButtonWrapper = function(javaBtnBuilder) {
                            dialog.width = function(w) {
                                javaBtnBuilder.width(w);
                                return dialog;
                            };
                            return dialog;
                        };

                        dialog.onEvent = function(handler) {
                            __DialogApiWrapper.attachEventHandler(javaDialog, { e: function(event) {
                                try {
                                    handler({
                                        type:  event.get('type'),
                                        id:    event.get('id'),
                                        value: event.get('value'),
                                        read: function(key) {
                                            return event.get(key);
                                        },
                                        get: function(id) {
                                            var snap = event.get('_snapshot');
                                            return snap ? snap.get(id) : null;
                                        },
                                        setCancelled: function() {
                                            event.replace("cancelled", true);
                                        },
                                        isCancelled: function() {
                                            return event.get("cancelled");
                                        }
                                    });
                                } catch(e) {
                                    log.error('Dialog onEvent error: ' + e);
                                }
                            }});
                            return dialog;
                        };

                        dialog.title = function(text) {
                            javaDialog.title(text);
                            return dialog;
                        };

                        dialog.columns = function(cols) {
                            javaDialog.columns(cols);
                            return dialog;
                        };

                        dialog.setTitle = function(text) {
                            javaDialog.setTitle(text);
                            return dialog;
                        };

                        dialog.canCloseWithEscape = function(value) {
                            javaDialog.canCloseWithEscape(value);
                            return dialog;
                        };

                        dialog.bodyMessage = function(id, text) {
                            javaDialog.bodyMessage(id, text);
                            return dialog;
                        };

                        dialog.setBodyMessage = function(id, text) {
                            javaDialog.setBodyMessage(id, text);
                            return dialog;
                        };

                        dialog.textInput = function(id, label, initial) {
                            javaDialog.textInput(id, label, initial || '');
                            return dialog;
                        };

                        dialog.rangeInput = function(id, label, initial, min, max, step) {
                            javaDialog.rangeInput(id, label,
                                parseFloat(initial || 0),
                                parseFloat(min || 0),
                                parseFloat(max || 1),
                                parseFloat(step || 1)
                            );
                            return dialog;
                        };

                        dialog.boolInput = function(id, label, initial) {
                            javaDialog.boolInput(id, label, !!initial);
                            return dialog;
                        };

                        dialog.item = function(id, itemStack, description) {
                            if (description) {
                                javaDialog.bodyItemDescription(id, itemStack, description);
                            } else {
                                javaDialog.bodyItem(id, itemStack);
                            }
                            return dialog;
                        };

                        dialog.setItem = function(id, itemStack, description) {
                            if (description) {
                                javaDialog.setBodyItemDescription(id, itemStack, description);
                            } else {
                                javaDialog.setBodyItem(id, itemStack);
                            }
                            return dialog;
                        };

                        dialog.baseButton = function(id, label) {
                            return buildButtonWrapper(javaDialog.baseButton(id, label));
                        };

                        dialog.urlButton = function(id, label, url) {
                            return buildButtonWrapper(javaDialog.urlButton(id, label, url));
                        };

                        dialog.confirmButtons = function(yesId, yesLabel, noId, noLabel) {
                            javaDialog.confirmButtons(yesId, yesLabel, noId, noLabel);
                            return dialog;
                        };

                        dialog.exitButton = function(label) {
                            return buildButtonWrapper(javaDialog.exitButton(label));
                        };

                        dialog.setButtonText = function(id, text) {
                            javaDialog.setButtonText(id, text);
                            return dialog;
                        };

                        dialog.get = function(id) {
                            return javaDialog.get(id);
                        };

                        dialog.show = function() {
                            javaDialog.show();
                            return dialog;
                        };

                        dialog.close = function() {
                            javaDialog.close();
                            __DialogApiWrapper.removeDialog(javaDialog);
                        };

                        dialog.destroy = function() {
                            __DialogApiWrapper.destroyDialog(javaDialog);
                        };

                        return dialog;
                    }
                }
                """);

            scriptWrapper.addToCleanupMap(scriptClass.MainRelativePath, wrapper::cleanup);
            return api;

        } catch (Exception e) {
            sharedClass.logger.scriptlog(Level.SEVERE, scriptClass.RelativePath, "Failed to load DialogApiService: " + e.getMessage(), pluginLogger.RED);
            throw new RuntimeException(e);
        }
    }
}