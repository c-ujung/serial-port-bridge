package de.beiai.serial.client.network;

import cn.hutool.json.JSONUtil;
import de.beiai.serial.common.Message;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;

/**
 * 客户端网络处理器
 */
@Slf4j
public class ClientNetworkHandler extends ChannelInboundHandlerAdapter {
    
    private final ClientNetworkManager networkManager;
    
    public ClientNetworkHandler(ClientNetworkManager networkManager) {
        this.networkManager = networkManager;
    }
    
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        SocketChannel channel = (SocketChannel) ctx.channel();
        String connectionId = channel.remoteAddress().getAddress().getHostAddress() + ":" + channel.remoteAddress().getPort();
        networkManager.setConnectionId(connectionId);
        log.info("客户端连接建立: {}", connectionId);
    }
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            String json = (String) msg;
            Message message = JSONUtil.toBean(json, Message.class);
            
            log.debug("收到消息: {}", json);
            
            // 处理消息
            networkManager.handleMessage(message);
            
        } catch (Exception e) {
            log.error("处理消息失败", e);
        }
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("客户端连接断开");
        // 触发断开处理与重连
        if (networkManager != null) {
            networkManager.onDisconnected();
            networkManager.reconnect();
        }
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("客户端连接异常", cause);
        ctx.close();
    }
} 