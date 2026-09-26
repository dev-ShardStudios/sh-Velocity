package com.velocitypowered.proxy.protocol.packet.chat;

import com.velocitypowered.api.network.ProtocolVersion;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.HoverEvent;

public final class ObjectComponents {

  private ObjectComponents() {
  }

  public static Component forVersion(Component component, ProtocolVersion version) {
    if (version == ProtocolVersion.UNKNOWN || version.noLessThan(ProtocolVersion.MINECRAFT_1_21_9)
        || !contains(component)) {
      return component;
    }
    return replace(component);
  }

  private static boolean contains(Component component) {
    if (component instanceof ObjectComponent) {
      return true;
    }
    for (Component child : component.children()) {
      if (contains(child)) {
        return true;
      }
    }
    if (component instanceof TranslatableComponent translatable) {
      for (TranslationArgument argument : translatable.arguments()) {
        if (argument.value() instanceof Component value && contains(value)) {
          return true;
        }
      }
    }
    HoverEvent<?> hover = component.hoverEvent();
    return hover != null && hover.value() instanceof Component value && contains(value);
  }

  private static Component replace(Component component) {
    List<Component> children = new ArrayList<>(component.children().size());
    for (Component child : component.children()) {
      children.add(replace(child));
    }

    Component result;
    if (component instanceof ObjectComponent object) {
      Component fallback = object.fallback();
      result = fallback == null
          ? Component.text("", object.style())
          : replace(fallback).applyFallbackStyle(object.style());
      children.addAll(0, result.children());
    } else if (component instanceof TranslatableComponent translatable) {
      List<TranslationArgument> arguments = new ArrayList<>(translatable.arguments().size());
      for (TranslationArgument argument : translatable.arguments()) {
        arguments.add(argument.value() instanceof Component value
            ? TranslationArgument.component(replace(value)) : argument);
      }
      result = translatable.arguments(arguments);
    } else {
      result = component;
    }

    HoverEvent<?> hover = result.hoverEvent();
    if (hover != null && hover.value() instanceof Component value) {
      result = result.hoverEvent(HoverEvent.showText(replace(value)));
    }
    return result.children(children);
  }
}
