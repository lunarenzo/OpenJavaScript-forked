package coolcostupit.openjs.ServiceObjects;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.modules.FoliaSupport;
import coolcostupit.openjs.modules.sharedClass;
import coolcostupit.openjs.utility.ReflectionNames;
import coolcostupit.openjs.utility.chatColors;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class InventoryApiObject {

    private final ScriptClassObject scriptClass;
    private final ScriptEngine engine;

    private final Set<InventoryUI> inventories = ConcurrentHashMap.newKeySet();
    private final LegacyComponentSerializer hexSerializer = LegacyComponentSerializer.legacySection()
            .toBuilder()
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    // I hate doing this

    public InventoryApiObject(ScriptClassObject scriptClass, ScriptEngine engine) {
        this.scriptClass = scriptClass;
        this.engine = engine;
    }

    public static class InventoryApiListener implements Listener {

        @EventHandler
        public void onClick(InventoryClickEvent e) {
            if (!(e.getInventory().getHolder() instanceof UIHolder holder)) return;
            holder.ui.handleClick(e);
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            if (!(e.getInventory().getHolder() instanceof UIHolder holder)) return;
            holder.ui.handleDrag(e);
        }

        @EventHandler
        public void onClose(InventoryCloseEvent e) {
            if (!(e.getInventory().getHolder() instanceof UIHolder holder)) return;
            holder.ui.handleClose(e);
        }
    }

    private record UIHolder(InventoryUI ui) implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return ui.inventory;
        }
    }


    public ItemStack createItem(Map<String, Object> data) {
        String id = (String) data.getOrDefault("id", "minecraft:stone");
        int amount = ((Number) data.getOrDefault("amount", 1)).intValue();

        Material mat = Material.matchMaterial(id.replace("minecraft:", ""));
        if (mat == null) mat = Material.BARRIER;

        ItemStack item = new ItemStack(mat, amount);
        ItemMeta meta = item.getItemMeta();

        if (data.containsKey("name")) {
            meta.displayName(hexSerializer.deserialize(data.get("name").toString()));
        }

        if (data.containsKey("lore")) {
            List<?> rawLore = (List<?>) data.get("lore");
            List<Component> lore = new ArrayList<>();
            for (Object line : rawLore) {
                lore.add(hexSerializer.deserialize(line.toString()));
            }
            meta.lore(lore);
        }

        item.setItemMeta(meta);
        return item;
    }

    public ItemStack setItemLore(ItemStack item, List<String> newLore) {
        if (item == null || item.getType() == Material.AIR) return item;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        List<Component> loreComponents = new ArrayList<>();
        for (String line : newLore) {
            loreComponents.add(hexSerializer.deserialize(line));
        }

        meta.lore(loreComponents);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack setItemName(ItemStack item, String newName) {
        if (item == null || item.getType() == Material.AIR) return item;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(hexSerializer.deserialize(newName));
        item.setItemMeta(meta);
        return item;
    }

    public InventoryUI constructInventory(String type, String title) {
        InventoryUI ui = new InventoryUI(type, title);
        inventories.add(ui);
        return ui;
    }

    public void cleanup() {
        inventories.forEach(InventoryUI::destroy);
        inventories.clear();
    }

    public class InventoryUI {

        private Inventory inventory;
        private boolean titleChanged = false;
        private String title;
        private String type;
        private int size;

        private final Map<Integer, ItemStack> slots = new HashMap<>();
        private final List<Object> leftClickHandlers = new ArrayList<>();
        private final List<Object> rightClickHandlers = new ArrayList<>();
        private final List<Object> itemPlaceHandlers = new ArrayList<>();
        private final List<Object> closeHandlers = new ArrayList<>();

        InventoryUI(String type, String title) {
            setType(type);
            this.title = title;
            rebuild();
        }

        private void rebuild() {
            inventory = Bukkit.createInventory(new UIHolder(this), size, Component.text(title));
            redraw();
        }

        private void redraw() {
            inventory.clear();
            slots.forEach(inventory::setItem);
        }

        private void sendOpenScreenPacket(Player player, int windowId, Object windowType, String titleJson) {
            final WrappedChatComponent wrappedChatComponent = com.comphenix.protocol.wrappers.WrappedChatComponent.fromLegacyText(titleJson);
            PacketContainer openScreen = new PacketContainer(PacketType.Play.Server.OPEN_WINDOW);
            openScreen.getIntegers().write(0, windowId);
            openScreen.getModifier().write(1, windowType);
            openScreen.getChatComponents().write(0, wrappedChatComponent);

            try {
                ReflectionNames.protocolManager.sendServerPacket(player, openScreen);
            } catch (Exception e) {
                sharedClass.logger.logScriptError(e, scriptClass.Name);
            }
        }

        public void setTitle(String newTitle) {
            if (newTitle == null) return;
            this.title = newTitle;

            for (HumanEntity viewer : new ArrayList<>(inventory.getViewers())) {
                if (!(viewer instanceof Player player)) continue;

                try {

                    if (ReflectionNames.protocolLibAvailable) {
                        try {
                            Object nmsPlayer = ReflectionNames.getHandleMethod.invoke(player);
                            Object container = ReflectionNames.activeContainerField.get(nmsPlayer);

                            int windowId = (int) ReflectionNames.containerIdField.get(container);
                            Object nmsMenuType = ReflectionNames.getTypeMethod.invoke(container);

                            sendOpenScreenPacket(player, windowId, nmsMenuType, newTitle);
                            player.updateInventory();

                            titleChanged = true;
                            continue;
                        } catch (Exception e) {
                            sharedClass.logger.logException(e);
                        }
                    }

                    try {
                        player.getOpenInventory().setTitle(newTitle);
                        titleChanged = true;
                    } catch (NoSuchMethodError ignored) {
                        sharedClass.logger.scriptlog(Level.WARNING, scriptClass.Name, "Inventory title changing is not supported on this server version.", pluginLogger.ORANGE);
                    }

                } catch (Exception e) {
                    sharedClass.logger.scriptlog(Level.WARNING, scriptClass.Name, "Failed to change inventory title:", pluginLogger.ORANGE);
                    sharedClass.logger.logScriptError(e, scriptClass.Name);
                }
            }
        }

        public void setType(String type) {
            this.type = type;
            this.size = switch (type.toLowerCase()) {
                case "double" -> 54;
                default -> 27;
            };
        }

        public void setSlot(int slot, ItemStack item) {
            slots.put(slot, item);
            inventory.setItem(slot, item);
        }

        public ItemStack getSlot(int slot) {
            if (slot < 0 || slot >= size) return null;

            // Check the live inventory first, then fallback to our tracking map
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() == Material.AIR) {
                return slots.get(slot);
            }

            return item;
        }

        public void remove() {
            destroy();
            inventories.remove(this);
        }

        public InventoryUI copy() {
            InventoryUI copy = new InventoryUI(type, title);
            copy.size = this.size;
            copy.slots.putAll(slots);
            copy.rebuild();
            inventories.add(copy);
            return copy;
        }

        public void show(Object playerObj) {
            Player player = (Player) playerObj;

            FoliaSupport.runTaskSynchronously(sharedClass.plugin, () -> {
                player.openInventory(inventory);

                // If title was changed previously and doesn't match, fix it
                if (titleChanged) {
                    setTitle(title);
                }
            });
        }

        public void hide(Object player) {
            if (!(player instanceof Player p)) return;
            FoliaSupport.runTaskSynchronously(sharedClass.plugin, () -> {
                if (p.getOpenInventory().getTopInventory().equals(inventory))
                    p.closeInventory();
            });
        }

        public Inventory getInventory() {
            return inventory;
        }

        void handleClick(InventoryClickEvent e) {
            int slot = e.getRawSlot();

            if (slot < 0 || slot >= inventory.getSize()) return;

            Object player = e.getWhoClicked();

            if (e.isLeftClick()) {
                for (Object cb : leftClickHandlers) {
                    invoke(cb, player, slot, e);
                }
            }

            if (e.isRightClick()) {
                for (Object cb : rightClickHandlers) {
                    invoke(cb, player, slot, e);
                }
            }

            if (e.getCursor().getType() != Material.AIR) {
                for (Object cb : itemPlaceHandlers) {
                    invoke(cb, player, slot, e);
                }
            }
        }

        void handleDrag(InventoryDragEvent e) {
            e.setCancelled(true);
        }

        void handleClose(InventoryCloseEvent e) {
            Object player = e.getPlayer();
            List<Object> handlersSnapshot = new ArrayList<>(closeHandlers);

            for (Object cb : handlersSnapshot) {
                invoke(cb, player, e);
            }
        }

        private void invoke(Object cb, Object... args) {
                try {
                    ((Invocable) engine).invokeMethod(cb, "e", args);
                } catch (Exception e) {
                    sharedClass.logger.logScriptError(e, scriptClass.RelativePath);
                }
        }

        public void onLeftClickHandler(Object handler) {
            leftClickHandlers.add(handler);
        }

        public void onRightClickHandler(Object handler) {
            rightClickHandlers.add(handler);
        }

        public void onItemPlacedHandler(Object handler) {
            itemPlaceHandlers.add(handler);
        }

        public void onClosedHandler(Object handler) {
            closeHandlers.add(handler);
        }

        void destroy() {
            FoliaSupport.runTasklessSynchronously(sharedClass.plugin, () -> {
                leftClickHandlers.clear();
                rightClickHandlers.clear();
                itemPlaceHandlers.clear();
                closeHandlers.clear();

                for (HumanEntity viewer : new ArrayList<>(inventory.getViewers())) {
                    viewer.closeInventory();
                }

                inventory.clear();
                inventories.remove(this);
                inventory = null;
            });
        }
    }
}
