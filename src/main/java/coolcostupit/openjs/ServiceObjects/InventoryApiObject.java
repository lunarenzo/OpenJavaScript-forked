package coolcostupit.openjs.ServiceObjects;

import coolcostupit.openjs.modules.FoliaSupport;
import coolcostupit.openjs.modules.sharedClass;
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

public class InventoryApiObject {

    private final ScriptClassObject scriptClass;
    private final ScriptEngine engine;

    private final Set<InventoryUI> inventories = ConcurrentHashMap.newKeySet();

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

        if (data.containsKey("name"))
            meta.displayName(Component.text(chatColors.RESET + data.get("name")));

        if (data.containsKey("lore")) {
            List<?> rawLore = (List<?>) data.get("lore");
            List<Component> lore = new ArrayList<>();

            for (Object line : rawLore) {
                lore.add(Component.text(chatColors.RESET + line.toString()));
            }

            meta.lore(lore);
        }

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

        public void setTitle(String title) {
            this.title = title;
            rebuild();
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
            FoliaSupport.runTaskSynchronously(sharedClass.plugin, () -> player.openInventory(inventory));
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

            for (Object cb : closeHandlers) {
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
            FoliaSupport.runTaskSynchronously(sharedClass.plugin, () -> {
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
