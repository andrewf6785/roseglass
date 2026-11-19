package dev.roseglass;

import io.netty.channel.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import java.util.function.Consumer;

/** Passive decoded-packet observer. Vanilla receives the original object exactly once. */
public final class MultiplayerPackets {
    interface Listener {
        void connected(NetworkManager manager);
        void disconnected(NetworkManager manager);
        void transition(NetworkManager manager);
        void fullChunk(NetworkManager manager, int x, int z);
    }
    private final Listener listener;
    private final Consumer<Runnable> scheduler;
    MultiplayerPackets(Listener listener) { this(listener, action -> Minecraft.getMinecraft().addScheduledTask(action)); }
    MultiplayerPackets(Listener listener, Consumer<Runnable> scheduler) { this.listener = listener; this.scheduler = scheduler; }

    @SubscribeEvent public void connected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        if (event.isLocal) return;
        final NetworkManager manager = event.manager;
        Channel channel = manager.channel();
        if (channel.eventLoop().inEventLoop()) install(manager, channel);
        else channel.eventLoop().execute(() -> install(manager, channel));
    }

    void install(NetworkManager manager, Channel channel) {
            if (channel.pipeline().get("roseglass-observer") != null) return;
            channel.pipeline().addBefore("packet_handler", "roseglass-observer", new ChannelInboundHandlerAdapter() {
                @Override public void channelRead(ChannelHandlerContext ctx, Object packet) throws Exception {
                    if (packet instanceof S01PacketJoinGame || packet instanceof S07PacketRespawn)
                        main(() -> listener.transition(manager));
                    // NetworkManager schedules vanilla packet application on the same client task queue.
                    ctx.fireChannelRead(packet);
                    if (packet instanceof S21PacketChunkData) {
                        S21PacketChunkData data = (S21PacketChunkData) packet;
                        if (data.func_149274_i() && data.getExtractedSize() != 0)
                            main(() -> listener.fullChunk(manager, data.getChunkX(), data.getChunkZ()));
                    } else if (packet instanceof S26PacketMapChunkBulk) {
                        S26PacketMapChunkBulk bulk = (S26PacketMapChunkBulk) packet;
                        main(() -> {
                            for (int i = 0; i < bulk.getChunkCount(); i++)
                                listener.fullChunk(manager, bulk.getChunkX(i), bulk.getChunkZ(i));
                        });
                    }
                }
            });
            main(() -> listener.connected(manager));
    }

    @SubscribeEvent public void disconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        if (!event.manager.isLocalChannel()) main(() -> listener.disconnected(event.manager));
    }

    private void main(Runnable action) { scheduler.accept(action); }
}
