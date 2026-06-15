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
        for (Player player : getServer().getOnlinePlayers()) {
            removeChannelHandler(player);
        }
        getLogger().info("OutOfOrderFix has been disabled!");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        getLogger().info("Player joined: " + event.getPlayer().getName() + ". Injecting handler...");
        injectChannelHandler(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removeChannelHandler(event.getPlayer());
    }

    private void injectChannelHandler(Player player) {
        try {
            Object packetListener = getPacketListener(player);
            getLogger().info("Found packet listener class: " + packetListener.getClass().getName());
            
            Channel channel = getChannel(packetListener);
            getLogger().info("Found netty channel: " + channel.getClass().getName());

            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null) {
                pipeline.remove(HANDLER_NAME);
            }

            pipeline.addBefore("packet_handler", HANDLER_NAME, new ChannelDuplexHandler() {
                @SuppressWarnings("unchecked")
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    try {
                        String simpleName = msg.getClass().getSimpleName();
                        if (simpleName.contains("KeepAlive")) {
                            getLogger().info("Intercepted KeepAlive packet: " + msg.getClass().getName());
                            Object listener = getPacketListener(player);
                            long packetId = getPacketId(msg);
                            
                            // Find the keepAlive field
                            Field keepAliveField = getFieldByType(listener.getClass(), "io.papermc.paper.util.KeepAlive");
                            keepAliveField.setAccessible(true);
                            Object keepAliveObj = keepAliveField.get(listener);
                            
                            if (keepAliveObj != null) {
                                Field pendingQueueField = getField(keepAliveObj.getClass(), "pendingKeepAlives");
                                pendingQueueField.setAccessible(true);
                                Object pendingQueue = pendingQueueField.get(keepAliveObj);
                                
                                getLogger().info("pendingKeepAlives Queue class: " + pendingQueue.getClass().getName());
                                if (pendingQueue instanceof java.util.Collection) {
                                    java.util.Collection<?> col = (java.util.Collection<?>) pendingQueue;
                                    getLogger().info("Queue size: " + col.size());
                                    for (Object item : col) {
                                        getLogger().info("  Queue Item: " + item + " (Type: " + item.getClass().getName() + ")");
                                    }
                                } else {
                                    try {
                                        Method sizeMethod = pendingQueue.getClass().getMethod("size");
                                        int size = (Integer) sizeMethod.invoke(pendingQueue);
                                        getLogger().info("Queue size (reflected): " + size);
                                    } catch (Throwable ignored) {}
                                    try {
                                        Method toArrayMethod = pendingQueue.getClass().getMethod("toArray");
                                        Object[] arr = (Object[]) toArrayMethod.invoke(pendingQueue);
                                        for (Object item : arr) {
                                            getLogger().info("  Queue Item (reflected): " + item + " (Type: " + item.getClass().getName() + ")");
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    } catch (Throwable t) {
                        getLogger().log(Level.WARNING, "Error while inspecting KeepAlive packet for " + player.getName(), t);
                    }
                    super.channelRead(ctx, msg);
                }
            });
            getLogger().info("Successfully injected Netty handler for " + player.getName());

        } catch (Throwable e) {
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
                getLogger().info("Removed Netty handler for " + player.getName());
            }
        } catch (Exception ignored) {
        }
    }

    // --- Reflection Helpers ---

    private Field getField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field " + fieldName + " not found in " + clazz.getName());
    }

    private Field getFieldByType(Class<?> clazz, String typeClassName) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (f.getType().getName().equals(typeClassName)) {
                    return f;
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchFieldException("Field of type " + typeClassName + " not found in " + clazz.getName());
    }

    private Field getFieldByClass(Class<?> clazz, Class<?> typeClass) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (typeClass.isAssignableFrom(f.getType())) {
                    return f;
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchFieldException("Field of type " + typeClass.getName() + " not found in " + clazz.getName());
    }
    
    private Field getLongField(Class<?> clazz) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (f.getType().equals(long.class)) {
                    return f;
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchFieldException("No long field found in " + clazz.getName());
    }

    private Object getPacketListener(Player player) throws Exception {
        Method getHandle = player.getClass().getMethod("getHandle");
        Object serverPlayer = getHandle.invoke(player);
        Field connectionField = getField(serverPlayer.getClass(), "connection");
        connectionField.setAccessible(true);
        return connectionField.get(serverPlayer);
    }

    private Channel getChannel(Object packetListener) throws Exception {
        Field connectionField = getFieldByType(packetListener.getClass(), "net.minecraft.network.Connection");
        connectionField.setAccessible(true);
        Object connection = connectionField.get(packetListener);

        Field channelField = getFieldByClass(connection.getClass(), Channel.class);
        channelField.setAccessible(true);
        return (Channel) channelField.get(connection);
    }

    private long getPacketId(Object packet) throws Exception {
        Field field = getLongField(packet.getClass());
        field.setAccessible(true);
        return field.getLong(packet);
    }
}
