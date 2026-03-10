/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */
package coolcostupit.openjs.ServiceObjects;

import coolcostupit.openjs.modules.sharedClass;
import coolcostupit.openjs.utility.ReflectionNames;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class DialogApiObject {
    // Fields
    private static final Map<UUID, DialogApiObject> activeDialogs = new ConcurrentHashMap<>();
    private static DialogCloseListener activeListener = null;
    private static com.comphenix.protocol.events.PacketListener activePacketListener = null;
    private static boolean listenerRegistered = false;
    public static final String defaultExitButtonId = "exit";
    private final Player player;
    private Component title = Component.text("Menu");
    private boolean canCloseWithEscape = true;
    private int columns = 1;
    private String exitButtonId = null;
    private String exitButtonLabel = null;
    private int exitButtonWidth = 100;
    private boolean destroyed = false;
    private boolean closedEventFired = false;
    private final List<Consumer<DialogEvent>> eventCallbacks = new CopyOnWriteArrayList<>();
    private final List<Object> bodyElements       = new ArrayList<>();
    private final Map<String, Integer> bodyIdToIndex = new LinkedHashMap<>();
    private final List<InputSpec> inputSpecs      = new ArrayList<>();
    private final Map<String, InputSpec> inputById = new LinkedHashMap<>();
    private final List<ButtonBuilder> pendingButtons = new ArrayList<>();
    private final Map<String, ButtonBuilder> buttonById = new LinkedHashMap<>();
    private final Map<String, Object> lastSnapshot = new ConcurrentHashMap<>();
    private Object dialogType = null;

    public static void registerPacketListenerIfNeeded() {
        if (!ReflectionNames.protocolLibAvailable) return;
        if (activePacketListener != null) return;

        com.comphenix.protocol.PacketType.Play.Client clientSide = com.comphenix.protocol.PacketType.Play.Client.getInstance();
        com.comphenix.protocol.PacketType steerVehicle = null;
        com.comphenix.protocol.PacketType look = null;
        for (com.comphenix.protocol.PacketType pt : clientSide.values()) {
            String name = pt.name();
            if (name.equals("STEER_VEHICLE")) steerVehicle = pt;
            else if (name.equals("LOOK")) look = pt;
        }

        List<com.comphenix.protocol.PacketType> targets = new ArrayList<>();
        if (steerVehicle != null) targets.add(steerVehicle);
        if (look != null) targets.add(look);
        if (targets.isEmpty()) return;

        activePacketListener = new com.comphenix.protocol.events.PacketAdapter(
                sharedClass.plugin,
                com.comphenix.protocol.events.ListenerPriority.MONITOR,
                targets) {
            @Override
            public void onPacketReceiving(com.comphenix.protocol.events.PacketEvent event) {
                UUID uuid = event.getPlayer().getUniqueId();
                if (!activeDialogs.containsKey(uuid)) return;
                sharedClass.plugin.getServer().getScheduler().runTaskLater(
                        sharedClass.plugin, () -> {
                            DialogApiObject d = activeDialogs.remove(uuid);
                            if (d != null) { d.fireClosedEvent(); unregisterIfEmpty(); }
                        }, 1L
                );
            }
        };
        ReflectionNames.protocolManager.addPacketListener(activePacketListener);
    }

    public static void registerListenerIfNeeded() {
        if (listenerRegistered) return;
        listenerRegistered = true;
        activeListener = new DialogCloseListener();
        sharedClass.plugin.getServer().getPluginManager().registerEvents(activeListener, sharedClass.plugin);
        registerPacketListenerIfNeeded();

        /* DEBUG
        if (ReflectionNames.protocolLibAvailable) {
            // Packets to ignore — fire constantly and are useless for this
            java.util.Set<String> IGNORED = new java.util.HashSet<>(java.util.Arrays.asList(
                    "KEEP_ALIVE",
                    "CLIENT_TICK_END",
                    "POSITION",
                    "ENTITY_VELOCITY",
                    "REL_ENTITY_MOVE",
                    "REL_ENTITY_MOVE_LOOK",
                    "ENTITY_HEAD_ROTATION",
                    "ENTITY_HEAD_ROTATION",
                    "ENTITY_POSITION_SYNC",
                    "MAP_CHUNK",
                    "ENTITY_LOOK",
                    "ENTITY_STATUS",
                    "NAMED_SOUND_EFFECT",
                    "UPDATE_TIME",
                    "BLOCK_CHANGE",
                    "ENTITY_METADATA",
                    "SPAWN_ENTITY"
            ));

            com.comphenix.protocol.PacketType.Play.Client clientSide =
                    com.comphenix.protocol.PacketType.Play.Client.getInstance();
            java.util.Collection<com.comphenix.protocol.PacketType> allClientPackets = clientSide.values();
            java.util.List<com.comphenix.protocol.PacketType> validPackets = new java.util.ArrayList<>();
            for (com.comphenix.protocol.PacketType pt : allClientPackets) {
                if (pt.isSupported()) validPackets.add(pt);
            }

            ReflectionNames.protocolManager.addPacketListener(
                    new com.comphenix.protocol.events.PacketAdapter(
                            sharedClass.plugin,
                            com.comphenix.protocol.events.ListenerPriority.MONITOR,
                            validPackets) {
                        @Override
                        public void onPacketReceiving(com.comphenix.protocol.events.PacketEvent event) {
                            String name = event.getPacketType().name();
                            if (IGNORED.contains(name)) return;

                            com.comphenix.protocol.reflect.StructureModifier<Object> mods =
                                    event.getPacket().getModifier();

                            StringBuilder sb = new StringBuilder("[PKT] ").append(name).append(" | ");
                            for (int i = 0; i < mods.size(); i++) {
                                try {
                                    Object val = mods.read(i);
                                    sb.append("[").append(i).append("]=");
                                    if (val == null) {
                                        sb.append("null");
                                    } else if (val.getClass().isArray()) {
                                        sb.append(java.util.Arrays.toString((Object[]) val));
                                    } else {
                                        sb.append(val).append("(").append(val.getClass().getSimpleName()).append(")");
                                    }
                                    sb.append(" ");
                                } catch (Exception ex) {
                                    sb.append("[").append(i).append("]=ERR ");
                                }
                            }

                            sharedClass.logger.log(Level.INFO, sb.toString(), pluginLogger.ORANGE);
                        }
                    }
            );

            java.util.List<com.comphenix.protocol.PacketType> validServerPackets = new java.util.ArrayList<>();
            for (com.comphenix.protocol.PacketType pt : com.comphenix.protocol.PacketType.Play.Server.getInstance().values()) {
                if (pt.isSupported()) validServerPackets.add(pt);
            }

            ReflectionNames.protocolManager.addPacketListener(
                    new com.comphenix.protocol.events.PacketAdapter(
                            sharedClass.plugin,
                            com.comphenix.protocol.events.ListenerPriority.MONITOR,
                            validServerPackets) {
                        @Override
                        public void onPacketSending(com.comphenix.protocol.events.PacketEvent event) {
                            String name = event.getPacketType().name();
                            if (IGNORED.contains(name)) return;

                            com.comphenix.protocol.reflect.StructureModifier<Object> mods =
                                    event.getPacket().getModifier();
                            StringBuilder sb = new StringBuilder("[PKT OUT] ").append(name).append(" | ");
                            for (int i = 0; i < mods.size(); i++) {
                                try {
                                    Object val = mods.read(i);
                                    sb.append("[").append(i).append("]=");
                                    if (val == null) sb.append("null");
                                    else if (val.getClass().isArray()) sb.append(java.util.Arrays.toString((Object[]) val));
                                    else sb.append(val).append("(").append(val.getClass().getSimpleName()).append(")");
                                    sb.append(" ");
                                } catch (Exception ex) {
                                    sb.append("[").append(i).append("]=ERR ");
                                }
                            }
                            sharedClass.logger.log(Level.INFO, sb.toString(), pluginLogger.ORANGE);
                        }
                    }
            );
        }
        */
    }

    private static void unregisterIfEmpty() {
        if (!activeDialogs.isEmpty()) return;
        if (!listenerRegistered) return;
        org.bukkit.event.HandlerList.unregisterAll(activeListener);

        if (activePacketListener != null) {ReflectionNames.protocolManager.removePacketListener(activePacketListener);}
        activePacketListener = null;
        activeListener = null;
        listenerRegistered = false;
    }

    private static Component parseText(String text) {
        if (text == null) return Component.empty();
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    public static class DialogCloseListener implements Listener {
        @EventHandler
        public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
            DialogApiObject d = activeDialogs.remove(e.getPlayer().getUniqueId());
            if (d != null) { d.fireClosedEvent(); unregisterIfEmpty(); }
        }

        @EventHandler
        public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent e) {
            DialogApiObject d = activeDialogs.remove(e.getPlayer().getUniqueId());
            if (d != null) { d.fireClosedEvent(); unregisterIfEmpty(); }
        }

        @EventHandler
        public void onDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
            DialogApiObject d = activeDialogs.remove(e.getEntity().getUniqueId());
            if (d != null) { d.fireClosedEvent(); unregisterIfEmpty(); }
        }

        @EventHandler
        public void onKick(org.bukkit.event.player.PlayerKickEvent e) {
            DialogApiObject d = activeDialogs.remove(e.getPlayer().getUniqueId());
            if (d != null) { d.fireClosedEvent(); unregisterIfEmpty(); }
        }

        @EventHandler
        public void onInventoryOpen(org.bukkit.event.inventory.InventoryOpenEvent e) {
            if (e.getPlayer() instanceof Player p) {
                DialogApiObject d = activeDialogs.remove(p.getUniqueId());
                if (d != null) { d.fireClosedEvent(); unregisterIfEmpty(); }
            }
        }

        @EventHandler
        public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent e) {
            if (e.getFrom().getWorld() == e.getTo().getWorld()) {
                DialogApiObject d = activeDialogs.remove(e.getPlayer().getUniqueId());
                if (d != null) { d.fireClosedEvent(); unregisterIfEmpty(); }
            }
        }

        @EventHandler
        public void onItemSwitch(org.bukkit.event.player.PlayerItemHeldEvent e) {
            DialogApiObject d = activeDialogs.remove(e.getPlayer().getUniqueId());
            if (d != null) { d.fireClosedEvent(); unregisterIfEmpty(); }
        }

        @EventHandler
        public void onInteract(org.bukkit.event.player.PlayerInteractEvent e) {
            DialogApiObject d = activeDialogs.remove(e.getPlayer().getUniqueId());
            if (d != null) { d.fireClosedEvent(); unregisterIfEmpty(); }
        }

        @EventHandler
        public void onAttack(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
            if (e.getDamager() instanceof Player p) {
                DialogApiObject d = activeDialogs.remove(p.getUniqueId());
                if (d != null) { d.fireClosedEvent(); unregisterIfEmpty(); }
            }
        }

        @EventHandler
        public void onSneak(org.bukkit.event.player.PlayerToggleSneakEvent e) {
            DialogApiObject d = activeDialogs.remove(e.getPlayer().getUniqueId());
            if (d != null) { d.fireClosedEvent(); unregisterIfEmpty(); }
        }

        @EventHandler
        public void onSprint(org.bukkit.event.player.PlayerToggleSprintEvent e) {
            DialogApiObject d = activeDialogs.remove(e.getPlayer().getUniqueId());
            if (d != null) { d.fireClosedEvent(); unregisterIfEmpty(); }
        }
    }

    public static class DialogEvent {
        private final String type;
        private final String id;
        private final Object value;
        private final Map<String, Object> snapshot;

        DialogEvent(String type, String id, Object value, Map<String, Object> snapshot) {
            this.type     = type;
            this.id       = id;
            this.value    = value;
            this.snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(snapshot));
        }

        public String getType()                  { return type; }
        public String getId()                    { return id; }
        public Object getValue()                 { return value; }
        public Map<String, Object> getSnapshot() { return snapshot; }
        public Object get(String inputId)        { return snapshot.get(inputId); }

        @Override
        public String toString() {
            return "DialogEvent{type='" + type + "', id='" + id + "', value=" + value + "}";
        }
    }

    private static class InputSpec {
        enum Type { TEXT, RANGE, BOOL }

        final String id;
        final String label;
        final Type type;

        // text (just a guess, no way to get it reflectively)
        int maxLength = 200;
        int width = 400;

        double min = 0, max = 1, step = 1;

        Object currentValue;

        InputSpec(String id, String label, Type type, Object initialValue) {
            this.id           = id;
            this.label        = label;
            this.type         = type;
            this.currentValue = initialValue;
        }

        Object buildInput() throws Exception {
            return switch (type) {
                case TEXT -> {
                    Object builder = ReflectionNames.textInputMethod.invoke(null, id, parseText(label));
                    builder = builder.getClass().getMethod("initial", String.class)
                            .invoke(builder, currentValue != null ? (String) currentValue : "");
                    builder = builder.getClass().getMethod("maxLength", int.class).invoke(builder, maxLength);
                    builder = builder.getClass().getMethod("width", int.class).invoke(builder, width);
                    yield builder.getClass().getMethod("build").invoke(builder);
                }
                case RANGE -> {
                    Class<?> numType = ReflectionNames.numberRangeMethod.getParameterTypes()[2];
                    Object builder = ReflectionNames.numberRangeMethod.invoke(null, id, parseText(label),
                            castNumber(min, numType), castNumber(max, numType));
                    double val = currentValue instanceof Number n ? n.doubleValue() :
                            Double.parseDouble(String.valueOf(currentValue));
                    for (Method m : builder.getClass().getMethods()) {
                        if (m.getName().equals("initial") && m.getParameterCount() == 1)
                            m.invoke(builder, castNumber(val, m.getParameterTypes()[0]));
                        else if (m.getName().equals("step") && m.getParameterCount() == 1)
                            m.invoke(builder, castNumber(step, m.getParameterTypes()[0]));
                    }
                    yield builder.getClass().getMethod("build").invoke(builder);
                }
                case BOOL -> {
                    Object builder = ReflectionNames.boolInputMethod.invoke(null, id, parseText(label));
                    boolean val = currentValue instanceof Boolean b ? b : false;
                    for (Method m : builder.getClass().getMethods()) {
                        if (m.getName().equals("initial") && m.getParameterCount() == 1
                                && (m.getParameterTypes()[0] == boolean.class || m.getParameterTypes()[0] == Boolean.class)) {
                            m.invoke(builder, val);
                            break;
                        }
                    }
                    yield builder.getClass().getMethod("build").invoke(builder);
                }
            };
        }

        private static Object castNumber(double value, Class<?> targetType) {
            if (targetType == double.class  || targetType == Double.class)  return value;
            if (targetType == float.class   || targetType == Float.class)   return (float) value;
            if (targetType == int.class     || targetType == Integer.class) return (int) Math.round(value);
            if (targetType == long.class    || targetType == Long.class)    return Math.round(value);
            return value;
        }
    }



    // ButtonBuilder ============================

    public static class ButtonBuilder {
        private final DialogApiObject parent;
        final String id;
        String label;
        private int width = 100;
        Object prebuiltAction = null;

        ButtonBuilder(DialogApiObject parent, String id, String label) {
            this.parent = parent;
            this.id     = id;
            this.label  = label;
        }

        public DialogApiObject width(int width) {
            this.width = width;
            return parent;
        }

        Object build() throws Exception {
            Object buttonBuilder = ReflectionNames.builderMethod.invoke(null, parseText(label));
            buttonBuilder = ReflectionNames.actionButtonWidth.invoke(buttonBuilder, width);

            if (prebuiltAction != null) {
                for (Method m : buttonBuilder.getClass().getMethods()) {
                    if (m.getName().equals("action") && m.getParameterCount() == 1) {
                        m.setAccessible(true);
                        m.invoke(buttonBuilder, prebuiltAction);
                        break;
                    }
                }
            } else {
                parent.attachActionToBuilder(buttonBuilder, id);
            }

            return ReflectionNames.actionButtonBuild.invoke(buttonBuilder);
        }
    }

    private Object buildItemBody(org.bukkit.inventory.ItemStack item, String label, int width, int height, boolean showTooltip) throws Exception {
        Object builder = ReflectionNames.itemBodyMethod.invoke(null, item);
        if (width > 0)   builder = ReflectionNames.itemBodyWidth.invoke(builder, width);
        if (height > 0)  builder = ReflectionNames.itemBodyHeight.invoke(builder, height);
        builder = ReflectionNames.itemBodyShowTooltip.invoke(builder, showTooltip);
        if (label != null && ReflectionNames.itemBodyDescription != null) {
            Object plainBody = ReflectionNames.plainMessage.invoke(null, parseText(label), width > 0 ? width : 400);
            builder = ReflectionNames.itemBodyDescription.invoke(builder, plainBody);
        }
        return ReflectionNames.itemBodyBuild.invoke(builder);
    }

    private Object buildExitButton() throws Exception {
        if (exitButtonId == null) return null;
        Object buttonBuilder = ReflectionNames.builderMethod.invoke(null, parseText(exitButtonLabel));
        buttonBuilder = ReflectionNames.actionButtonWidth.invoke(buttonBuilder, exitButtonWidth);
        attachActionToBuilder(buttonBuilder, exitButtonId);
        return ReflectionNames.actionButtonBuild.invoke(buttonBuilder);
    }

    public static class ExitButtonBuilder {
        private final DialogApiObject parent;
        ExitButtonBuilder(DialogApiObject parent) { this.parent = parent; }

        public DialogApiObject width(int width) {
            parent.exitButtonWidth = width;
            return parent;
        }

        // Allow skipping .width() and going straight to next chain call
        public DialogApiObject done() { return parent; }
    }

    private DialogApiObject(Player player) {
        this.player = player;
    }

    public static DialogApiObject create(Player player) {
        registerListenerIfNeeded();
        return new DialogApiObject(player);
    }

    public DialogApiObject columns(int cols) {
        this.setColumns(cols);
        return this;
    }

    void setColumns(int cols) { this.columns = cols; }

    public DialogApiObject title(String text) {
        this.title = parseText(text);
        return this;
    }

    public DialogApiObject setTitle(String text) {
        this.title = parseText(text);
        return this;
    }

    public DialogApiObject canCloseWithEscape(boolean value) {
        this.canCloseWithEscape = value;
        return this;
    }

    public DialogApiObject onEvent(Consumer<DialogEvent> callback) {
        eventCallbacks.add(callback);
        return this;
    }

    public Object get(String id) {
        return lastSnapshot.get(id);
    }



    // Body ============================

    public DialogApiObject bodyMessage(String id, String text) {
        try {
            Object body = ReflectionNames.plainMessage.invoke(null, parseText(text), 400);
            bodyIdToIndex.put(id, bodyElements.size());
            bodyElements.add(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build body message: " + id, e);
        }
        return this;
    }

    public DialogApiObject setBodyMessage(String id, String text) {
        try {
            Integer index = bodyIdToIndex.get(id);
            if (index == null) throw new IllegalArgumentException("No body message with id: " + id);
            bodyElements.set(index, ReflectionNames.plainMessage.invoke(null, parseText(text), 400));
        } catch (Exception e) {
            throw new RuntimeException("Failed to update body message: " + id, e);
        }
        return this;
    }

    public DialogApiObject bodyItemMain(String id, org.bukkit.inventory.ItemStack item, String label, int width, int height, boolean showTooltip) {
        try {
            Object built = buildItemBody(item, label, width, height, showTooltip);
            bodyIdToIndex.put(id, bodyElements.size());
            bodyElements.add(built);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build body item: " + id, e);
        }
        return this;
    }

    public DialogApiObject bodyItem(String id, org.bukkit.inventory.ItemStack item) {
        return bodyItemMain(id, item, null, 0, 0, true);
    }

    public DialogApiObject bodyItemDescription(String id, org.bukkit.inventory.ItemStack item, String label) {
        return bodyItemMain(id, item, label, 0, 0, true);
    }

    public DialogApiObject setBodyItemMain(String id, org.bukkit.inventory.ItemStack item, String label, int width, int height, boolean showTooltip) {
        try {
            Integer index = bodyIdToIndex.get(id);
            if (index == null) throw new IllegalArgumentException("No body item with id: " + id);
            bodyElements.set(index, buildItemBody(item, label, width, height, showTooltip));
        } catch (Exception e) {
            throw new RuntimeException("Failed to update body item: " + id, e);
        }
        return this;
    }

    public DialogApiObject setBodyItem(String id, org.bukkit.inventory.ItemStack item) {
        return setBodyItemMain(id, item, null, 0, 0, true);
    }

    public DialogApiObject setBodyItemDescription(String id, org.bukkit.inventory.ItemStack item, String label) {
        return setBodyItemMain(id, item, label, 0, 0, true);
    }

    // Input elements stuff ============================

    public DialogApiObject textInput(String id, String label, String initial) {
        InputSpec spec = new InputSpec(id, label, InputSpec.Type.TEXT, initial);
        inputSpecs.add(spec);
        inputById.put(id, spec);
        lastSnapshot.put(id, initial);
        return this;
    }

    public DialogApiObject rangeInput(String id, String label, double initial, double min, double max, double step) {
        InputSpec spec = new InputSpec(id, label, InputSpec.Type.RANGE, initial);
        spec.min  = min;
        spec.max  = max;
        spec.step = step;
        inputSpecs.add(spec);
        inputById.put(id, spec);
        lastSnapshot.put(id, (float) initial);
        return this;
    }

    public DialogApiObject boolInput(String id, String label, boolean initial) {
        InputSpec spec = new InputSpec(id, label, InputSpec.Type.BOOL, initial);
        inputSpecs.add(spec);
        inputById.put(id, spec);
        lastSnapshot.put(id, initial);
        return this;
    }

    public DialogApiObject setBoolInput(String id, boolean value) {
        InputSpec spec = inputById.get(id);
        if (spec != null && spec.type == InputSpec.Type.BOOL) {
            spec.currentValue = value;
            lastSnapshot.put(id, value);
        }
        return this;
    }

    // Buttons ============================

    public DialogApiObject confirmButtons(String yesId, String yes, String noId, String no) {
        try {
            Object yesBuilder = ReflectionNames.builderMethod.invoke(null, parseText(yes));
            attachActionToBuilder(yesBuilder, yesId);
            Object yesBtn = yesBuilder.getClass().getMethod("build").invoke(yesBuilder);

            Object noBuilder = ReflectionNames.builderMethod.invoke(null, parseText(no));
            attachActionToBuilder(noBuilder, noId);
            Object noBtn = noBuilder.getClass().getMethod("build").invoke(noBuilder);

            dialogType = ReflectionNames.confirmation.invoke(null, yesBtn, noBtn);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build confirm buttons", e);
        }
        return this;
    }

    public ButtonBuilder baseButton(String id, String label) {
        ButtonBuilder btn = new ButtonBuilder(this, id, label);
        pendingButtons.add(btn);
        buttonById.put(id, btn);
        return btn;
    }

    public ButtonBuilder urlButton(String id, String label, String url) {
        try {
            ButtonBuilder btn = new ButtonBuilder(this, id, label);
            Object clickEvent = ReflectionNames.clickEventOpenUrl.invoke(null, url);
            btn.prebuiltAction = ReflectionNames.staticAction.invoke(null, clickEvent);
            pendingButtons.add(btn);
            buttonById.put(id, btn);
            return btn;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build url button: " + id, e);
        }
    }

    public ExitButtonBuilder exitButton(String label) {
        this.exitButtonId = defaultExitButtonId;
        this.exitButtonLabel = label;
        return new ExitButtonBuilder(this);
    }

    public DialogApiObject setButtonText(String id, String text) {
        ButtonBuilder btn = buttonById.get(id);
        if (btn != null) btn.label = text;
        return this;
    }


    // Event handling ============================

    private void fireEvent(DialogEvent event) {
        if (!destroyed) {
            for (Consumer<DialogEvent> cb : eventCallbacks) {
                try {
                    cb.accept(event);
                } catch (Exception e) {
                    sharedClass.logger.logException(e);
                }
            }
        }
    }

    void fireClosedEvent() {
        if (!closedEventFired) {
            closedEventFired = true;
            fireEvent(new DialogEvent("closed", null, null, lastSnapshot));
        }
    }


    // Action attachment ============================

    void attachActionToBuilder(Object builder, String buttonId) {
        if (ReflectionNames.customClickMethod == null) return;

        try {
            Class<?> callbackType = ReflectionNames.customClickMethod.getParameterTypes()[0];

            Object callbackProxy = Proxy.newProxyInstance(
                    callbackType.getClassLoader(),
                    new Class[]{callbackType},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) return null;

                        Map<String, Object> snapshot = new LinkedHashMap<>();

                        if (args != null && args.length > 0) {
                            Object view = args[0];
                            for (InputSpec spec : inputSpecs) {
                                try {
                                    Object value = switch (spec.type) {
                                        case RANGE -> ReflectionNames.responseGetFloat.invoke(view, spec.id);
                                        case BOOL  -> ReflectionNames.responseGetBoolean.invoke(view, spec.id);
                                        default    -> ReflectionNames.responseGetText.invoke(view, spec.id);
                                    };
                                    if (value != null) {
                                        snapshot.put(spec.id, value);
                                        spec.currentValue = value;
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                        lastSnapshot.clear();
                        lastSnapshot.putAll(snapshot);

                        // Fire button event
                        fireEvent(new DialogEvent("button", buttonId, buttonId, snapshot));

                        // Fire per-input events (necessary?)
                        for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
                            fireEvent(new DialogEvent("input", entry.getKey(), entry.getValue(), snapshot));
                        }

                        return null;
                    }
            );

            Object dialogAction = ReflectionNames.customClickMethod.invoke(
                    null, callbackProxy, ReflectionNames.cachedClickOptions);

            for (Method m : builder.getClass().getMethods()) {
                if (m.getName().equals("action") && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    m.invoke(builder, dialogAction);
                    break;
                }
            }
        } catch (Exception e) {
            sharedClass.logger.logException(e);
        }
    }



    // Show ============================

    public DialogApiObject show() {
        if (destroyed) return this;
        if (!ReflectionNames.dialogApiSupported) {
            player.sendMessage(Component.text("Dialogs are not supported on this server version."));
            return this;
        }
        if (exitButtonId == null) {
            exitButtonId = defaultExitButtonId;
            exitButtonLabel = "Exit";
            exitButtonWidth = 100;
        }
        closedEventFired = false;
        try {
            // Always rebuild buttons and dialog type on each show() so that it will update any change
            if (!pendingButtons.isEmpty()) dialogType = null;

            if (dialogType == null && !pendingButtons.isEmpty()) {
                List<Object> builtButtons = new ArrayList<>();
                for (ButtonBuilder pb : pendingButtons) builtButtons.add(pb.build());

                if (builtButtons.size() == 1) {
                    Object builtExitButton = buildExitButton();
                    if (builtExitButton != null) {
                        List rawList = builtButtons;
                        Object builder = ReflectionNames.multiAction.invoke(null, (Object) rawList);
                        builder = ReflectionNames.multiActionExitAction.invoke(builder, builtExitButton);
                        dialogType = ReflectionNames.multiActionBuild.invoke(builder);
                    } else {
                        dialogType = ReflectionNames.notice.invoke(null, builtButtons.get(0));
                    }
                } else {
                    List rawList = builtButtons;
                    Object builder = ReflectionNames.multiAction.invoke(null, (Object) rawList);
                    Object builtExitButton = buildExitButton();
                    builder = ReflectionNames.multiActionColumns.invoke(builder, columns);
                    if (builtExitButton != null) builder = ReflectionNames.multiActionExitAction.invoke(builder, builtExitButton);
                    dialogType = ReflectionNames.multiActionBuild.invoke(builder);
                }
            }

            List<Object> builtInputs = new ArrayList<>();
            for (InputSpec spec : inputSpecs) builtInputs.add(spec.buildInput());

            Object baseBuilder = ReflectionNames.baseBuilderMethod.invoke(null, title);
            baseBuilder = ReflectionNames.afterActionMethod.invoke(baseBuilder, ReflectionNames.dialogAfterActionNone);
            baseBuilder = baseBuilder.getClass().getMethod("pause", boolean.class).invoke(baseBuilder, false);
            baseBuilder = baseBuilder.getClass().getMethod("canCloseWithEscape", boolean.class).invoke(baseBuilder, canCloseWithEscape);

            if (!bodyElements.isEmpty())
                baseBuilder = baseBuilder.getClass().getMethod("body", List.class).invoke(baseBuilder, bodyElements);
            if (!builtInputs.isEmpty())
                baseBuilder = baseBuilder.getClass().getMethod("inputs", List.class).invoke(baseBuilder, builtInputs);

            Object base = baseBuilder.getClass().getMethod("build").invoke(baseBuilder);
            final Object finalDialogType = dialogType;

            Object configuratorProxy = Proxy.newProxyInstance(
                    ReflectionNames.createFunctionalInterface.getClassLoader(),
                    new Class<?>[]{ReflectionNames.createFunctionalInterface},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) return null;
                        try {
                            Object b = args[0];
                            Object empty = b.getClass().getMethod("empty").invoke(b);
                            Object withBase = findMethod(empty, "base").invoke(empty, base);
                            return findMethod(withBase, "type").invoke(withBase, finalDialogType);
                        } catch (Throwable t) {
                            sharedClass.logger.logException(t instanceof Exception ex ? ex : new RuntimeException(t));
                            throw t;
                        }
                    }
            );

            Object dialog = ReflectionNames.createMethod.invoke(null, configuratorProxy);

            for (Method m : player.getClass().getMethods()) {
                if (m.getName().equals("showDialog") && m.getParameterCount() == 1) {
                    m.invoke(player, dialog);
                    break;
                }
            }

            // Register as active so close event can find us
            activeDialogs.put(player.getUniqueId(), this);

        } catch (Exception e) {
            sharedClass.logger.logException(e);
            player.sendMessage(Component.text("§cFailed to open dialog."));
        }

        return this;
    }


    // Close / Destroy ============================

    public void close() {
        activeDialogs.remove(player.getUniqueId());
        unregisterIfEmpty();
        try {
            fireClosedEvent();
            for (Method m : player.getClass().getMethods()) {
                if (m.getName().equals("closeDialog") && m.getParameterCount() == 0) {
                    m.invoke(player);
                    return;
                }
            }
            player.closeInventory();
        } catch (Exception e) {
            sharedClass.logger.logException(e);
        }
    }


    public void destroy() {
        close();
        destroyed = true;
        eventCallbacks.clear();
        inputSpecs.clear();
        inputById.clear();
        pendingButtons.clear();
        buttonById.clear();
        bodyElements.clear();
        bodyIdToIndex.clear();
        lastSnapshot.clear();
        dialogType = null;
        exitButtonId = null;
        exitButtonLabel = null;
        exitButtonWidth = 100;
    }

    // Utilities (didn't I code that somewhere else?) ============================

    private Method findMethod(Object target, String name) {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1) return m;
        }
        throw new RuntimeException("Method not found: " + name + " on " + target.getClass().getName());
    }
}

// what a pain in the ass this was