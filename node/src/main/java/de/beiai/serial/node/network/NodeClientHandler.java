package de.beiai.serial.node.network;

import cn.hutool.json.JSONUtil;
import de.beiai.serial.common.Message;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;

/**
 * 节点端客户端处理器
 */
@Slf4j
public class NodeClientHandler extends ChannelInboundHandlerAdapter {
    
    private final NodeClient client;
    
    public NodeClientHandler(NodeClient client) {
        this.client = client;
    }
    
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        SocketChannel channel = (SocketChannel) ctx.channel();
        String connectionId = channel.remoteAddress().getAddress().getHostAddress() + ":" + channel.remoteAddress().getPort();
        client.setConnectionId(connectionId);
        log.info("节点端连接建立: {}", connectionId);
    }
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            String json = (String) msg;
            Message message = JSONUtil.toBean(json, Message.class);
            
            log.debug("收到消息: {}", json);
            
            // 处理消息
            client.handleMessage(message);
            
        } catch (Exception e) {
            log.error("处理消息失败", e);
        }
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("与服务器连接断开");
        
        // 通知串口服务网络连接断开
        if (client != null) {
            client.onDisconnected();
        }
        
        // 尝试重连
        if (client != null) {
            client.reconnect();
        }
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("节点端连接异常", cause);
        ctx.close();
    }
} 