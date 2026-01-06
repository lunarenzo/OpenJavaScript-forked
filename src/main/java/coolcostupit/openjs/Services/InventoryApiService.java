package coolcostupit.openjs.Services;

import coolcostupit.openjs.ServiceManager.ScriptService;
import coolcostupit.openjs.ServiceObjects.InventoryApiObject;
import coolcostupit.openjs.ServiceObjects.ScriptClassObject;
import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.modules.scriptWrapper;
import coolcostupit.openjs.modules.sharedClass;
import coolcostupit.openjs.utility.scriptUtils;

import javax.script.ScriptEngine;
import java.util.logging.Level;

public class InventoryApiService implements ScriptService {
    private static boolean registeredListener = false;

    @Override
    public Object load(String scriptName, ScriptEngine engine, ScriptClassObject scriptClass) {
        try {
            InventoryApiObject apiObject = new InventoryApiObject(scriptClass, engine);
            engine.put("__InventoryApiObject", apiObject);
            Object api = scriptUtils.evalJavascriptArray(engine, scriptName, """
            {
                createItem: function(data) {
                    if (data.lore) {
                        data.lore = toJavaList(data.lore);
                    }
                    return __InventoryApiObject.createItem(data);
                },
                constructInventory: function(type, title) {
                    let constructApi = function(inventoryApi) {
                        let wrapperMeta = {};
                        wrapperMeta.onLeftClick = function(handler) {
                            inventoryApi.onLeftClickHandler({ e: handler });
                        };
                        wrapperMeta.onRightClick = function(handler) {
                            inventoryApi.onRightClickHandler({ e: handler });
                        };
                        wrapperMeta.onItemPlaced = function(handler) {
                            inventoryApi.onItemPlacedHandler({ e: handler });
                        };
                        wrapperMeta.onClosed = function(handler) {
                            inventoryApi.onClosedHandler({ e: handler });
                        };
                        wrapperMeta.getInventory = function() {
                            return inventoryApi.getInventory();
                        };
                        wrapperMeta.setSize = function(size) {
                            inventoryApi.setType(size);
                        };
                        wrapperMeta.setSlot = function(slot, item) {
                            inventoryApi.setSlot(slot, item);
                        };
                        wrapperMeta.destroy = function() {
                            inventoryApi.remove();
                        };
                        wrapperMeta.show = function(player) {
                            inventoryApi.show(player);
                        };
                        wrapperMeta.hide = function(player) {
                            inventoryApi.hide(player);
                        };
                        return wrapperMeta;
                    };
                    let inventoryApiObject =  __InventoryApiObject.constructInventory(type, title);
                    let inventoryApi = constructApi(inventoryApiObject);
                    inventoryApi.copy = function() {
                        let copiedInventoryApiObject = inventoryApiObject.copy();
                        return constructApi(copiedInventoryApiObject);
                    };
                    return inventoryApi;
                }
            }
            """);

            scriptWrapper.addToCleanupMap(scriptClass.MainRelativePath, apiObject::cleanup);

            if (!registeredListener) {
                registeredListener = true;
                sharedClass.plugin.getServer().getPluginManager().registerEvents(new InventoryApiObject.InventoryApiListener(), sharedClass.plugin);
            }

            return api;
        } catch (Exception e) {
            sharedClass.logger.scriptlog(Level.SEVERE, scriptClass.RelativePath, "Failed to load InventoryApiService: " + e.getMessage(), pluginLogger.RED);
            throw new RuntimeException(e);
        }
    }
}
