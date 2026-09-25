package com.velocitypowered.proxy.connection.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.electronwill.nightconfig.toml.TomlParser;
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
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.proxy.config.LegacyClientsConfig;
import com.velocitypowered.proxy.event.VelocityEventManager;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import com.velocitypowered.proxy.testutil.FakePluginManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyClientsListenerTest {

  private static final Component REASON = Component.text("test kick");

  @TempDir
  Path dir;

  private final FakePluginManager pluginManager = new FakePluginManager();
  private final VelocityEventManager eventManager = new VelocityEventManager(pluginManager);
  private final Map<String, RegisteredServer> servers = new HashMap<>();
  private final ProxyServer proxy = mock(ProxyServer.class);

  private LegacyClientsConfig settings;
  private LegacyClientsConfig.Messages messages;
  private RegisteredServer lobby01;
  private RegisteredServer lobby18901;
  private RegisteredServer lobby18902;
  private RegisteredServer lifesteal;

  @BeforeEach
  void setUp() {
    lobby01 = server("eu-lobby-01", 10);
    lobby18901 = server("eu-lobby-189-01", 3);
    lobby18902 = server("eu-lobby-189-02", 1);
    lifesteal = server("lifesteal", 0);
    when(proxy.getServer(anyString())).thenAnswer(invocation -> Optional.ofNullable(
        servers.get(invocation.<String>getArgument(0).toLowerCase(Locale.ROOT))));

    useSettings(true);
    eventManager.register(VelocityVirtualPlugin.INSTANCE,
        new LegacyClientsListener(proxy, () -> settings));
    eventManager.register(FakePluginManager.PLUGIN_A, new PulseLikeListener(lobby01));
  }

  @AfterEach
  void tearDown() {
    pluginManager.shutdown();
  }

  private void useSettings(boolean premiumOnly) {
    final String toml = """
        [legacy-clients]
        enabled = true
        protocols = [47]
        lobbies = ["eu-lobby-189-01", "eu-lobby-189-02"]
        premium-only = %s
        bungeeguard-secret-file = "%s"

        [legacy-clients.servers]
        eu-lobby-189-01 = "bungeeguard"
        eu-lobby-189-02 = "bungeeguard"
        """.formatted(premiumOnly,
        dir.resolve("forwarding-legacy.secret").toString().replace('\\', '/'));
    settings = LegacyClientsConfig.read(new TomlParser().parse(toml).get("legacy-clients"),
        List.of("eu-lobby-01", "eu-lobby-189-01", "eu-lobby-189-02", "lifesteal"),
        "modern-secret".getBytes(StandardCharsets.UTF_8));
    assertTrue(settings.isEnabled());
    messages = settings.getMessages();
  }

  private RegisteredServer server(String name, int players) {
    final RegisteredServer server = mock(RegisteredServer.class);
    when(server.getServerInfo()).thenReturn(
        new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565)));
    final List<Player> connected = new ArrayList<>();
    for (int i = 0; i < players; i++) {
      connected.add(mock(Player.class));
    }
    when(server.getPlayersConnected()).thenReturn(connected);
    servers.put(name, server);
    return server;
  }

  private static Player player(String name, ProtocolVersion version, boolean onlineMode) {
    final Player player = mock(Player.class);
    when(player.getUsername()).thenReturn(name);
    when(player.getUniqueId()).thenReturn(
        UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
    when(player.getProtocolVersion()).thenReturn(version);
    when(player.isOnlineMode()).thenReturn(onlineMode);
    when(player.getCurrentServer()).thenReturn(Optional.empty());
    return player;
  }

  private static Player legacy(boolean onlineMode) {
    return player("Steve", ProtocolVersion.MINECRAFT_1_8, onlineMode);
  }

  private static Player modern() {
    return player("Alex", ProtocolVersion.MAXIMUM_VERSION, false);
  }

  private static void connectedTo(Player player, RegisteredServer server) {
    final ServerInfo info = server.getServerInfo();
    final ServerConnection connection = mock(ServerConnection.class);
    when(connection.getServer()).thenReturn(server);
    when(connection.getServerInfo()).thenReturn(info);
    when(player.getCurrentServer()).thenReturn(Optional.of(connection));
  }

  private <E> E fire(E event) throws Exception {
    return eventManager.fire(event).get(5, TimeUnit.SECONDS);
  }

  private KickedFromServerEvent kick(Player player, RegisteredServer from, Component reason,
      boolean duringConnect) throws Exception {
    return fire(new KickedFromServerEvent(player, from, reason, duringConnect,
        DisconnectPlayer.create(Component.text("friendly"))));
  }

  @Test
  void deniesOfflineLegacyClientsWhenPremiumOnly() throws Exception {
    final LoginEvent event = fire(new LoginEvent(legacy(false)));
    assertFalse(event.getResult().isAllowed());
    assertEquals(Optional.of(messages.premiumOnly()), event.getResult().getReasonComponent());
  }

  @Test
  void admitsPremiumLegacyClientsAndOfflineModernClients() throws Exception {
    assertTrue(fire(new LoginEvent(legacy(true))).getResult().isAllowed());
    assertTrue(fire(new LoginEvent(modern())).getResult().isAllowed());
  }

  @Test
  void admitsOfflineLegacyClientsWhenNotPremiumOnly() throws Exception {
    useSettings(false);
    assertTrue(fire(new LoginEvent(legacy(false))).getResult().isAllowed());
  }

  @Test
  void keepsAnEarlierLoginDenial() throws Exception {
    final LoginEvent event = new LoginEvent(legacy(false));
    event.setResult(ComponentResult.denied(Component.text("banned")));
    fire(event);
    assertEquals(Optional.of(Component.text("banned")), event.getResult().getReasonComponent());
  }

  @Test
  void legacyClientsStartOnTheLeastPopulatedLobby() throws Exception {
    final PlayerChooseInitialServerEvent event =
        fire(new PlayerChooseInitialServerEvent(legacy(true), lobby01));
    assertEquals(Optional.of(lobby18902), event.getInitialServer());
  }

  @Test
  void modernClientsKeepTheirInitialServer() throws Exception {
    final PlayerChooseInitialServerEvent event =
        fire(new PlayerChooseInitialServerEvent(modern(), lobby01));
    assertEquals(Optional.of(lobby01), event.getInitialServer());
  }

  @Test
  void legacyClientsMoveFreelyBetweenLegacyServers() throws Exception {
    final Player steve = legacy(true);
    connectedTo(steve, lobby18901);
    final ServerPreConnectEvent event =
        fire(new ServerPreConnectEvent(steve, lobby18902, lobby18901));
    assertEquals(Optional.of(lobby18902), event.getResult().getServer());
    verify(steve, never()).sendMessage(any(Component.class));
  }

  @Test
  void legacyClientsAreSentBackToALegacyLobbyOnFirstJoin() throws Exception {
    final ServerPreConnectEvent event =
        fire(new ServerPreConnectEvent(legacy(true), lobby18902, null));
    assertEquals(Optional.of(lobby18902), event.getResult().getServer());
  }

  @Test
  void legacyClientsWithoutALegacyLobbyAreDisconnectedOnFirstJoin() throws Exception {
    servers.remove("eu-lobby-189-01");
    servers.remove("eu-lobby-189-02");
    final Player steve = legacy(true);
    final ServerPreConnectEvent event = fire(new ServerPreConnectEvent(steve, lobby01, null));
    assertFalse(event.getResult().isAllowed());
    verify(steve).disconnect(messages.noLobby());
  }

  @Test
  void legacyClientsCannotJoinOtherServersOnceConnected() throws Exception {
    final Player steve = legacy(true);
    connectedTo(steve, lobby18902);
    final ServerPreConnectEvent event =
        fire(new ServerPreConnectEvent(steve, lifesteal, lobby18902));
    assertFalse(event.getResult().isAllowed());
    verify(steve).sendMessage(messages.serverDenied());
    verify(steve, never()).disconnect(any());
  }

  @Test
  void modernClientsCannotJoinLegacyServers() throws Exception {
    final Player alex = modern();
    connectedTo(alex, lobby01);
    final ServerPreConnectEvent event = fire(new ServerPreConnectEvent(alex, lobby18901, lobby01));
    assertFalse(event.getResult().isAllowed());
    verify(alex).sendMessage(messages.modernDenied());
    verify(alex, never()).disconnect(any());
  }

  @Test
  void modernClientsSentToALegacyServerOnFirstJoinAreDisconnected() throws Exception {
    eventManager.unregisterListeners(FakePluginManager.PLUGIN_A);
    final Player alex = modern();
    final ServerPreConnectEvent event = fire(new ServerPreConnectEvent(alex, lobby18901, null));
    assertFalse(event.getResult().isAllowed());
    verify(alex).disconnect(messages.modernDenied());
  }

  @Test
  void modernClientsFollowPulseOnFirstJoin() throws Exception {
    final ServerPreConnectEvent event =
        fire(new ServerPreConnectEvent(modern(), lobby18901, null));
    assertEquals(Optional.of(lobby01), event.getResult().getServer());
  }

  @Test
  void deniedConnectionsAreLeftAlone() throws Exception {
    final Player steve = legacy(true);
    connectedTo(steve, lobby18901);
    final ServerPreConnectEvent event = new ServerPreConnectEvent(steve, lifesteal, lobby18901);
    event.setResult(ServerResult.denied());
    fire(event);
    assertFalse(event.getResult().isAllowed());
    verify(steve, never()).sendMessage(any(Component.class));
  }

  @Test
  void legacyClientsKickedWhileSwitchingStayWhereTheyAre() throws Exception {
    final Player steve = legacy(true);
    connectedTo(steve, lobby18901);
    final KickedFromServerEvent event = kick(steve, lobby18902, REASON, true);
    assertEquals(REASON, assertInstanceOf(Notify.class, event.getResult()).getMessageComponent());
  }

  @Test
  void legacyClientsAreRedirectedToAnotherLegacyLobby() throws Exception {
    final Player steve = legacy(true);
    connectedTo(steve, lobby18902);
    final RedirectPlayer redirect = assertInstanceOf(RedirectPlayer.class,
        kick(steve, lobby18902, REASON, false).getResult());
    assertEquals(lobby18901, redirect.getServer());
    assertEquals(REASON, redirect.getMessageComponent());
  }

  @Test
  void legacyClientsWithoutAnotherLobbyAreDisconnectedWithTheReason() throws Exception {
    servers.remove("eu-lobby-189-02");
    final Player steve = legacy(true);
    connectedTo(steve, lobby18901);
    final DisconnectPlayer disconnect = assertInstanceOf(DisconnectPlayer.class,
        kick(steve, lobby18901, REASON, false).getResult());
    assertEquals(REASON, disconnect.getReasonComponent());
  }

  @Test
  void legacyClientsWithoutAnotherLobbyOrReasonGetTheNoLobbyMessage() throws Exception {
    servers.remove("eu-lobby-189-02");
    final DisconnectPlayer disconnect = assertInstanceOf(DisconnectPlayer.class,
        kick(legacy(true), lobby18901, null, false).getResult());
    assertEquals(messages.noLobby(), disconnect.getReasonComponent());
  }

  @Test
  void lobbiesThatFailedAreNotTriedAgain() throws Exception {
    final Player steve = legacy(true);
    assertEquals(Optional.of(lobby18902),
        fire(new PlayerChooseInitialServerEvent(steve, lobby01)).getInitialServer());
    assertEquals(Optional.of(lobby18902),
        fire(new ServerPreConnectEvent(steve, lobby18902, null)).getResult().getServer());

    final RedirectPlayer redirect = assertInstanceOf(RedirectPlayer.class,
        kick(steve, lobby18902, null, false).getResult());
    assertEquals(lobby18901, redirect.getServer());
    assertEquals(Optional.of(lobby18901),
        fire(new ServerPreConnectEvent(steve, lobby18901, null)).getResult().getServer());

    final DisconnectPlayer disconnect = assertInstanceOf(DisconnectPlayer.class,
        kick(steve, lobby18901, null, false).getResult());
    assertEquals(messages.noLobby(), disconnect.getReasonComponent());

    final ServerPreConnectEvent retry = fire(new ServerPreConnectEvent(steve, lobby01, null));
    assertFalse(retry.getResult().isAllowed());
    verify(steve).disconnect(messages.noLobby());
  }

  @Test
  void aSuccessfulConnectionForgetsTheFailedLobbies() throws Exception {
    final Player steve = legacy(true);
    kick(steve, lobby18902, null, false);
    fire(new ServerPostConnectEvent(steve, null));
    final RedirectPlayer redirect = assertInstanceOf(RedirectPlayer.class,
        kick(steve, lobby18901, null, false).getResult());
    assertEquals(lobby18902, redirect.getServer());
  }

  @Test
  void leavingTheProxyForgetsTheFailedLobbies() throws Exception {
    final Player steve = legacy(true);
    kick(steve, lobby18902, null, false);
    fire(new DisconnectEvent(steve, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
    final RedirectPlayer redirect = assertInstanceOf(RedirectPlayer.class,
        kick(steve, lobby18901, null, false).getResult());
    assertEquals(lobby18902, redirect.getServer());
  }

  @Test
  void modernClientsKeepPulseRedirects() throws Exception {
    final Player alex = modern();
    connectedTo(alex, lifesteal);
    final RedirectPlayer redirect = assertInstanceOf(RedirectPlayer.class,
        kick(alex, lifesteal, REASON, false).getResult());
    assertEquals(lobby01, redirect.getServer());
  }

  @Test
  void nothingChangesWhenDisabled() throws Exception {
    settings = LegacyClientsConfig.disabled();
    final Player steve = legacy(false);
    assertTrue(fire(new LoginEvent(steve)).getResult().isAllowed());
    assertEquals(Optional.of(lobby01),
        fire(new PlayerChooseInitialServerEvent(steve, lobby01)).getInitialServer());
    assertEquals(Optional.of(lobby01),
        fire(new ServerPreConnectEvent(steve, lobby18901, null)).getResult().getServer());
    assertEquals(lobby01, assertInstanceOf(RedirectPlayer.class,
        kick(steve, lobby18901, REASON, false).getResult()).getServer());

    final Player alex = modern();
    connectedTo(alex, lobby01);
    assertEquals(Optional.of(lobby18901),
        fire(new ServerPreConnectEvent(alex, lobby18901, lobby01)).getResult().getServer());
    verify(steve, never()).disconnect(any());
    verify(alex, never()).sendMessage(any(Component.class));
  }

  public static final class PulseLikeListener {

    private final RegisteredServer lobby;

    PulseLikeListener(RegisteredServer lobby) {
      this.lobby = lobby;
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
      if (event.getPreviousServer() == null) {
        event.setResult(ServerResult.allowed(lobby));
      }
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
      event.setResult(RedirectPlayer.create(lobby));
    }
  }
}
