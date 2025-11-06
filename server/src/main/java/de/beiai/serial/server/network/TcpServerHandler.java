package de.beiai.serial.server.network;

import cn.hutool.json.JSONUtil;
import de.beiai.serial.common.ConnectionInfo;
import de.beiai.serial.common.Message;
import de.beiai.serial.server.service.ConnectionManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;

/**
 * TCP服务器处理器
 */
@Slf4j
public class TcpServerHandler extends ChannelInboundHandlerAdapter {
    
    private final ConnectionManager connectionManager;
    private String connectionId;
    
    public TcpServerHandler(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }
    
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        SocketChannel channel = (SocketChannel) ctx.channel();
        connectionId = channel.remoteAddress().getAddress().getHostAddress() + ":" + channel.remoteAddress().getPort();
        log.info("客户端连接: {}", connectionId);
    }
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            String json = (String) msg;
            Message message = JSONUtil.toBean(json, Message.class);
            message.setSenderId(connectionId);
            
            log.debug("收到消息: {}", json);
            
            // 处理消息
            connectionManager.handleMessage(ctx.channel(), message);
            
        } catch (Exception e) {
            log.error("处理消息失败", e);
        }
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("客户端断开连接: {}", connectionId);
        connectionManager.removeConnection(connectionId);
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("TCP连接异常", cause);
        ctx.close();
    }
} 