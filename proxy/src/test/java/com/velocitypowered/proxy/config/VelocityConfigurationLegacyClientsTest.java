package com.velocitypowered.proxy.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VelocityConfigurationLegacyClientsTest {

  private static final byte[] MODERN = "modern-secret".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LEGACY = "legacy-token".getBytes(StandardCharsets.UTF_8);

  @TempDir
  Path dir;

  private Path configFile;

  @BeforeEach
  void setUp() throws IOException {
    Files.write(dir.resolve("forwarding.secret"), MODERN);
    Files.write(dir.resolve("forwarding-legacy.secret"), LEGACY);
    configFile = dir.resolve("velocity.toml");
  }

  private String path(String file) {
    return dir.resolve(file).toString().replace('\\', '/');
  }

  private void write(String legacyClients) throws IOException {
    Files.writeString(configFile, """
        config-version = "2.9"
        bind = "127.0.0.1:25590"
        online-mode = false
        player-info-forwarding-mode = "MODERN"
        forwarding-secret-file = "%s"

        [servers]
        eu-lobby-01 = "127.0.0.1:25592"
        eu-lobby-189-01 = "127.0.0.1:25591"
        try = ["eu-lobby-01"]

        %s
        """.formatted(path("forwarding.secret"), legacyClients));
  }

  private String section(boolean enabled) {
    return """
        [legacy-clients]
        enabled = %s
        protocols = [47]
        lobbies = ["eu-lobby-189-01"]
        premium-only = false
        bungeeguard-secret-file = "%s"

        [legacy-clients.servers]
        eu-lobby-189-01 = "bungeeguard"
        """.formatted(enabled, path("forwarding-legacy.secret"));
  }

  @Test
  void listedServersGetTheirOwnForwarding() throws IOException {
    write(section(true));
    final VelocityConfiguration config = VelocityConfiguration.read(configFile);

    assertEquals(PlayerInfoForwarding.MODERN, config.getPlayerInfoForwardingMode());
    assertEquals(PlayerInfoForwarding.BUNGEEGUARD,
        config.getPlayerInfoForwardingMode("eu-lobby-189-01"));
    assertEquals(PlayerInfoForwarding.MODERN, config.getPlayerInfoForwardingMode("eu-lobby-01"));

    assertArrayEquals(MODERN, config.getForwardingSecret());
    assertArrayEquals(LEGACY, config.getForwardingSecret("eu-lobby-189-01"));
    assertArrayEquals(MODERN, config.getForwardingSecret("eu-lobby-01"));
  }

  @Test
  void everyServerUsesTheGlobalForwardingWhenDisabled() throws IOException {
    write(section(false));
    final VelocityConfiguration config = VelocityConfiguration.read(configFile);

    assertFalse(config.getLegacyClients().isEnabled());
    assertEquals(PlayerInfoForwarding.MODERN,
        config.getPlayerInfoForwardingMode("eu-lobby-189-01"));
    assertArrayEquals(MODERN, config.getForwardingSecret("eu-lobby-01"));
  }

  @Test
  void migrationAddsADisabledSectionOnce() throws IOException {
    write("");
    VelocityConfiguration.read(configFile);

    final String migrated = Files.readString(configFile);
    final CommentedConfig parsed = new TomlParser().parse(migrated);
    assertEquals("2.9", parsed.get("config-version"));
    assertEquals(false, parsed.get("legacy-clients.enabled"));
    assertEquals(List.of(47), parsed.get("legacy-clients.protocols"));
    assertEquals(List.of(), parsed.get("legacy-clients.lobbies"));
    assertEquals(true, parsed.get("legacy-clients.premium-only"));
    assertEquals("forwarding-legacy.secret", parsed.get("legacy-clients.bungeeguard-secret-file"));
    assertFalse(migrated.contains("servers = {}"));
    assertEquals(LegacyClientsConfig.DEFAULT_MESSAGES.get("no-lobby"),
        parsed.get("legacy-clients.messages.no-lobby"));
    assertFalse(Files.exists(Path.of(LegacyClientsConfig.DEFAULT_SECRET_FILE)));

    final VelocityConfiguration reread = VelocityConfiguration.read(configFile);
    assertFalse(reread.getLegacyClients().isEnabled());
    assertEquals(migrated, Files.readString(configFile));
  }

  @Test
  void migratedSectionCanBeEnabledByEditingIt() throws IOException {
    write("");
    VelocityConfiguration.read(configFile);

    final String edited = Files.readString(configFile)
        .replace("enabled = false", "enabled = true")
        .replace("lobbies = []", "lobbies = [\"eu-lobby-189-01\"]")
        .replace("\"forwarding-legacy.secret\"", "\"" + path("forwarding-legacy.secret") + "\"")
        + "\n[legacy-clients.servers]\neu-lobby-189-01 = \"bungeeguard\"\n";
    Files.writeString(configFile, edited);
    final VelocityConfiguration config = VelocityConfiguration.read(configFile);

    assertTrue(config.getLegacyClients().isEnabled());
    assertArrayEquals(LEGACY, config.getForwardingSecret("eu-lobby-189-01"));
  }

  @Test
  void migrationKeepsAnExistingSection() throws IOException {
    write(section(true));
    final VelocityConfiguration config = VelocityConfiguration.read(configFile);

    assertTrue(config.getLegacyClients().isEnabled());
    final CommentedConfig parsed = new TomlParser().parse(Files.readString(configFile));
    assertEquals(true, parsed.get("legacy-clients.enabled"));
    assertEquals(false, parsed.get("legacy-clients.premium-only"));
    assertEquals(List.of("eu-lobby-189-01"), parsed.get("legacy-clients.lobbies"));
  }
}
