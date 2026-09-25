package com.velocitypowered.proxy.connection.legacy;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent.DisconnectPlayer;
import com.velocitypowered.api.event.player.KickedFromServerEvent.Notify;
import com.velocitypowered.api.event.player.KickedFromServerEvent.RedirectPlayer;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent.ServerResult;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.config.LegacyClientsConfig;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.translation.Argument;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class LegacyClientsListener {

  private static final Logger logger = LogManager.getLogger(LegacyClientsListener.class);

  private final ProxyServer server;
  private final Supplier<LegacyClientsConfig> config;
  private final Map<UUID, Set<String>> triedLobbies = new ConcurrentHashMap<>();

  public LegacyClientsListener(ProxyServer server, Supplier<LegacyClientsConfig> config) {
    this.server = server;
    this.config = config;
  }

  @Subscribe(order = PostOrder.LAST)
  public void onProxyInitialize(ProxyInitializeEvent event) {
    logger.info(config.get().describe());
  }

  @Subscribe(order = PostOrder.LAST)
  public void onProxyReload(ProxyReloadEvent event) {
    logger.info(config.get().describe());
  }

  @Subscribe(order = PostOrder.LAST)
  public void onLogin(LoginEvent event) {
    final LegacyClientsConfig settings = config.get();
    final Player player = event.getPlayer();
    if (!settings.admits(player.getProtocolVersion()) || !settings.isPremiumOnly()
        || player.isOnlineMode() || !event.getResult().isAllowed()) {
      return;
    }
    event.setResult(ComponentResult.denied(settings.getMessages().premiumOnly()));
    logger.info("legacy-clients: {} denied at login, only premium accounts are admitted",
        describe(player));
  }

  @Subscribe(order = PostOrder.LAST)
  public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
    final LegacyClientsConfig settings = config.get();
    if (settings.admits(event.getPlayer().getProtocolVersion())) {
      leastPopulatedLobby(settings, event.getPlayer()).ifPresent(event::setInitialServer);
    }
  }

  @Subscribe(order = PostOrder.LAST)
  public void onServerPreConnect(ServerPreConnectEvent event) {
    final LegacyClientsConfig settings = config.get();
    final Optional<RegisteredServer> requested = event.getResult().getServer();
    if (!settings.isEnabled() || requested.isEmpty()) {
      return;
    }
    final Player player = event.getPlayer();
    final String target = requested.get().getServerInfo().getName();
    final boolean firstJoin = event.getPreviousServer() == null;
    final LegacyClientsConfig.Messages messages = settings.getMessages();

    if (!settings.admits(player.getProtocolVersion())) {
      if (settings.isLegacyServer(target)) {
        event.setResult(ServerResult.denied());
        if (firstJoin) {
          player.disconnect(messages.modernDenied());
        } else {
          player.sendMessage(messages.modernDenied());
        }
        logger.info("legacy-clients: {} denied, {} is reserved to legacy clients",
            describe(player), target);
      }
      return;
    }

    if (settings.isLegacyServer(target)) {
      if (firstJoin) {
        logger.info("legacy-clients: {} routed to {}", describe(player), target);
      }
      return;
    }

    if (!firstJoin) {
      event.setResult(ServerResult.denied());
      player.sendMessage(messages.serverDenied());
      logger.info("legacy-clients: {} denied, {} is not a legacy server", describe(player),
          target);
      return;
    }

    final Optional<RegisteredServer> lobby = leastPopulatedLobby(settings, player);
    if (lobby.isPresent()) {
      event.setResult(ServerResult.allowed(lobby.get()));
      logger.info("legacy-clients: {} routed to {} instead of {}", describe(player),
          lobby.get().getServerInfo().getName(), target);
    } else {
      event.setResult(ServerResult.denied());
      player.disconnect(messages.noLobby());
      logger.info("legacy-clients: {} disconnected, no legacy lobby is available",
          describe(player));
    }
  }

  @Subscribe(order = PostOrder.LAST)
  public void onKickedFromServer(KickedFromServerEvent event) {
    final LegacyClientsConfig settings = config.get();
    final Player player = event.getPlayer();
    if (!settings.admits(player.getProtocolVersion())) {
      return;
    }
    final String from = event.getServer().getServerInfo().getName();
    final Optional<Component> reason = event.getServerKickReason();

    if (event.kickedDuringServerConnect() && player.getCurrentServer().isPresent()) {
      event.setResult(Notify.create(reason.orElseGet(() -> connectFailed(from))));
      logger.info("legacy-clients: {} could not join {}, staying on {}", describe(player), from,
          player.getCurrentServer().get().getServerInfo().getName());
      return;
    }

    triedLobbies.computeIfAbsent(player.getUniqueId(), id -> ConcurrentHashMap.newKeySet())
        .add(from.toLowerCase(Locale.ROOT));
    final Optional<RegisteredServer> lobby = leastPopulatedLobby(settings, player);
    if (lobby.isPresent()) {
      event.setResult(reason.isPresent()
          ? RedirectPlayer.create(lobby.get(), reason.get())
          : RedirectPlayer.create(lobby.get()));
      logger.info("legacy-clients: {} left {}, redirected to {}", describe(player), from,
          lobby.get().getServerInfo().getName());
    } else {
      event.setResult(DisconnectPlayer.create(reason.orElse(settings.getMessages().noLobby())));
      logger.info("legacy-clients: {} left {}, disconnected as no other legacy lobby is available",
          describe(player), from);
    }
  }

  @Subscribe(order = PostOrder.LAST)
  public void onServerPostConnect(ServerPostConnectEvent event) {
    triedLobbies.remove(event.getPlayer().getUniqueId());
  }

  @Subscribe(order = PostOrder.LAST)
  public void onDisconnect(DisconnectEvent event) {
    triedLobbies.remove(event.getPlayer().getUniqueId());
  }

  private Optional<RegisteredServer> leastPopulatedLobby(LegacyClientsConfig settings,
      Player player) {
    final Set<String> tried = triedLobbies.getOrDefault(player.getUniqueId(), Set.of());
    RegisteredServer best = null;
    int bestCount = Integer.MAX_VALUE;
    for (final String name : settings.getLobbies()) {
      if (tried.contains(name.toLowerCase(Locale.ROOT))) {
        continue;
      }
      final Optional<RegisteredServer> lobby = server.getServer(name);
      if (lobby.isPresent() && lobby.get().getPlayersConnected().size() < bestCount) {
        best = lobby.get();
        bestCount = best.getPlayersConnected().size();
      }
    }
    return Optional.ofNullable(best);
  }

  private static Component connectFailed(String serverName) {
    return Component.translatable("velocity.error.connecting-server-error",
        NamedTextColor.RED, Argument.string("server", serverName));
  }

  private static String describe(Player player) {
    return player.getUsername() + " (protocol " + player.getProtocolVersion().getProtocol() + ")";
  }
}
