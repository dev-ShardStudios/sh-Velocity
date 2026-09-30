package com.velocitypowered.proxy.connection.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.LegacyClientsConfig;
import com.velocitypowered.proxy.config.ShardClientConfig;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

class StatusShardListingTest {

  private static final String SHA = "01e4f7dab6b501ddef0b28c3d61e4243b25dcd3d28167e8e8c4f5fccbf17f942";

  private static final LegacyClientsConfig LEGACY = legacy(true);
  private static final LegacyClientsConfig NO_LEGACY = legacy(false);
  private static final ShardClientConfig SHARD = ShardClientConfig.read(new TomlParser().parse("""
      [shard-client]
      motd = "<gradient:#B29CFF:#8E7CCC>ꜱʜᴀʀᴅ</gradient> & <sprite:\\"minecraft:items\\":\\"item/diamond\\">"
      icon-url = "https://dl.shard.rip/icons/%s.png"
      icon-sha256 = "%s"
      """.formatted(SHA, SHA)).get("shard-client"));

  private static LegacyClientsConfig legacy(boolean enabled) {
    final CommentedConfig parsed = new TomlParser().parse("""
        [legacy-clients]
        enabled = %s
        protocols = [47]
        lobbies = ["lobby"]

        [legacy-clients.servers]
        lobby = "legacy"
        """.formatted(enabled));
    return LegacyClientsConfig.read(parsed.get("legacy-clients"), List.of("lobby"),
        "secret".getBytes(StandardCharsets.UTF_8));
  }

  private static String vanilla(ProtocolVersion version, String description) {
    final ServerPing ping = ServerPing.builder()
        .version(new ServerPing.Version(version.getProtocol(), "1.8.9"))
        .onlinePlayers(3)
        .maximumPlayers(5000)
        .description(Component.text(description, NamedTextColor.DARK_PURPLE))
        .build();
    final StringBuilder json = new StringBuilder();
    VelocityServer.getPingGsonInstance(version).toJson(ping, json);
    return json.toString();
  }

  private static String withListing(String json, ProtocolVersion version, ShardClientConfig shard,
      LegacyClientsConfig legacy) {
    final StringBuilder builder = new StringBuilder(json);
    StatusSessionHandler.withShardListing(builder, version, shard, legacy);
    return builder.toString();
  }

  @Test
  void vanillaPingsStayByteForByte() {
    final String legacyPing = vanilla(ProtocolVersion.MINECRAFT_1_8, "SHARD");
    assertEquals(legacyPing, withListing(legacyPing, ProtocolVersion.MINECRAFT_1_8,
        ShardClientConfig.disabled(), LEGACY));
    assertEquals(legacyPing, withListing(legacyPing, ProtocolVersion.MINECRAFT_1_8, SHARD, NO_LEGACY));
    final String modernPing = vanilla(ProtocolVersion.MAXIMUM_VERSION, "SHARD");
    assertEquals(modernPing, withListing(modernPing, ProtocolVersion.MAXIMUM_VERSION, SHARD, LEGACY));
    final String olderPing = vanilla(ProtocolVersion.MINECRAFT_1_12_2, "SHARD");
    assertEquals(olderPing, withListing(olderPing, ProtocolVersion.MINECRAFT_1_12_2, SHARD, LEGACY));
  }

  @Test
  void admittedLegacyPingsCarryTheListing() {
    final String ping = vanilla(ProtocolVersion.MINECRAFT_1_8, "SHARD");
    final String result = withListing(ping, ProtocolVersion.MINECRAFT_1_8, SHARD, LEGACY);
    assertEquals(ping.substring(0, ping.length() - 1) + ",\"shard\":" + SHARD.getJson() + "}", result);
    final JsonObject parsed = JsonParser.parseString(result).getAsJsonObject();
    final JsonObject original = JsonParser.parseString(ping).getAsJsonObject();
    for (String key : original.keySet()) {
      assertEquals(original.get(key), parsed.get(key), key);
    }
    final JsonObject shard = parsed.getAsJsonObject("shard");
    assertEquals(SHARD.getMotd(), shard.get("description").getAsString());
    assertEquals(SHA, shard.getAsJsonObject("icon").get("sha256").getAsString());
  }

  @Test
  void aResponseTooLongFor18GoesOutWithout() {
    final String ping = vanilla(ProtocolVersion.MINECRAFT_1_8, "x".repeat(32767 - 300));
    assertTrue(ping.length() < 32767);
    assertEquals(ping, withListing(ping, ProtocolVersion.MINECRAFT_1_8, SHARD, LEGACY));
  }
}
