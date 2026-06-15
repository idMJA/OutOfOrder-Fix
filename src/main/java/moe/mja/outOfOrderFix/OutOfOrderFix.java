package moe.mja.outOfOrderFix;

import io.netty.channel.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Level;

public final class OutOfOrderFix extends JavaPlugin implements Listener {

    private static final String HANDLER_NAME = "out_of_order_fix_handler";

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("OutOfOrderFix has been enabled successfully!");
    }

    @Override
    public void onDisable() {
        // Uninject handlers for all online players
        for (Player player : getServer().getOnlinePlayers()) {
            removeChannelHandler(player);
        }
        getLogger().info("OutOfOrderFix has been disabled!");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        injectChannelHandler(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removeChannelHandler(event.getPlayer());
    }

    private void injectChannelHandler(Player player) {
        try {
            Object packetListener = getPacketListener(player);
            Channel channel = getChannel(packetListener);

            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null) {
                pipeline.remove(HANDLER_NAME);
            }

            pipeline.addBefore("packet_handler", HANDLER_NAME, new ChannelDuplexHandler() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    try {
                        if (msg.getClass().getSimpleName().equals("ServerboundKeepAlivePacket")) {
                            Object listener = getPacketListener(player);
                            if (isKeepAlivePending(listener)) {
                                long expectedId = getExpectedKeepAliveId(listener);
                                long packetId = getPacketId(msg);
                                if (packetId != expectedId) {
                                    setPacketId(msg, expectedId);
                                    getLogger().info("Fixed out-of-order keepalive response for " 
                                            + player.getName() + " (" + packetId + " -> " + expectedId + ")");
                                }
                            }
                        }
                    } catch (Throwable t) {
                        // Suppress errors during packet inspection to avoid disconnecting player
                    }
                    super.channelRead(ctx, msg);
                }
            });

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to inject Netty handler for " + player.getName(), e);
        }
    }

    private void removeChannelHandler(Player player) {
        try {
            Object packetListener = getPacketListener(player);
            Channel channel = getChannel(packetListener);
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null) {
                pipeline.remove(HANDLER_NAME);
            }
        } catch (Exception ignored) {
        }
    }

    // --- Reflection Helpers ---

    private Object getPacketListener(Player player) throws Exception {
        Method getHandle = player.getClass().getMethod("getHandle");
        Object serverPlayer = getHandle.invoke(player);
        Field connectionField = serverPlayer.getClass().getField("connection");
        return connectionField.get(serverPlayer);
    }

    private Channel getChannel(Object packetListener) throws Exception {
        // Find field of type net.minecraft.network.Connection
        Field connectionField = null;
        for (Field f : packetListener.getClass().getDeclaredFields()) {
            if (f.getType().getName().equals("net.minecraft.network.Connection")) {
                connectionField = f;
                break;
            }
        }
        if (connectionField == null) {
            // Try superclasses if any
            for (Field f : packetListener.getClass().getFields()) {
                if (f.getType().getName().equals("net.minecraft.network.Connection")) {
                    connectionField = f;
                    break;
                }
            }
        }
        if (connectionField == null) {
            throw new NoSuchFieldException("Could not find Connection field in PacketListener");
        }
        connectionField.setAccessible(true);
        Object connection = connectionField.get(packetListener);

        // Find field of type io.netty.channel.Channel in Connection
        Field channelField = null;
        for (Field f : connection.getClass().getDeclaredFields()) {
            if (f.getType().equals(Channel.class)) {
                channelField = f;
                break;
            }
        }
        if (channelField == null) {
            for (Field f : connection.getClass().getFields()) {
                if (f.getType().equals(Channel.class)) {
                    channelField = f;
                    break;
                }
            }
        }
        if (channelField == null) {
            throw new NoSuchFieldException("Could not find Channel field in Connection");
        }
        channelField.setAccessible(true);
        return (Channel) channelField.get(connection);
    }

    private long getExpectedKeepAliveId(Object packetListener) throws Exception {
        Field field = packetListener.getClass().getDeclaredField("keepAliveId");
        field.setAccessible(true);
        return field.getLong(packetListener);
    }

    private boolean isKeepAlivePending(Object packetListener) throws Exception {
        Field field = packetListener.getClass().getDeclaredField("keepAlivePending");
        field.setAccessible(true);
        return field.getBoolean(packetListener);
    }

    private long getPacketId(Object packet) throws Exception {
        Field field = packet.getClass().getDeclaredField("id");
        field.setAccessible(true);
        return field.getLong(packet);
    }

    private void setPacketId(Object packet, long newId) throws Exception {
        Field field = packet.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.setLong(packet, newId);
    }
}
