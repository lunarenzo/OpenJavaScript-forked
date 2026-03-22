/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.ServiceObjects;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.*;
import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.modules.scriptWrapper;
import coolcostupit.openjs.modules.sharedClass;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class ProtocolLibObject {
    private final ProtocolManager manager = ProtocolLibrary.getProtocolManager();
    private final Map<Object, PacketListener> scriptListeners = new ConcurrentHashMap<>();
    private final Map<String, List<PacketListener>> TotalScriptListeners = new ConcurrentHashMap<>();

    public final ScriptEngine engine;
    public final String scriptName;
    public final pluginLogger Logger;

    public ProtocolLibObject(ScriptEngine engine, String scriptName) {
        this.engine = engine;
        this.scriptName = scriptName;
        this.Logger = sharedClass.logger;
        scriptWrapper.addToCleanupMap(scriptName, this::clearListeners);
    }

    private PacketType resolvePacketType(String path) throws Exception {
        String[] parts = path.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid PacketType format: " + path);
        }

        String protocol = parts[0];
        String side = parts[1];
        String name = parts[2];

        Class<?> holderClass = Class.forName("com.comphenix.protocol.PacketType$" + protocol + "$" + side);
        return (PacketType) holderClass.getField(name).get(null);
    }

    private PacketType[] resolvePacketTypes(List<String> paths) {
        List<PacketType> result = new ArrayList<>();
        for (String path : paths) {
            try {
                result.add(resolvePacketType(path));
            } catch (Exception e) {
                Logger.scriptlog(Level.WARNING, scriptName, "Invalid packet type: " + path, pluginLogger.RED);
            }
        }
        return result.toArray(new PacketType[0]);
    }

    public void broadcastServerPacket(PacketContainer packet, org.bukkit.entity.Player source, Number broadcastRange) throws Exception {
        manager.broadcastServerPacket(packet, source.getLocation(), broadcastRange.intValue());
    }

    public void sendServerPacket(org.bukkit.entity.Player player, PacketContainer packet) throws Exception {
        manager.sendServerPacket(player, packet);
    }

    public PacketContainer createPacket(String packetTypePath) throws Exception {
        PacketType type = resolvePacketType(packetTypePath);
        return manager.createPacket(type);
    }

    public PacketListener registerListener(String Priority, Object jsHandler, List<String> packetTypeStrings) {
        PacketType[] types = resolvePacketTypes(packetTypeStrings);
        PacketAdapter adapter = new PacketAdapter(sharedClass.plugin, ListenerPriority.valueOf(Priority), types) {
            @Override
            public void onPacketSending(PacketEvent event) {
                invokeJS(jsHandler, "onSend", event);
            }

            @Override
            public void onPacketReceiving(PacketEvent event) {
                invokeJS(jsHandler, "onReceive", event);
            }
        };

        manager.addPacketListener(adapter);
        scriptListeners.put(adapter, adapter);
        TotalScriptListeners.computeIfAbsent(this.scriptName, k -> new ArrayList<>()).add(adapter);

        return adapter;
    }

    public void unregisterListener(Object adapter) {
        PacketListener listener = scriptListeners.remove(adapter);
        if (listener != null) {
            manager.removePacketListener(listener);
        }
    }

    public void clearListeners() {
        List<PacketListener> listeners = TotalScriptListeners.remove(scriptName);
        if (listeners != null) {
            for (PacketListener listener : listeners) {
                manager.removePacketListener(listener);
                //Logger.scriptlog(Level.INFO, scriptName, "[ProtocolLib] Listener destroyed", pluginLogger.LIGHT_BLUE);
            }
        }
    }

    private void invokeJS(Object handler, String method, PacketEvent event) {
        try {
            ((Invocable) engine).invokeMethod(handler, method, event);
        } catch (Exception e) {
            Logger.scriptlog(Level.WARNING, scriptName, "[ProtocolLib] " + method + " failed: " + e.getMessage(), pluginLogger.ORANGE);
        }
    }
}