/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.ScriptGlobals;

import java.util.concurrent.ConcurrentHashMap;

public class PublicVarManager {
    private final ConcurrentHashMap<String, Object> publicVars = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> waitMonitors = new ConcurrentHashMap<>();

    public void setPublicVar(String key, Object value) {
        publicVars.put(key, value);
        Object monitor = waitMonitors.get(key);
        if (monitor != null) {
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }
    }

    public Object getPublicVar(String key) throws InterruptedException {
        Object val = publicVars.get(key);
        if (val != null) return val;

        Object monitor = waitMonitors.computeIfAbsent(key, k -> new Object());
        synchronized (monitor) {
            try {
                long deadline = System.nanoTime() + 500_000_000L;
                while (!publicVars.containsKey(key)) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return null;
                    long ms = remaining / 1_000_000L;
                    int ns = (int)(remaining % 1_000_000L);
                    monitor.wait(ms, ns);
                }
                return publicVars.get(key);
            } finally {
                waitMonitors.remove(key);
            }
        }
    }
}