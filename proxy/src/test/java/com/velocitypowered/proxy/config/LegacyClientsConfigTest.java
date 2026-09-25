package com.velocitypowered.proxy.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.velocitypowered.api.network.ProtocolVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyClientsConfigTest {

  private static final List<String> SERVERS = List.of("eu-lobby-01", "eu-lobby-189-01",
      "eu-lobby-189-02", "lifesteal");
  private static final byte[] MODERN_SECRET = "modern-secret".getBytes(StandardCharsets.UTF_8);

  @TempDir
  Path dir;

  private final CapturingAppender appender = new CapturingAppender();
  private final Logger logger = (Logger) LogManager.getLogger(LegacyClientsConfig.class);

  @BeforeEach
  void attachAppender() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.removeAppender(appender);
    appender.stop();
  }

  private String secretPath() {
    return dir.resolve("forwarding-legacy.secret").toString().replace('\\', '/');
  }

  private String example() {
    return """
        [legacy-clients]
          enabled = true
          protocols = [47]
          lobbies = ["eu-lobby-189-01"]
          premium-only = true
          bungeeguard-secret-file = "%s"

        [legacy-clients.servers]
          eu-lobby-189-01 = "bungeeguard"

        [legacy-clients.messages]
          no-lobby = "<red>The 1.8.9 lobby is not available right now. Try again in a moment."
          server-denied = "<red>This server is not available on 1.8.9."
          modern-denied = "<red>This server can only be joined with Minecraft 1.8.9."
          premium-only = "<red>1.8.9 is currently open to premium accounts only."
        """.formatted(secretPath());
  }

  private LegacyClientsConfig read(String toml) {
    final CommentedConfig parsed = new TomlParser().parse(toml);
    return LegacyClientsConfig.read(parsed.get("legacy-clients"), SERVERS, MODERN_SECRET);
  }

  private void assertRejected(String toml, String expectedError) throws InterruptedException {
    final LegacyClientsConfig config = read(toml);
    assertFalse(config.isEnabled());
    assertFalse(config.admits(ProtocolVersion.MINECRAFT_1_8));
    assertTrue(config.describe().contains("errors above"), config.describe());
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (appender.errors.stream().noneMatch(error -> error.contains(expectedError))
        && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(appender.errors.stream().anyMatch(error -> error.contains(expectedError)),
        () -> "expected an error containing '" + expectedError + "', got " + appender.errors);
  }

  @Test
  void disabledWithoutSection() {
    final LegacyClientsConfig config = LegacyClientsConfig.read(null, SERVERS, MODERN_SECRET);
    assertFalse(config.isEnabled());
    assertEquals("legacy-clients: disabled", config.describe());
  }

  @Test
  void defaultConfigurationIsDisabled() throws IOException {
    final String defaults;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream("default-velocity.toml")) {
      defaults = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    final LegacyClientsConfig config = read(defaults);
    assertFalse(config.isEnabled());
    assertFalse(config.admits(ProtocolVersion.MINECRAFT_1_8));
    assertEquals("legacy-clients: disabled", config.describe());
  }

  @Test
  void readsTheExample() throws IOException {
    final LegacyClientsConfig config = read(example());

    assertTrue(config.isEnabled(), appender.errors::toString);
    assertEquals(List.of("eu-lobby-189-01"), config.getLobbies());
    assertTrue(config.isPremiumOnly());
    assertEquals(PlayerInfoForwarding.BUNGEEGUARD, config.getForwardingMode("eu-lobby-189-01"));
    assertEquals(PlayerInfoForwarding.BUNGEEGUARD, config.getForwardingMode("EU-LOBBY-189-01"));
    assertNull(config.getForwardingMode("eu-lobby-01"));
    assertArrayEquals(Files.readString(Path.of(secretPath())).getBytes(StandardCharsets.UTF_8),
        config.getBungeeGuardSecret());
    assertEquals(
        MiniMessage.miniMessage().deserialize("<red>This server is not available on 1.8.9."),
        config.getMessages().serverDenied());
  }

  @Test
  void admitsOnlyListedProtocolsWhenEnabled() {
    final LegacyClientsConfig enabled = read(example());
    assertTrue(enabled.admits(ProtocolVersion.MINECRAFT_1_8));
    assertFalse(enabled.admits(ProtocolVersion.MINECRAFT_1_12_2));
    assertFalse(enabled.admits(ProtocolVersion.MINECRAFT_1_7_6));

    final LegacyClientsConfig disabled = read(example().replace("enabled = true",
        "enabled = false"));
    assertFalse(disabled.admits(ProtocolVersion.MINECRAFT_1_8));
    assertNull(disabled.getForwardingMode("eu-lobby-189-01"));
  }

  @Test
  void createsTheTokenFileWhenMissing() throws IOException {
    final Path secret = Path.of(secretPath());
    assertFalse(Files.exists(secret));

    final LegacyClientsConfig config = read(example());

    assertTrue(Files.isRegularFile(secret));
    final String token = Files.readString(secret);
    assertEquals(64, token.length());
    assertFalse(token.equals(new String(MODERN_SECRET, StandardCharsets.UTF_8)));
    assertArrayEquals(token.getBytes(StandardCharsets.UTF_8), config.getBungeeGuardSecret());
  }

  @Test
  void keepsAnExistingToken() throws IOException {
    Files.writeString(Path.of(secretPath()), "legacy-token\n");
    assertArrayEquals("legacy-token".getBytes(StandardCharsets.UTF_8),
        read(example()).getBungeeGuardSecret());
  }

  @Test
  void rejectsTheModernSecretAsToken() throws IOException, InterruptedException {
    Files.write(Path.of(secretPath()), MODERN_SECRET);
    assertRejected(example(), "same secret as forwarding-secret-file");
  }

  @Test
  void rejectsAnEmptyToken() throws IOException, InterruptedException {
    Files.writeString(Path.of(secretPath()), "  \n");
    assertRejected(example(), "is empty");
  }

  @Test
  void rejectsALobbyNotListedAmongTheServers() throws InterruptedException {
    assertRejected(example().replace("lobbies = [\"eu-lobby-189-01\"]",
            "lobbies = [\"eu-lobby-189-01\", \"eu-lobby-189-02\"]"),
        "'eu-lobby-189-02' must also be listed in [legacy-clients.servers]");
  }

  @Test
  void rejectsAnEmptyLobbyList() throws InterruptedException {
    assertRejected(example().replace("lobbies = [\"eu-lobby-189-01\"]", "lobbies = []"),
        "legacy-clients.lobbies is empty");
  }

  @Test
  void rejectsAnUnsupportedProtocol() throws InterruptedException {
    assertRejected(example().replace("protocols = [47]", "protocols = [47, 12345]"),
        "12345 is not a protocol this proxy supports");
  }

  @Test
  void rejectsProtocolsFrom113Up() throws InterruptedException {
    assertRejected(example().replace("protocols = [47]", "protocols = [47, 393]"),
        "393 (1.13) is 1.13 or newer");
  }

  @Test
  void rejectsAServerMissingFromServers() throws InterruptedException {
    assertRejected(example().replace("eu-lobby-189-01 = \"bungeeguard\"",
            "eu-lobby-189-01 = \"bungeeguard\"\n  eu-lobby-189-09 = \"legacy\""),
        "'eu-lobby-189-09' is not defined in [servers]");
  }

  @Test
  void rejectsAnUnknownForwardingMode() throws InterruptedException {
    assertRejected(example().replace("eu-lobby-189-01 = \"bungeeguard\"",
        "eu-lobby-189-01 = \"modern\""), "must be \"bungeeguard\" or \"legacy\"");
  }

  @Test
  void requiresTheTokenFileForBungeeGuard() throws InterruptedException {
    assertRejected(example().replace("bungeeguard-secret-file = \"" + secretPath() + "\"", ""),
        "bungeeguard-secret-file is required");
  }

  @Test
  void doesNotNeedATokenForLegacyForwarding() {
    final LegacyClientsConfig config = read(example()
        .replace("eu-lobby-189-01 = \"bungeeguard\"", "eu-lobby-189-01 = \"legacy\"")
        .replace("bungeeguard-secret-file = \"" + secretPath() + "\"", ""));
    assertTrue(config.isEnabled(), appender.errors::toString);
    assertEquals(PlayerInfoForwarding.LEGACY, config.getForwardingMode("eu-lobby-189-01"));
    assertEquals(0, config.getBungeeGuardSecret().length);
    assertFalse(Files.exists(Path.of(secretPath())));
  }

  @Test
  void rejectsWrongTypes() throws InterruptedException {
    assertRejected(example().replace("protocols = [47]", "protocols = \"47\""),
        "legacy-clients.protocols must be a list");
    appender.errors.clear();
    assertRejected(example().replace("enabled = true", "enabled = \"yes\""),
        "legacy-clients.enabled must be true or false");
    appender.errors.clear();
    assertRejected("legacy-clients = true", "legacy-clients must be a table");
  }

  private static final class CapturingAppender extends AbstractAppender {

    private final List<String> errors = new CopyOnWriteArrayList<>();

    private CapturingAppender() {
      super("legacy-clients-test", null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
      if (event.getLevel() == Level.ERROR) {
        errors.add(event.getMessage().getFormattedMessage());
      }
    }
  }
}
