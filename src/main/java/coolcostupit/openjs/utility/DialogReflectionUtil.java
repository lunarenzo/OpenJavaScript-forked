/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.utility;

import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.modules.sharedClass;
import io.github.projectunified.unidialog.core.dialog.Dialog;
import io.github.projectunified.unidialog.paper.PaperDialogManager;
import io.github.projectunified.unidialog.paper.opener.PaperDialogOpener;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public class DialogReflectionUtil {
    private static PaperDialogManager dialogManager;
    private static boolean available = false;

    public static void initialize(JavaPlugin plugin) {
        if (dialogManager != null) return;

        try {
            PaperDialogManager paperManager = new PaperDialogManager(plugin);
            paperManager.register();

            dialogManager = paperManager;
            available = true;

            sharedClass.logger.log(Level.INFO, "UniDialog (Paper Implementation) initialized and registered!", pluginLogger.GREEN);
        } catch (Exception e) {
            available = false;
            sharedClass.logger.log(Level.WARNING, "UniDialog failed to initialize. Ensure uni-dialog-paper is shaded. Error: " + e.getMessage(), pluginLogger.ORANGE);
        }
    }

    public static boolean isDialogApiAvailable() {
        if (dialogManager == null) {
            initialize(sharedClass.plugin);
        }
        return available;
    }

    public static void showNoticeDialog(Player player, String title, String message) {
        if (!isDialogApiAvailable()) {
            player.sendMessage("§6[" + title + "] §f" + message);
            return;
        }

        try {
            PaperDialogOpener opener = dialogManager.createNoticeDialog()
                    .title(title)
                    .body(builder -> builder.text().text(message))
                    .afterAction(Dialog.AfterAction.CLOSE) // Closes the UI when they click the button
                    .opener();

            opener.open(player.getUniqueId());

        } catch (Exception e) {
            sharedClass.logger.log(Level.SEVERE, "UniDialog failed to open: " + e.getMessage(), pluginLogger.RED);
            player.sendMessage("§6[" + title + "] §f" + message);
        }
    }
}