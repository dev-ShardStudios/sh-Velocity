package com.velocitypowered.proxy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ShardClientConfigTest {

  private static final String SHA = "01E4F7DAB6B501DDEF0B28C3D61E4243B25DCD3D28167E8E8C4F5FCCBF17F942";

  private static ShardClientConfig read(String toml) {
    final CommentedConfig parsed = new TomlParser().parse(toml);
    return ShardClientConfig.read(parsed.get("shard-client"));
  }

  @Test
  void disabledWithoutSection() {
    assertFalse(ShardClientConfig.read(null).isEnabled());
    assertFalse(read("motd = \"x\"").isEnabled());
  }

  @Test
  void readsDescriptionAndIcon() {
    final ShardClientConfig config = read("""
        [shard-client]
        motd = "<gradient:#B29CFF:#8E7CCC>ꜱʜᴀʀᴅ</gradient> <sprite:\\"minecraft:items\\":\\"item/diamond\\">"
        icon-url = "https://dl.shard.rip/icons/%s.png"
        icon-sha256 = "%s"
        """.formatted(SHA.toLowerCase(), SHA));
    assertTrue(config.isEnabled());
    assertEquals(SHA.toLowerCase(), config.getIcon().sha256());
    final JsonObject json = JsonParser.parseString(config.getJson()).getAsJsonObject();
    assertEquals(config.getMotd(), json.get("description").getAsString());
    assertEquals(config.getIcon().url(), json.getAsJsonObject("icon").get("url").getAsString());
    assertEquals(SHA.toLowerCase(), json.getAsJsonObject("icon").get("sha256").getAsString());
    assertTrue(config.getJson().contains("<gradient:#B29CFF:#8E7CCC>"), config.getJson());
  }

  @Test
  void descriptionAndIconStandAlone() {
    final ShardClientConfig text = read("[shard-client]\nmotd = \"ꜱʜᴀʀᴅ\"\n");
    assertTrue(text.isEnabled());
    assertNull(text.getIcon());
    assertFalse(text.getJson().contains("icon"));
    final ShardClientConfig icon = read("""
        [shard-client]
        icon-url = "https://dl.shard.rip/a.png"
        icon-sha256 = "%s"
        """.formatted(SHA));
    assertTrue(icon.isEnabled());
    assertFalse(icon.getJson().contains("description"));
  }

  @Test
  void carriesTheAnimationsFile() {
    final ShardClientConfig config = read("""
        [shard-client]
        motd = "%%animation:ShardTitle%%"
        animations-url = "https://dl.shard.rip/animations/%s.json"
        animations-sha256 = "%s"
        """.formatted(SHA.toLowerCase(), SHA));
    assertTrue(config.isEnabled());
    final JsonObject json = JsonParser.parseString(config.getJson()).getAsJsonObject();
    assertEquals(SHA.toLowerCase(), json.getAsJsonObject("animations").get("sha256").getAsString());
    assertEquals(config.getAnimations().url(), json.getAsJsonObject("animations").get("url").getAsString());
    assertFalse(read("""
        [shard-client]
        motd = "x"
        animations-url = "https://dl.shard.rip/a.json"
        """).isEnabled());
    assertFalse(read("""
        [shard-client]
        icon-url = "https://dl.shard.rip/a.png"
        icon-sha256 = "%s"
        animations-url = "https://dl.shard.rip/a.json"
        animations-sha256 = "%s"
        """.formatted(SHA, SHA)).isEnabled());
  }

  @Test
  void describesItselfForTheLog() {
    assertEquals("shard-client: off", ShardClientConfig.disabled().describe());
    assertEquals("shard-client: on for the protocols of legacy-clients, description 5 characters, icon "
        + SHA.toLowerCase().substring(0, 12) + ", animations none", read("""
        [shard-client]
        motd = "ꜱʜᴀʀᴅ"
        icon-url = "https://dl.shard.rip/a.png"
        icon-sha256 = "%s"
        """.formatted(SHA)).describe());
  }

  @Test
  void emptyValuesTurnItOff() {
    assertFalse(read("[shard-client]\nmotd = \"\"\nicon-url = \"\"\nicon-sha256 = \"\"\n").isEnabled());
  }

  @Test
  void mistakesTurnItOff() {
    assertFalse(read("[shard-client]\nicon-url = \"https://dl.shard.rip/a.png\"\n").isEnabled());
    assertFalse(read("[shard-client]\nicon-sha256 = \"%s\"\n".formatted(SHA)).isEnabled());
    assertFalse(read("""
        [shard-client]
        icon-url = "http://dl.shard.rip/a.png"
        icon-sha256 = "%s"
        """.formatted(SHA)).isEnabled());
    assertFalse(read("""
        [shard-client]
        icon-url = "https://dl.shard.rip/a.png"
        icon-sha256 = "abc"
        """).isEnabled());
    assertFalse(read("[shard-client]\nmotd = 7\n").isEnabled());
    assertFalse(read("shard-client = \"on\"\n").isEnabled());
    assertFalse(read("[shard-client]\nmotd = \"%s\"\n".formatted("a".repeat(ShardClientConfig.MAX_MOTD + 1)))
        .isEnabled());
  }
}
