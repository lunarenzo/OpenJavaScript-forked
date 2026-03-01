/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.utility;

import coolcostupit.openjs.ServiceObjects.DialogApiObject;
import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.modules.sharedClass;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.logging.Level;

public class DialogReflectionUtil {
    public static void showNoticeDialog(Player player, String title, String description) {
        if (!ReflectionNames.dialogApiSupported) {
            // Chat Fallback for 1.20.x
            player.sendMessage("§8§l[§6" + title + "§8§l] §f" + description);
            return;
        }

        try {
            DialogApiObject.create(player)
                    .onResult((results) -> {                          // <-- set BEFORE confirmButtons
                        try {
                            String buttonId   = (String) results.get("__button__");
                            String subject    = (String) results.get("subject");
                            String message = (String) results.get("message");
                            Object rating     = results.get("rating");

                            player.sendMessage(Component.text("You clicked: " + buttonId));
                            player.sendMessage(Component.text("Subject: " + subject));
                            player.sendMessage(Component.text("Message: " + message));
                            player.sendMessage(Component.text("Rating: " + rating));

                        } catch (Exception e) {
                            sharedClass.logger.logException(e);
                            player.sendMessage(Component.text("Failed to read dialog result."));
                        }
                    })
                    .title("Server Feedback")
                    .bodyMessage("Help us improve the server.")
                    .textInput("subject", "Subject", "")
                    .textInput("message", "Message", "")
                    .rangeInput("rating", "Rating (1-10)", 8, 1, 10, 1)
                    .columns(2)
                    .baseButton("Send", "Send Feedback")
                    .baseButton("Discard", "Discard")
                    .columns(1)
                    .exitButton("Exit", "Exit Menu")
                    .show();
        } catch (Exception e) {
            sharedClass.logger.log(Level.SEVERE, "Failed to invoke Dialog Proxy: " + e.getMessage(), pluginLogger.RED);
            player.sendMessage("§6[" + title + "] §f" + description);
        }
    }
}