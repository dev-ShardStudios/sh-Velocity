package com.velocitypowered.proxy.config.migration;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.velocitypowered.proxy.config.LegacyClientsConfig;
import java.util.List;
import org.apache.logging.log4j.Logger;

public final class LegacyClientsMigration implements ConfigurationMigration {

  @Override
  public boolean shouldMigrate(final CommentedFileConfig config) {
    return !config.contains("legacy-clients");
  }

  @Override
  public void migrate(final CommentedFileConfig config, final Logger logger) {
    config.set("legacy-clients.enabled", false);
    config.setComment("legacy-clients.enabled",
        " Lets clients older than 1.13 join while player-info-forwarding-mode is \"modern\", and\n"
            + " keeps them on the servers listed in [legacy-clients.servers]. When false, nothing\n"
            + " below has any effect.");

    config.set("legacy-clients.protocols", List.of(LegacyClientsConfig.DEFAULT_PROTOCOL));
    config.setComment("legacy-clients.protocols",
        " Protocols below 1.13 that are admitted. 47 covers 1.8 to 1.8.9.");

    config.set("legacy-clients.lobbies", List.of());
    config.setComment("legacy-clients.lobbies",
        " Admitted clients join the least populated of these servers. Each one must also be\n"
            + " listed in [legacy-clients.servers].");

    config.set("legacy-clients.premium-only", true);
    config.setComment("legacy-clients.premium-only",
        " Only let premium (online-mode) accounts in with these clients.");

    config.set("legacy-clients.bungeeguard-secret-file", LegacyClientsConfig.DEFAULT_SECRET_FILE);
    config.setComment("legacy-clients.bungeeguard-secret-file",
        " BungeeGuard token sent to the servers set to \"bungeeguard\". It must differ from\n"
            + " forwarding-secret-file, and a random one is created if the file is missing.");

    config.set("legacy-clients.servers", config.createSubConfig());
    config.setComment("legacy-clients.servers",
        " Servers reserved to admitted clients, with their forwarding: \"bungeeguard\" or\n"
            + " \"legacy\". Example: lobby-189 = \"bungeeguard\"");

    LegacyClientsConfig.DEFAULT_MESSAGES.forEach((key, message) ->
        config.set(List.of("legacy-clients", "messages", key), message));
    config.setComment("legacy-clients.messages", " Messages for players, in MiniMessage format.");
  }
}
