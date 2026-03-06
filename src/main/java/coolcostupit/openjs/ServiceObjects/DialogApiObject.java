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
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class DialogApiObject {
    // Fields ============================
    private static final Map<UUID, DialogApiObject> activeDialogs = new ConcurrentHashMap<>();
    private static boolean listenerRegistered = false;
    private final Player player;
    private Component title = Component.text("Menu");
    private boolean canCloseWithEscape = true;
    private int columns = 1;
    private Object exitButton = null;
    private boolean destroyed = false;
    private final List<Consumer<DialogEvent>> eventCallbacks = new CopyOnWriteArrayList<>();
    private final List<Object> bodyElements       = new ArrayList<>();
    private final Map<String, Integer> bodyIdToIndex = new LinkedHashMap<>();
    private final List<InputSpec> inputSpecs      = new ArrayList<>();
    private final Map<String, InputSpec> inputById = new LinkedHashMap<>();
    private final List<ButtonBuilder> pendingButtons = new ArrayList<>();
    private final Map<String, ButtonBuilder> buttonById = new LinkedHashMap<>();
    private final Map<String, Object> lastSnapshot = new ConcurrentHashMap<>();
    private Object dialogType = null;

    public static class DialogCloseListener implements Listener {
        @EventHandler
        public void onClose(org.bukkit.event.player.PlayerQuitEvent e) {
            // Covers logout — fire closed event and clean up
            DialogApiObject dialog = activeDialogs.remove(e.getPlayer().getUniqueId());
            if (dialog != null) dialog.fireClosedEvent();
        }
    }

    public static void registerListenerIfNeeded() {
        if (listenerRegistered) return;
        listenerRegistered = true;
        sharedClass.plugin.getServer().getPluginManager().registerEvents(new DialogCloseListener(), sharedClass.plugin);

        // Try to hook Papers PlayerDialogCloseEvent reflectively
        try {
            Class<?> eventClass = Class.forName("io.papermc.paper.event.player.PlayerDialogCloseEvent");
            sharedClass.plugin.getServer().getPluginManager().registerEvent(
                    (Class<? extends Event>) eventClass,
                    new PaperDialogCloseListener(),
                    EventPriority.NORMAL,
                    (listener, event) -> ((PaperDialogCloseListener) listener).onDialogClose(event),
                    sharedClass.plugin
            );
            sharedClass.logger.debug("Paper PlayerDialogCloseEvent hooked successfully.");
        } catch (ClassNotFoundException ignored) {
            sharedClass.logger.debug("PlayerDialogCloseEvent not found — closed events will only fire on quit.");
        } catch (Exception e) {
            sharedClass.logger.logException(e);
        }
    }


    public static class PaperDialogCloseListener implements Listener {
        @EventHandler
        public void onDialogClose(org.bukkit.event.Event e) {
            try {
                // Only handle PlayerDialogCloseEvent
                if (!e.getClass().getName().equals("io.papermc.paper.event.player.PlayerDialogCloseEvent")) return;
                Method getPlayer = e.getClass().getMethod("getPlayer");
                Player player = (Player) getPlayer.invoke(e);
                DialogApiObject dialog = activeDialogs.remove(player.getUniqueId());
                if (dialog != null) dialog.fireClosedEvent();
            } catch (Exception ignored) {}
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
                    Object builder = ReflectionNames.textInputMethod.invoke(null, id, Component.text(label));
                    builder = builder.getClass().getMethod("initial", String.class)
                            .invoke(builder, currentValue != null ? (String) currentValue : "");
                    builder = builder.getClass().getMethod("maxLength", int.class).invoke(builder, maxLength);
                    builder = builder.getClass().getMethod("width", int.class).invoke(builder, width);
                    yield builder.getClass().getMethod("build").invoke(builder);
                }
                case RANGE -> {
                    Class<?> numType = ReflectionNames.numberRangeMethod.getParameterTypes()[2];
                    Object builder = ReflectionNames.numberRangeMethod.invoke(null, id, Component.text(label),
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
                    Object builder = ReflectionNames.boolInputMethod.invoke(null, id, Component.text(label));
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
            Object buttonBuilder = ReflectionNames.builderMethod.invoke(null, Component.text(label));
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
        this.title = Component.text(text);
        return this;
    }

    public DialogApiObject setTitle(String text) {
        this.title = Component.text(text);
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
            Object body = ReflectionNames.plainMessage.invoke(null, Component.text(text), 400);
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
            bodyElements.set(index, ReflectionNames.plainMessage.invoke(null, Component.text(text), 400));
        } catch (Exception e) {
            throw new RuntimeException("Failed to update body message: " + id, e);
        }
        return this;
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
            Object yesBuilder = ReflectionNames.builderMethod.invoke(null, Component.text(yes));
            attachActionToBuilder(yesBuilder, yesId);
            Object yesBtn = yesBuilder.getClass().getMethod("build").invoke(yesBuilder);

            Object noBuilder = ReflectionNames.builderMethod.invoke(null, Component.text(no));
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

    public DialogApiObject exitButton(String id, String label) {
        try {
            Object buttonBuilder = ReflectionNames.builderMethod.invoke(null, Component.text(label));
            attachActionToBuilder(buttonBuilder, id);
            exitButton = buttonBuilder.getClass().getMethod("build").invoke(buttonBuilder);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build exit button", e);
        }
        return this;
    }

    public DialogApiObject setButtonText(String id, String text) {
        ButtonBuilder btn = buttonById.get(id);
        if (btn != null) btn.label = text;
        return this;
    }


    // Event handling ============================

    private void fireEvent(DialogEvent event) {
        for (Consumer<DialogEvent> cb : eventCallbacks) {
            try { cb.accept(event); } catch (Exception e) { sharedClass.logger.logException(e); }
        }
    }

    void fireClosedEvent() {
        fireEvent(new DialogEvent("closed", null, null, lastSnapshot));
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

        try {
            // Always rebuild buttons and dialog type on each show() so that it will update any change
            if (!pendingButtons.isEmpty()) dialogType = null;

            if (dialogType == null && !pendingButtons.isEmpty()) {
                List<Object> builtButtons = new ArrayList<>();
                for (ButtonBuilder pb : pendingButtons) builtButtons.add(pb.build());

                if (builtButtons.size() == 1) {
                    dialogType = ReflectionNames.notice.invoke(null, builtButtons.get(0));
                } else {
                    @SuppressWarnings("unchecked")
                    List rawList = builtButtons;
                    Object builder = ReflectionNames.multiAction.invoke(null, (Object) rawList);
                    if (columns != 1) builder = ReflectionNames.multiActionColumns.invoke(builder, columns);
                    if (exitButton != null) builder = ReflectionNames.multiActionExitAction.invoke(builder, exitButton);
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
        try {
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
        destroyed = true;
        close();
        eventCallbacks.clear();
        inputSpecs.clear();
        inputById.clear();
        pendingButtons.clear();
        buttonById.clear();
        bodyElements.clear();
        bodyIdToIndex.clear();
        lastSnapshot.clear();
        dialogType = null;
        exitButton = null;
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