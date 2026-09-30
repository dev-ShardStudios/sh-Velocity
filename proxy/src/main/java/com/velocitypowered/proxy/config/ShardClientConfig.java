package com.velocitypowered.proxy.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class ShardClientConfig {

  public static final int MAX_MOTD = 1024;
  public static final int MAX_URL = 256;

  private static final Logger logger = LogManager.getLogger(ShardClientConfig.class);
  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final ShardClientConfig DISABLED = new ShardClientConfig(null, null, null);

  public record Asset(String url, String sha256) {
  }

  private final @Nullable String motd;
  private final @Nullable Asset icon;
  private final @Nullable Asset animations;
  private final @Nullable String json;

  private ShardClientConfig(@Nullable String motd, @Nullable Asset icon, @Nullable Asset animations) {
    this.motd = motd;
    this.icon = icon;
    this.animations = animations;
    if (motd == null && icon == null) {
      this.json = null;
      return;
    }
    final JsonObject shard = new JsonObject();
    if (motd != null) {
      shard.addProperty("description", motd);
    }
    if (icon != null) {
      shard.add("icon", asset(icon));
    }
    if (animations != null) {
      shard.add("animations", asset(animations));
    }
    this.json = GSON.toJson(shard);
  }

  private static JsonObject asset(Asset asset) {
    final JsonObject object = new JsonObject();
    object.addProperty("url", asset.url());
    object.addProperty("sha256", asset.sha256());
    return object;
  }

  public static ShardClientConfig disabled() {
    return DISABLED;
  }

  public static ShardClientConfig read(@Nullable Object section) {
    if (section == null) {
      return DISABLED;
    }
    final List<String> errors = new ArrayList<>();
    ShardClientConfig config = DISABLED;
    if (section instanceof UnmodifiableConfig table) {
      config = parse(table, errors);
    } else {
      errors.add("shard-client must be a table: write it as [shard-client]");
    }
    if (errors.isEmpty()) {
      if (config.isEnabled()) {
        logger.info(config.describe());
      }
      return config;
    }
    errors.forEach(logger::error);
    return DISABLED;
  }

  private static ShardClientConfig parse(UnmodifiableConfig section, List<String> errors) {
    String motd = get(section, "motd", errors);
    if (motd != null && motd.isBlank()) {
      motd = null;
    }
    if (motd != null && motd.length() > MAX_MOTD) {
      errors.add("shard-client.motd is " + motd.length() + " characters, the limit is " + MAX_MOTD);
    }
    final Asset icon = asset(section, "icon", errors);
    final Asset animations = asset(section, "animations", errors);
    if (animations != null && motd == null) {
      errors.add("shard-client: animations-url is only read through %animation:name% in motd, which is "
          + "missing");
    }
    return errors.isEmpty() ? new ShardClientConfig(motd, icon, animations) : DISABLED;
  }

  private static @Nullable Asset asset(UnmodifiableConfig section, String name, List<String> errors) {
    String url = get(section, name + "-url", errors);
    String sha256 = get(section, name + "-sha256", errors);
    if (url != null && url.isBlank()) {
      url = null;
    }
    if (sha256 != null && sha256.isBlank()) {
      sha256 = null;
    }
    if (url == null && sha256 == null) {
      return null;
    }
    if (url == null || sha256 == null) {
      errors.add("shard-client: " + name + "-url and " + name + "-sha256 go together, set both or "
          + "neither");
      return null;
    }
    sha256 = sha256.toLowerCase(Locale.ROOT);
    if (!SHA256.matcher(sha256).matches()) {
      errors.add("shard-client." + name + "-sha256 must be 64 hexadecimal characters");
    }
    if (url.length() > MAX_URL || !isHttps(url)) {
      errors.add("shard-client." + name + "-url must be an https address of at most " + MAX_URL
          + " characters");
    }
    return new Asset(url, sha256);
  }

  private static boolean isHttps(String url) {
    try {
      final URI uri = new URI(url);
      return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private static @Nullable String get(UnmodifiableConfig section, String key, List<String> errors) {
    final Object value = section.get(List.of(key));
    if (value == null) {
      return null;
    }
    if (!(value instanceof String text)) {
      errors.add("shard-client." + key + " must be a string, not " + value);
      return null;
    }
    return text;
  }

  public boolean isEnabled() {
    return json != null;
  }

  public String describe() {
    if (!isEnabled()) {
      return "shard-client: off";
    }
    return "shard-client: on for the protocols of legacy-clients, description "
        + (motd == null ? "none" : motd.length() + " characters")
        + ", icon " + (icon == null ? "none" : icon.sha256().substring(0, 12))
        + ", animations " + (animations == null ? "none" : animations.sha256().substring(0, 12));
  }

  public @Nullable String getMotd() {
    return motd;
  }

  public @Nullable Asset getIcon() {
    return icon;
  }

  public @Nullable Asset getAnimations() {
    return animations;
  }

  public @Nullable String getJson() {
    return json;
  }
}
