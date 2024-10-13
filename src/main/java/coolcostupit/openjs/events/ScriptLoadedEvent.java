package coolcostupit.openjs.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

@SuppressWarnings("all")
public class ScriptLoadedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String scriptName;

    public ScriptLoadedEvent(String scriptName) {
        this.scriptName = scriptName;
    }

    public String getScriptName() {
        return scriptName;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
