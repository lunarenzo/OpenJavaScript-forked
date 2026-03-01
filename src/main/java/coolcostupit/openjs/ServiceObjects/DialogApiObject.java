package coolcostupit.openjs.ServiceObjects;

import coolcostupit.openjs.modules.sharedClass;
import coolcostupit.openjs.utility.ReflectionNames;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class DialogApiObject {

    private final Player player;
    private Component title = Component.text("Menu");
    private boolean canCloseWithEscape = true;
    private int columns = 1;
    private Object exitButton = null;
    private Consumer<Map<String, Object>> resultCallback;

    private final List<Object> bodyElements = new ArrayList<>();
    private final List<Object> inputs = new ArrayList<>();
    private final List<String> registeredInputIds = new ArrayList<>();
    private final List<Object> actionButtons = new ArrayList<>();

    private Object dialogType;

    private DialogApiObject(Player player) {
        this.player = player;
    }

    public DialogApiObject columns(int columns) {
        this.columns = columns;
        return this;
    }

    public static DialogApiObject create(Player player) {
        return new DialogApiObject(player);
    }

    // Builder methods
    public DialogApiObject title(String text) {
        this.title = Component.text(text);
        return this;
    }

    public DialogApiObject canCloseWithEscape(boolean value) {
        this.canCloseWithEscape = value;
        return this;
    }

    public DialogApiObject onResult(Consumer<Map<String, Object>> callback) {
        this.resultCallback = callback;
        return this;
    }

    // Body Elements
    public DialogApiObject bodyMessage(String text) {
        try {
            bodyElements.add(ReflectionNames.plainMessage.invoke(null, Component.text(text), 400));
        } catch (Exception e) {
            throw new RuntimeException("Failed to build body message", e);
        }
        return this;
    }

    // Inputs Elements
    public DialogApiObject textInput(String id, String label, String initial) {
        try {
            Object builder = ReflectionNames.textInputMethod.invoke(null, id, Component.text(label));
            builder = builder.getClass().getMethod("initial", String.class).invoke(builder, initial);
            builder = builder.getClass().getMethod("maxLength", int.class).invoke(builder, 200);
            builder = builder.getClass().getMethod("width", int.class).invoke(builder, 400);

            inputs.add(builder.getClass().getMethod("build").invoke(builder));
            registeredInputIds.add(id);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build text input: " + id, e);
        }
        return this;
    }

    public DialogApiObject rangeInput(String id, String label, double initial, double min, double max, double step) {
        try {
            Class<?> numType = ReflectionNames.numberRangeMethod.getParameterTypes()[2];
            Object builder = ReflectionNames.numberRangeMethod.invoke(null, id, Component.text(label), castNumber(min, numType), castNumber(max, numType));

            for (Method m : builder.getClass().getMethods()) {
                if (m.getName().equals("initial") && m.getParameterCount() == 1) {
                    m.invoke(builder, castNumber(initial, m.getParameterTypes()[0]));
                } else if (m.getName().equals("step") && m.getParameterCount() == 1) {
                    m.invoke(builder, castNumber(step, m.getParameterTypes()[0]));
                }
            }

            inputs.add(builder.getClass().getMethod("build").invoke(builder));
            registeredInputIds.add(id);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build range input: " + id, e);
        }
        return this;
    }

    private Object castNumber(double value, Class<?> targetType) {
        if (targetType == double.class  || targetType == Double.class)  return value;
        if (targetType == float.class   || targetType == Float.class)   return (float) value;
        if (targetType == int.class     || targetType == Integer.class) return (int) Math.round(value);
        if (targetType == long.class    || targetType == Long.class)    return Math.round(value);
        return value;
    }

    // Buttons
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

    public DialogApiObject baseButton(String id, String label) {
        try {
            Object buttonBuilder = ReflectionNames.builderMethod.invoke(null, Component.text(label));
            attachActionToBuilder(buttonBuilder, id);
            Object btn = buttonBuilder.getClass().getMethod("build").invoke(buttonBuilder);
            actionButtons.add(btn);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build notice button", e);
        }
        return this;
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

    private void attachActionToBuilder(Object builder, String buttonId) {
        if (resultCallback == null) return;
        if (ReflectionNames.customClickMethod == null) return;

        try {
            Method customClick = ReflectionNames.customClickMethod;
            Class<?> callbackType = customClick.getParameterTypes()[0];
            Object options = ReflectionNames.cachedClickOptions;
            List<String> inputIdSnapshot = List.copyOf(registeredInputIds);

            Object callbackProxy = Proxy.newProxyInstance(
                    callbackType.getClassLoader(),
                    new Class[]{callbackType},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) return null;

                        Map<String, Object> resultData = new HashMap<>();

                        if (args != null && args.length > 0) {
                            Object view = args[0];
                            try {
                                Class<?> responseViewClass = Class.forName("io.papermc.paper.dialog.DialogResponseView");
                                Method getText    = responseViewClass.getMethod("getText", String.class);
                                Method getFloat   = responseViewClass.getMethod("getFloat", String.class);
                                Method getBoolean = responseViewClass.getMethod("getBoolean", String.class);

                                for (String inputId : inputIdSnapshot) {
                                    Object value = null;
                                    try { value = getText.invoke(view, inputId); }    catch (Exception ignored) {}
                                    if (value == null) {
                                        try { value = getFloat.invoke(view, inputId); }   catch (Exception ignored) {}
                                    }
                                    if (value == null) {
                                        try { value = getBoolean.invoke(view, inputId); } catch (Exception ignored) {}
                                    }
                                    resultData.put(inputId, value);
                                }
                            } catch (Exception e) {
                                sharedClass.logger.logException(e);
                            }
                        }

                        resultData.put("__button__", buttonId);
                        resultCallback.accept(resultData);
                        return null;
                    }
            );

            Object dialogAction = customClick.invoke(null, callbackProxy, options);

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

    // Show
    public void show() {
        if (!ReflectionNames.dialogApiSupported) {
            player.sendMessage(Component.text("Dialogs are not supported on this server version."));
            return;
        }

        try {
            if (dialogType == null && !actionButtons.isEmpty()) {
                if (actionButtons.size() == 1) {
                    dialogType = ReflectionNames.notice.invoke(null, actionButtons.getFirst());
                } else {
                    List rawList = actionButtons;
                    Object builder = ReflectionNames.multiAction.invoke(null, (Object) rawList);

                    if (columns != 1) {
                        builder = ReflectionNames.multiActionColumns.invoke(builder, columns);
                    }
                    if (exitButton != null) {
                        builder = ReflectionNames.multiActionExitAction.invoke(builder, exitButton);
                    }

                    dialogType = ReflectionNames.multiActionBuild.invoke(builder);
                }
            }

            Object baseBuilder = ReflectionNames.baseBuilderMethod.invoke(null, title);
            baseBuilder = baseBuilder.getClass().getMethod("afterAction", ReflectionNames.dialogAfterActionClass).invoke(baseBuilder, ReflectionNames.dialogAfterActionNone);
            baseBuilder = baseBuilder.getClass().getMethod("pause", boolean.class).invoke(baseBuilder, false);
            baseBuilder = baseBuilder.getClass().getMethod("canCloseWithEscape", boolean.class).invoke(baseBuilder, canCloseWithEscape);

            if (!bodyElements.isEmpty()) {
                baseBuilder = baseBuilder.getClass().getMethod("body", List.class).invoke(baseBuilder, bodyElements);
            }
            if (!inputs.isEmpty()) {
                baseBuilder = baseBuilder.getClass().getMethod("inputs", List.class).invoke(baseBuilder, inputs);
            }

            Object base = baseBuilder.getClass().getMethod("build").invoke(baseBuilder);
            Object configuratorProxy = Proxy.newProxyInstance(ReflectionNames.createFunctionalInterface.getClassLoader(),
                    new Class<?>[]{ReflectionNames.createFunctionalInterface},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) return null;
                        try {
                            Object b = args[0];
                            Object empty = b.getClass().getMethod("empty").invoke(b);

                            Method baseMethod = findMethod(empty, "base");
                            Object withBase = baseMethod.invoke(empty, base);

                            Method typeMethod = findMethod(withBase, "type");
                            return typeMethod.invoke(withBase, dialogType);
                        } catch (Throwable t) {
                            sharedClass.logger.debug("Configurator proxy threw: " + t.getClass().getName() + ": " + t.getMessage());
                            if (t.getCause() != null) {
                                sharedClass.logger.debug("  Cause: " + t.getCause().getClass().getName() + ": " + t.getCause().getMessage());
                            }
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
        } catch (Exception e) {
            sharedClass.logger.logException(e);
            player.sendMessage(Component.text("§cFailed to open dialog."));
        }
    }

    public void close() {
        try {
            for (Method m : player.getClass().getMethods()) {
                if (m.getName().equals("closeDialog") && m.getParameterCount() == 0) {
                    m.invoke(player);
                    return;
                }
            }
            // Fallback: closing inventory also dismisses dialogs
            player.closeInventory();
        } catch (Exception e) {
            sharedClass.logger.logException(e);
        }
    }

    // Utilities
    private Method findMethod(Object target, String name) {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1) return m;
        }
        throw new RuntimeException("Method not found: " + name + " on " + target.getClass().getName());
    }
}