package coolcostupit.openjs.modules;

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
            long deadline = System.nanoTime() + 500_000_000L;
            while (!publicVars.containsKey(key)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return null;
                long ms = remaining / 1_000_000L;
                int ns = (int)(remaining % 1_000_000L);
                monitor.wait(ms, ns);
            }
            waitMonitors.remove(key);
            return publicVars.get(key);
        }
    }
}