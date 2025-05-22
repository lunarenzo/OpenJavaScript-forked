package coolcostupit.openjs.modules;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class pApiExtension extends PlaceholderExpansion {

    public pApiExtension() {
    }

    @Override
    public @NotNull String getIdentifier() {
        return sharedClass.Identifier;
    }

    @Override
    public @NotNull String getAuthor() {
        String authors = sharedClass.PluginDescription.getAuthors().toString();
        return authors.substring(1, authors.length() - 1);
    }

    @Override
    public @NotNull String getVersion() {
        return sharedClass.PluginDescription.getVersion();
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        String prefix;
        String param;

        int underscore = params.indexOf('_');
        if (underscore == -1) {
            // No params found
            prefix = params;
            param = "";
        } else {
            prefix = params.substring(0, underscore);
            param = params.substring(underscore + 1);
        }

        return sharedClass.PlaceHolderApiJavascript.invokePrefix(prefix, player, param);
    }
}