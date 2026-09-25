package com.velocitypowered.proxy.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.velocitypowered.api.network.ProtocolVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class LegacyClientsConfig {

  public static final String DEFAULT_SECRET_FILE = "forwarding-legacy.secret";
  public static final int DEFAULT_PROTOCOL = ProtocolVersion.MINECRAFT_1_8.getProtocol();
  public static final Map<String, String> DEFAULT_MESSAGES = ImmutableMap.of(
      "no-lobby", "<red>The 1.8.9 lobby is not available right now. Try again in a moment.",
      "server-denied", "<red>This server is not available on 1.8.9.",
      "modern-denied", "<red>This server can only be joined with Minecraft 1.8.9.",
      "premium-only", "<red>1.8.9 is currently open to premium accounts only."
  );

  private static final Logger logger = LogManager.getLogger(LegacyClientsConfig.class);
  private static final byte[] NO_SECRET = new byte[0];
  private static final LegacyClientsConfig DISABLED = new LegacyClientsConfig(false, false);
  private static final LegacyClientsConfig INVALID = new LegacyClientsConfig(false, true);

  private final boolean enabled;
  private final boolean invalid;
  private final Set<Integer> protocols;
  private final List<String> lobbies;
  private final boolean premiumOnly;
  private final Map<String, PlayerInfoForwarding> servers;
  private final @Nullable String secretFile;
  private final byte[] bungeeGuardSecret;
  private final Messages messages;

  private LegacyClientsConfig(boolean enabled, boolean invalid) {
    this(enabled, invalid, ImmutableSet.of(), ImmutableList.of(), true, ImmutableMap.of(), null,
        NO_SECRET, Messages.parse(DEFAULT_MESSAGES::get));
  }

  private LegacyClientsConfig(boolean enabled, boolean invalid, Set<Integer> protocols,
      List<String> lobbies, boolean premiumOnly, Map<String, PlayerInfoForwarding> servers,
      @Nullable String secretFile, byte[] bungeeGuardSecret, Messages messages) {
    this.enabled = enabled;
    this.invalid = invalid;
    this.protocols = protocols;
    this.lobbies = lobbies;
    this.premiumOnly = premiumOnly;
    this.servers = servers;
    this.secretFile = secretFile;
    this.bungeeGuardSecret = bungeeGuardSecret;
    this.messages = messages;
  }

  public static LegacyClientsConfig disabled() {
    return DISABLED;
  }

  public static LegacyClientsConfig read(@Nullable Object section, Collection<String> knownServers,
      byte[] forwardingSecret) {
    if (section == null) {
      return DISABLED;
    }
    final List<String> errors = new ArrayList<>();
    LegacyClientsConfig config;
    if (section instanceof UnmodifiableConfig table) {
      try {
        config = parse(table, knownServers, forwardingSecret, errors);
      } catch (RuntimeException e) {
        errors.add("legacy-clients: the section could not be read (" + e + ")");
        config = INVALID;
      }
    } else {
      errors.add("legacy-clients must be a table: write it as [legacy-clients]");
      config = INVALID;
    }
    if (errors.isEmpty()) {
      return config;
    }
    errors.forEach(logger::error);
    return INVALID;
  }

  private static LegacyClientsConfig parse(UnmodifiableConfig section,
      Collection<String> knownServers, byte[] forwardingSecret, List<String> errors) {
    final Boolean enabled = get(section, "enabled", Boolean.class, "true or false", errors);
    if (enabled == null || !enabled) {
      return DISABLED;
    }

    final Set<Integer> protocols = new LinkedHashSet<>();
    final List<?> protocolList = get(section, "protocols", List.class,
        "a list of protocol numbers, e.g. [47]", errors);
    if (protocolList == null) {
      protocols.add(DEFAULT_PROTOCOL);
    } else if (protocolList.isEmpty()) {
      errors.add("legacy-clients.protocols is empty: list at least one protocol, e.g. [47]");
    } else {
      for (final Object entry : protocolList) {
        if (!(entry instanceof Number number)) {
          errors.add("legacy-clients.protocols: '" + entry + "' is not a protocol number");
          continue;
        }
        final ProtocolVersion version = ProtocolVersion.getProtocolVersion(number.intValue());
        if (!version.isSupported()) {
          errors.add("legacy-clients.protocols: " + number + " is not a protocol this proxy "
              + "supports");
        } else if (version.noLessThan(ProtocolVersion.MINECRAFT_1_13)) {
          errors.add("legacy-clients.protocols: " + number + " (" + version + ") is 1.13 or "
              + "newer: list only older protocols, e.g. 47 for 1.8.x");
        } else {
          protocols.add(version.getProtocol());
        }
      }
    }

    final Map<String, PlayerInfoForwarding> servers = new LinkedHashMap<>();
    boolean usesBungeeGuard = false;
    final UnmodifiableConfig serverTable = get(section, "servers", UnmodifiableConfig.class,
        "a table of server = \"bungeeguard\" or \"legacy\"", errors);
    if (serverTable != null) {
      for (final UnmodifiableConfig.Entry entry : serverTable.entrySet()) {
        final String name = entry.getKey().replace("\"", "");
        final PlayerInfoForwarding mode = entry.getValue() instanceof String value
            ? forwardingMode(value) : null;
        if (mode == null) {
          errors.add("legacy-clients.servers: '" + name + "' must be \"bungeeguard\" or "
              + "\"legacy\", not " + entry.getValue());
        }
        usesBungeeGuard |= mode == PlayerInfoForwarding.BUNGEEGUARD;
        final String known = findIgnoreCase(knownServers, name);
        if (known == null) {
          errors.add("legacy-clients.servers: '" + name + "' is not defined in [servers]");
        }
        if (mode != null && known != null) {
          servers.put(known.toLowerCase(Locale.ROOT), mode);
        }
      }
    }

    final Set<String> lobbies = new LinkedHashSet<>();
    final List<?> lobbyList = get(section, "lobbies", List.class, "a list of server names",
        errors);
    if (lobbyList == null || lobbyList.isEmpty()) {
      if (lobbyList != null || !section.contains("lobbies")) {
        errors.add("legacy-clients.lobbies is empty: list the servers admitted clients join "
            + "first");
      }
    } else {
      for (final Object entry : lobbyList) {
        final String name = String.valueOf(entry);
        final String known = findIgnoreCase(knownServers, name);
        if (!(entry instanceof String) || known == null
            || !servers.containsKey(known.toLowerCase(Locale.ROOT))) {
          errors.add("legacy-clients.lobbies: '" + name + "' must also be listed in "
              + "[legacy-clients.servers]");
        } else {
          lobbies.add(known);
        }
      }
    }

    final Boolean premiumOnly = get(section, "premium-only", Boolean.class, "true or false",
        errors);

    final String secretFile = get(section, "bungeeguard-secret-file", String.class, "a file name",
        errors);
    byte[] secret = NO_SECRET;
    if (usesBungeeGuard) {
      if (secretFile == null || secretFile.isBlank()) {
        errors.add("legacy-clients.bungeeguard-secret-file is required when a server uses "
            + "\"bungeeguard\"");
      } else {
        secret = readSecret(Path.of(secretFile), forwardingSecret, errors);
      }
    }

    final UnmodifiableConfig messageTable = get(section, "messages", UnmodifiableConfig.class,
        "a table of messages", errors);
    final Messages messages = Messages.parse(key -> {
      final String raw = messageTable == null ? null
          : get(messageTable, "messages." + key, key, String.class, "text", errors);
      return raw == null ? DEFAULT_MESSAGES.get(key) : raw;
    });

    return new LegacyClientsConfig(true, false, ImmutableSet.copyOf(protocols),
        ImmutableList.copyOf(lobbies), premiumOnly == null || premiumOnly,
        ImmutableMap.copyOf(servers), usesBungeeGuard ? secretFile : null, secret, messages);
  }

  private static byte[] readSecret(Path path, byte[] forwardingSecret, List<String> errors) {
    try {
      if (Files.notExists(path)) {
        Files.writeString(path, VelocityConfiguration.generateRandomString(64),
            StandardCharsets.UTF_8);
        logger.info("legacy-clients: created {} with a new BungeeGuard token", path);
      }
      if (!Files.isRegularFile(path)) {
        errors.add("legacy-clients.bungeeguard-secret-file: " + path + " is not a file");
        return NO_SECRET;
      }
      final String secret = String.join("", Files.readAllLines(path, StandardCharsets.UTF_8));
      final byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
      if (secret.isBlank()) {
        errors.add("legacy-clients.bungeeguard-secret-file: " + path + " is empty");
      } else if (Arrays.equals(bytes, forwardingSecret)) {
        errors.add("legacy-clients.bungeeguard-secret-file: " + path + " holds the same secret as "
            + "forwarding-secret-file, give the legacy servers a different token");
      }
      return bytes;
    } catch (IOException e) {
      errors.add("legacy-clients.bungeeguard-secret-file: cannot read or create " + path + " ("
          + e + ")");
      return NO_SECRET;
    }
  }

  private static <T> @Nullable T get(UnmodifiableConfig config, String key, Class<T> type,
      String expected, List<String> errors) {
    return get(config, key, key, type, expected, errors);
  }

  private static <T> @Nullable T get(UnmodifiableConfig config, String displayKey, String key,
      Class<T> type, String expected, List<String> errors) {
    final Object value = config.get(List.of(key));
    if (value == null) {
      return null;
    }
    if (!type.isInstance(value)) {
      errors.add("legacy-clients." + displayKey + " must be " + expected + ", not " + value);
      return null;
    }
    return type.cast(value);
  }

  private static @Nullable PlayerInfoForwarding forwardingMode(String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "bungeeguard" -> PlayerInfoForwarding.BUNGEEGUARD;
      case "legacy" -> PlayerInfoForwarding.LEGACY;
      default -> null;
    };
  }

  private static @Nullable String findIgnoreCase(Collection<String> names, String name) {
    for (final String candidate : names) {
      if (candidate.equalsIgnoreCase(name)) {
        return candidate;
      }
    }
    return null;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public boolean admits(ProtocolVersion version) {
    return enabled && protocols.contains(version.getProtocol());
  }

  public boolean isLegacyServer(String serverName) {
    return getForwardingMode(serverName) != null;
  }

  public @Nullable PlayerInfoForwarding getForwardingMode(String serverName) {
    return enabled ? servers.get(serverName.toLowerCase(Locale.ROOT)) : null;
  }

  public byte[] getBungeeGuardSecret() {
    return bungeeGuardSecret.clone();
  }

  public List<String> getLobbies() {
    return lobbies;
  }

  public boolean isPremiumOnly() {
    return premiumOnly;
  }

  public Messages getMessages() {
    return messages;
  }

  public String describe() {
    if (invalid) {
      return "legacy-clients: disabled until the legacy-clients errors above are fixed";
    }
    if (!enabled) {
      return "legacy-clients: disabled";
    }
    final String serverModes = servers.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + entry.getValue().name().toLowerCase(Locale.ROOT))
        .collect(Collectors.joining(", ", "{", "}"));
    return "legacy-clients: enabled for protocols " + protocols + ", lobbies " + lobbies
        + ", servers " + serverModes + ", premium-only " + premiumOnly
        + (secretFile == null ? "" : ", BungeeGuard token from " + secretFile);
  }

  public record Messages(Component noLobby, Component serverDenied, Component modernDenied,
      Component premiumOnly) {

    private static Messages parse(Function<String, String> source) {
      final MiniMessage miniMessage = MiniMessage.miniMessage();
      return new Messages(
          miniMessage.deserialize(source.apply("no-lobby")),
          miniMessage.deserialize(source.apply("server-denied")),
          miniMessage.deserialize(source.apply("modern-denied")),
          miniMessage.deserialize(source.apply("premium-only")));
    }
  }
}
