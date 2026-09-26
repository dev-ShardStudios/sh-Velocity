package com.velocitypowered.proxy.protocol.packet.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.DisconnectPacket;
import com.velocitypowered.proxy.protocol.packet.LegacyPlayerListItemPacket;
import com.velocitypowered.proxy.protocol.packet.chat.legacy.LegacyChatBuilder;
import com.velocitypowered.proxy.protocol.packet.chat.legacy.LegacyChatPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.junit.jupiter.api.Test;

class ObjectComponentsTest {

  private static final ObjectComponent SPRITE = Component.object(ObjectContents.sprite(
      Key.key("minecraft:gui"), Key.key("minecraft:icon/trial_available")));

  private static final Component MESSAGE = Component.text("Trial ")
      .append(SPRITE)
      .append(Component.text(" open"));

  @Test
  void legacyJsonDropsSpriteAndKeepsText() {
    String json = new ComponentHolder(ProtocolVersion.MINECRAFT_1_8, MESSAGE).getJson();

    assertFalse(json.contains("atlas"), json);
    assertFalse(json.contains("trial_available"), json);
    assertTrue(json.contains("Trial "), json);
    assertTrue(json.contains(" open"), json);
  }

  @Test
  void clientsBefore1219LoseSprite() {
    String json = new ComponentHolder(ProtocolVersion.MINECRAFT_1_21_7, MESSAGE).getJson();

    assertFalse(json.contains("trial_available"), json);
  }

  @Test
  void modernClientsKeepSprite() {
    ComponentHolder holder = new ComponentHolder(ProtocolVersion.MINECRAFT_1_21_9, MESSAGE);

    assertTrue(holder.getJson().contains("trial_available"), holder.getJson());
    assertTrue(holder.getBinaryTag().toString().contains("trial_available"));
  }

  @Test
  void spriteBecomesItsFallbackWithTheSameStyle() {
    Component sprite = SPRITE.fallback(Component.text("[!]")).color(NamedTextColor.GOLD);

    assertEquals(Component.text("[!]", NamedTextColor.GOLD),
        ObjectComponents.forVersion(sprite, ProtocolVersion.MINECRAFT_1_8));
  }

  @Test
  void spriteWithoutFallbackKeepsItsChildren() {
    Component sprite = SPRITE.color(NamedTextColor.GOLD).append(Component.text("child"));

    assertEquals(Component.text("", NamedTextColor.GOLD).append(Component.text("child")),
        ObjectComponents.forVersion(sprite, ProtocolVersion.MINECRAFT_1_8));
  }

  @Test
  void hoverTextIsCleaned() {
    Component message = Component.text("hover me").hoverEvent(HoverEvent.showText(MESSAGE));

    String json = new ComponentHolder(ProtocolVersion.MINECRAFT_1_8, message).getJson();

    assertFalse(json.contains("trial_available"), json);
    assertTrue(json.contains(" open"), json);
  }

  @Test
  void translationArgumentsAreCleaned() {
    Component message = Component.translatable("chat.type.text", Component.text("Jeyzer"), MESSAGE);

    String json = new ComponentHolder(ProtocolVersion.MINECRAFT_1_8, message).getJson();

    assertFalse(json.contains("trial_available"), json);
    assertTrue(json.contains("Jeyzer"), json);
  }

  @Test
  void componentsWithoutObjectsAreUntouched() {
    Component message = Component.text("plain").append(Component.text("text"));

    assertSame(message, ObjectComponents.forVersion(message, ProtocolVersion.MINECRAFT_1_8));
  }

  @Test
  void legacyChatDropsSprite() {
    LegacyChatPacket packet = (LegacyChatPacket) new LegacyChatBuilder(ProtocolVersion.MINECRAFT_1_8)
        .component(MESSAGE)
        .toClient();

    assertFalse(packet.getMessage().contains("trial_available"), packet.getMessage());
    assertTrue(packet.getMessage().contains(" open"), packet.getMessage());
  }

  @Test
  void loginDisconnectDependsOnTheClient() {
    String legacy = DisconnectPacket.create(MESSAGE, ProtocolVersion.MINECRAFT_1_8,
        StateRegistry.LOGIN).getReason().getJson();
    String modern = DisconnectPacket.create(MESSAGE, ProtocolVersion.MINECRAFT_1_21_9,
        StateRegistry.LOGIN).getReason().getJson();

    assertFalse(legacy.contains("trial_available"), legacy);
    assertTrue(modern.contains("trial_available"), modern);
  }

  @Test
  void playDisconnectDropsSpriteForLegacyClients() {
    String json = DisconnectPacket.create(MESSAGE, ProtocolVersion.MINECRAFT_1_8,
        StateRegistry.PLAY).getReason().getJson();

    assertFalse(json.contains("trial_available"), json);
  }

  @Test
  void legacyTabListDisplayNameDropsSprite() {
    LegacyPlayerListItemPacket.Item item = new LegacyPlayerListItemPacket.Item(UUID.randomUUID())
        .setName("Jeyzer")
        .setDisplayName(MESSAGE);
    LegacyPlayerListItemPacket packet = new LegacyPlayerListItemPacket(
        LegacyPlayerListItemPacket.ADD_PLAYER, List.of(item));

    ByteBuf buf = Unpooled.buffer();
    try {
      packet.encode(buf, ProtocolUtils.Direction.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_8);
      LegacyPlayerListItemPacket decoded = new LegacyPlayerListItemPacket();
      decoded.decode(buf, ProtocolUtils.Direction.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_8);

      String json = GsonComponentSerializer.gson()
          .serialize(decoded.getItems().getFirst().getDisplayName());
      assertFalse(json.contains("trial_available"), json);
      assertTrue(json.contains(" open"), json);
    } finally {
      buf.release();
    }
  }
}
