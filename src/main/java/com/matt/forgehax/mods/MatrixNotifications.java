package com.matt.forgehax.mods;

import static com.matt.forgehax.Helper.getLocalPlayer;
import static com.matt.forgehax.Helper.printError;
import static com.matt.forgehax.asm.reflection.FastReflection.Fields.GuiDisconnected_message;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.matt.forgehax.asm.events.PacketEvent;
import com.matt.forgehax.events.LocalPlayerUpdateEvent;
import com.matt.forgehax.util.command.Setting;
import com.matt.forgehax.util.entity.EntityUtils;
import com.matt.forgehax.util.mod.Category;
import com.matt.forgehax.util.mod.ToggleMod;
import com.matt.forgehax.util.mod.loader.RegisterMod;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.network.play.server.SPacketChat;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContextBuilder;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

@RegisterMod
public class MatrixNotifications extends ToggleMod {
  
  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
  
  static {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      EXECUTOR.shutdown();
      try {
        while (!EXECUTOR.isTerminated()) {
          EXECUTOR.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        }
      } catch (Throwable t) {
        // ignore
      }
    }));
  }
  
  private static final Registry<ConnectionSocketFactory> SOCKET_FACTORY_REGISTRY;
  
  static {
    Registry<ConnectionSocketFactory> sf = null;
    try {
      SSLContextBuilder builder = SSLContexts.custom();
      builder.loadTrustMaterial(null, (chain, authType) -> true);
      SSLContext sslContext = builder.build();
      SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(sslContext,
          new AllowAllHostsVerifier());
      sf = RegistryBuilder.<ConnectionSocketFactory>create().register("https", sslsf).build();
    } catch (NoSuchAlgorithmException | KeyStoreException | KeyManagementException e) {
      e.printStackTrace();
    } finally {
      SOCKET_FACTORY_REGISTRY = sf;
    }
  }

  public enum Provider {
    DISCORD,
    MATRIX
  }
  
  private final Setting<String> url =
      getCommandStub()
          .builders()
          .<String>newSettingBuilder()
          .name("url")
          .description("URL to the WebHook")
          .defaultTo("")
          .build();
  
  private final Setting<String> user =
      getCommandStub()
          .builders()
          .<String>newSettingBuilder()
          .name("user")
          .description("User to ping for high priority messages (use ID for Discord)")
          .defaultTo("")
          .build();
  
  private final Setting<String> skin_server_url =
      getCommandStub()
          .builders()
          .<String>newSettingBuilder()
          .name("skin-server-url")
          .description("URL to the skin server. If left empty then no image will be used.")
          .defaultTo("https://visage.surgeplay.com/face/160/")
          .build();
  
  private final Setting<Integer> queue_notify_pos =
      getCommandStub()
          .builders()
          .<Integer>newSettingBuilder()
          .name("queue-notify-pos")
          .description("Position to start sending notifications at")
          .min(1)
          .max(100)
          .defaultTo(5)
          .build();
  
  private final Setting<Boolean> on_connected =
      getCommandStub()
          .builders()
          .<Boolean>newSettingBuilder()
          .name("on-connected")
          .description("Message on connected to server")
          .defaultTo(true)
          .build();
  
  private final Setting<Boolean> on_disconnected =
      getCommandStub()
          .builders()
          .<Boolean>newSettingBuilder()
          .name("on-disconnected")
          .description("Message on disconnected from server")
          .defaultTo(true)
          .build();
  
  private final Setting<Boolean> on_queue_move =
      getCommandStub()
          .builders()
          .<Boolean>newSettingBuilder()
          .name("on-queue-move")
          .description("Message when player moves in the queue")
          .defaultTo(true)
          .build();

  private final Setting<Boolean> relay_mentions =
      getCommandStub()
          .builders()
          .<Boolean>newSettingBuilder()
          .name("mentions")
          .description("Relay messages with your username")
          .defaultTo(true)
          .build();

  private final Setting<Boolean> relay_chat =
      getCommandStub()
          .builders()
          .<Boolean>newSettingBuilder()
          .name("relay_chat")
          .description("Pass all chat messages to WebHook")
          .defaultTo(false)
          .build();
  
  private final Setting<Boolean> log_visual_range =
      getCommandStub()
          .builders()
          .<Boolean>newSettingBuilder()
          .name("visual-range")
          .description("Log players entering render distance")
          .defaultTo(true)
          .build();
  
  private final Setting<Provider> provider =
      getCommandStub()
          .builders()
          .<Provider>newSettingEnumBuilder()
          .name("provider")
          .description("Either Matrix or Discord")
          .defaultTo(Provider.MATRIX)
          .build();
  
  public MatrixNotifications() {
    super(Category.MISC, "WebNotify", false, "Send notifications via WebHook. Either on Discord or Matrix");
  }
  
  private boolean joined = false;
  private boolean once = false;
  private int position = 0;
  private String serverName = null;
  
  private static CloseableHttpClient createHttpClient() {
    final RequestConfig req = RequestConfig.custom()
        .setConnectTimeout(30 * 1000)
        .setConnectionRequestTimeout(30 * 1000)
        .build();
    
    if (SOCKET_FACTORY_REGISTRY == null) {
      return HttpClientBuilder.create()
          .setDefaultRequestConfig(req)
          .build();
    } else {
      return HttpClients.custom()
          .setDefaultRequestConfig(req)
          .setConnectionManager(new PoolingHttpClientConnectionManager(SOCKET_FACTORY_REGISTRY))
          .build();
    }
  }
  
  private static HttpResponse post(final String url, final JsonElement json) throws IOException {
    final Gson gson = new Gson();
    try (CloseableHttpClient client = createHttpClient()) {
      final HttpPost post = new HttpPost(url);
      StringEntity entity = new StringEntity(gson.toJson(json));
      post.setEntity(entity);
      post.setHeader("Content-type", "application/json");
      return client.execute(post);
    }
  }
  
  private static void postAsync(final String url, final JsonElement json) {
    EXECUTOR.submit(() -> {
      try {
        HttpResponse res = post(url, json);
        if (res.getStatusLine().getStatusCode() != 200 && res.getStatusLine().getStatusCode() != 204) {
          throw new Error("got response code " + res.getStatusLine().getStatusCode());
        }
      } catch (Throwable t) {
        printError("Failed to send message to url: " + t.getMessage());
        t.printStackTrace();
      }
    });
  }
  
  private static String getServerName() {
    return Optional.ofNullable(MC.getCurrentServerData())
        .map(data -> data.serverName)
        .orElse("server");
  }
  
  private static String getUriUuid() {
    return Optional.of(MC.getSession().getProfile())
        .map(GameProfile::getId)
        .map(UUID::toString)
        .map(id -> id.replaceAll("-", ""))
        .orElse(null);
  }

  public void send_notify(String message) { // this is accessible from outside
    notify(message);
  }
  
  private void notify(String message) {
    JsonObject object = new JsonObject();
    String id = getUriUuid();
    switch(provider.get()) {
      case DISCORD:
        object.addProperty("content", message);
        object.addProperty("username", MC.getSession().getUsername());
        if (!skin_server_url.get().isEmpty() && id != null)
            object.addProperty("avatar_url", skin_server_url.get() + id);
      break;
      case MATRIX:
        object.addProperty("text", message);
        object.addProperty("format", "plain");
        object.addProperty("displayName", MC.getSession().getUsername());
        if (!skin_server_url.get().isEmpty() && id != null)
            object.addProperty("avatarUrl", skin_server_url.get() + id);
    }
    postAsync(url.get(), object);
  }
  
  private void notify(String message, Object... args) {
    notify(String.format(message, args));
  }
  
  private void ping(String message, Object... args) {
    String msg = String.format(message, args);
    if (user.get().isEmpty()) {
      notify(msg);
    } else {
      switch(provider.get()) {
        case MATRIX:
          notify("@" + user.get() + " " + msg);
          break;
        case DISCORD:
          notify("<@" + user.get() + "> " + msg);
          break;
      }
      
    }
  }
  
  @Override
  protected void onEnabled() {
    joined = once = false;
    position = 0;
    
    if (url.get().isEmpty()) {
      printError("Missing url");
    }
    
    if (SOCKET_FACTORY_REGISTRY == null) {
      printError(
          "Custom socket factory has not been registered. All host SSL certificates must be trusted with the current JRE");
    }
  }

  @SubscribeEvent
  public void onEntityJoinWorld(EntityJoinWorldEvent event) {
    if (getLocalPlayer() == null) return;
    if (!log_visual_range.get()) return;
    if (EntityUtils.isPlayer(event.getEntity()) &&
        !getLocalPlayer().equals(event.getEntity()) &&
        !EntityUtils.isFakeLocalPlayer(event.getEntity())) {
      send_notify(String.format("Spotted %s", event.getEntity().getName()));
    }
  }
  
  @SubscribeEvent
  public void onTick(LocalPlayerUpdateEvent event) {
    joined = true;
    
    if (!once) {
      once = true;
      
      if (on_connected.get()) {
        BlockPos pos = getLocalPlayer().getPosition();
        if (pos.getX() != 0 && pos.getZ() != 0 && getServerName().equals("2b2t")) {
          ping("Connected to %s", getServerName());
        } else {
          notify("Connected to %s", getServerName());
        }
      }
    }
  }
  
  @SubscribeEvent
  public void onWorldUnload(WorldEvent.Unload event) {
    once = false;
    position = 0;
    
    if (MC.getCurrentServerData() != null) {
      serverName = getServerName();
    }
  }
  
  @SubscribeEvent
  public void onGuiOpened(GuiOpenEvent event) {
    if (event.getGui() instanceof GuiDisconnected && joined) {
      joined = false;
      
      if (on_disconnected.get()) {
        String reason = Optional.ofNullable(GuiDisconnected_message.get(event.getGui()))
            .map(ITextComponent::getUnformattedText)
            .orElse("");
        if (reason.isEmpty()) {
          notify("Disconnected from %s", serverName);
        } else {
          notify("Disconnected from %s. Reason: %s", serverName, reason);
        }
      }
    }
  }
  
  @SubscribeEvent
  public void onPacketRecieve(PacketEvent.Incoming.Pre event) {
    if (event.getPacket() instanceof SPacketChat) {
      SPacketChat packet = event.getPacket();
      ITextComponent comp = packet.getChatComponent();
      String text = comp.getUnformattedText();
      if (relay_chat.get() ||
          (relay_mentions.get() && text.contains(MC.getSession().getUsername()))) {
          notify(text);
      }
      if (packet.getType() == ChatType.SYSTEM) {
        if (comp.getSiblings().size() >= 2) {
          String pos_text = comp.getSiblings().get(0).getUnformattedText();
          if ("Position in queue: ".equals(pos_text)) {
            try {
              int pos = Integer.valueOf(comp.getSiblings().get(1).getUnformattedText());
              if (pos != position) {
                position = pos;
                if (on_queue_move.get() && position <= queue_notify_pos.get()) {
                  if (position == 1) {
                    ping("Position 1 in queue");
                  } else if (!relay_chat.get()) {
                    notify("Position %d in queue", position);
                  }
                }
              }
            } catch (Throwable t) {
              // ignore
            }
          }
        }
      }
    }
  }
  
  private static class AllowAllHostsVerifier implements X509HostnameVerifier {
    
    @Override
    public void verify(String host, SSLSocket ssl) throws IOException {
    }
    
    @Override
    public void verify(String host, X509Certificate cert) throws SSLException {
    }
    
    @Override
    public void verify(String host, String[] cns, String[] subjectAlts) throws SSLException {
    }
    
    @Override
    public boolean verify(String s, SSLSession sslSession) {
      return true;
    }
  }
}
