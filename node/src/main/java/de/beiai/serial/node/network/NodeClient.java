package de.beiai.serial.node.network;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import de.beiai.serial.common.Message;
import de.beiai.serial.node.service.SerialPortService;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.json.JsonObjectDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 节点端网络客户端
 */
@Slf4j
public class NodeClient {
    
    private final String serverHost;
    private final int serverPort;
    private final String nodeName;
    private final SerialPortService serialPortService;
    
    private EventLoopGroup group;
    private Channel channel;
    private ScheduledExecutorService scheduler;
    private String connectionId;
    
    public NodeClient(String serverHost, int serverPort, String nodeName, SerialPortService serialPortService) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.nodeName = nodeName;
        this.serialPortService = serialPortService;
    }
    
    public void start() {
        group = new NioEventLoopGroup();
        scheduler = Executors.newScheduledThreadPool(2);
        
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new JsonObjectDecoder());
                            pipeline.addLast(new StringDecoder(CharsetUtil.UTF_8));
                            pipeline.addLast(new StringEncoder(CharsetUtil.UTF_8));
                            pipeline.addLast(new NodeClientHandler(NodeClient.this));
                        }
                    });
            
            ChannelFuture future = bootstrap.connect(serverHost, serverPort).sync();
            channel = future.channel();
            
            // 发送连接请求
            sendConnectRequest();
            
            // 启动心跳
            startHeartbeat();
            
            log.info("节点端连接服务器成功: {}:{}", serverHost, serverPort);
            
        } catch (Exception e) {
            log.error("节点端连接服务器失败", e);
            throw new RuntimeException(e);
        }
    }
    
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (channel != null) {
            channel.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
        log.info("节点端已断开连接");
    }
    
    private void sendConnectRequest() {
        Message message = new Message(Message.MessageType.CONNECT, connectionId);
        message.setContent("NODE");
        message.setData(nodeName.getBytes());
        sendMessage(message);
    }
    
    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            Message heartbeat = new Message(Message.MessageType.HEARTBEAT, connectionId);
            sendMessage(heartbeat);
        }, 10, 30, TimeUnit.SECONDS);
    }
    
    public void sendMessage(Message message) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(JSONUtil.toJsonStr(message));
        }
    }
    
    public void handleMessage(Message message) {
        switch (message.getType()) {
            case CONNECT_ACK:
                handleConnectAck(message);
                break;
            case HEARTBEAT_ACK:
                // 心跳确认，无需处理
                break;
            case PAIR_REQUEST:
                handlePairRequest(message);
                break;
            case PAIR_RESPONSE:
                handlePairResponse(message);
                break;
            case PAIR_CONFIRM:
                handlePairConfirm(message);
                break;
            case SERIAL_OPEN:
                handleSerialOpen(message);
                break;
            case SERIAL_CLOSE:
                handleSerialClose(message);
                break;
            case SERIAL_DATA:
                handleSerialData(message);
                break;
            default:
                log.warn("未知消息类型: {}", message.getType());
        }
    }
    
    private void handleConnectAck(Message message) {
        log.info("连接确认收到");
    }
    
    private void handlePairRequest(Message message) {
        String clientName = message.getContent();
        log.info("收到配对请求: {}", clientName);
        
        // 自动接受配对请求
        Message response = new Message(Message.MessageType.PAIR_RESPONSE, connectionId);
        response.setReceiverId(message.getSenderId());
        response.setContent("true");
        sendMessage(response);
    }
    
    private void handlePairResponse(Message message) {
        boolean accepted = "true".equals(message.getContent());
        if (accepted) {
            log.info("配对成功");
            
            // 发送配对确认
            Message confirm = new Message(Message.MessageType.PAIR_CONFIRM, connectionId);
            confirm.setReceiverId(message.getSenderId());
            sendMessage(confirm);
        } else {
            log.info("配对被拒绝");
        }
    }
    
    private void handlePairConfirm(Message message) {
        log.info("配对确认完成");
    }
    
    private void handleSerialOpen(Message message) {
        String config = message.getContent();
        log.info("收到串口打开请求: {}", config);
        
        try {
            // 解析串口配置
            String[] parts = config.split(",");
            String portName = parts[0];
            int baudRate = Integer.parseInt(parts[1]);
            
            boolean success = serialPortService.openPort(portName, baudRate);
            
            // 发送状态更新
            Message statusUpdate = new Message(Message.MessageType.STATUS_UPDATE, connectionId);
            statusUpdate.setReceiverId(message.getSenderId());
            statusUpdate.setContent("SERIAL_OPEN:" + (success ? "SUCCESS" : "FAILED") + ":" + portName);
            sendMessage(statusUpdate);
            
        } catch (Exception e) {
            log.error("打开串口失败", e);
            
            Message statusUpdate = new Message(Message.MessageType.STATUS_UPDATE, connectionId);
            statusUpdate.setReceiverId(message.getSenderId());
            statusUpdate.setContent("SERIAL_OPEN:ERROR:" + e.getMessage());
            sendMessage(statusUpdate);
        }
    }
    
    private void handleSerialClose(Message message) {
        log.info("收到串口关闭请求");
        
        try {
            serialPortService.closeAllPorts();
            
            // 发送状态更新
            Message statusUpdate = new Message(Message.MessageType.STATUS_UPDATE, connectionId);
            statusUpdate.setReceiverId(message.getSenderId());
            statusUpdate.setContent("SERIAL_CLOSE:SUCCESS");
            sendMessage(statusUpdate);
            
        } catch (Exception e) {
            log.error("关闭串口失败", e);
            
            Message statusUpdate = new Message(Message.MessageType.STATUS_UPDATE, connectionId);
            statusUpdate.setReceiverId(message.getSenderId());
            statusUpdate.setContent("SERIAL_CLOSE:ERROR:" + e.getMessage());
            sendMessage(statusUpdate);
        }
    }
    
    private void handleSerialData(Message message) {
        if (message.getData() != null) {
            // 将数据写入对应的串口
            serialPortService.writeToAllPorts(message.getData());
        }
    }
    
    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
    }
    
    public String getConnectionId() {
        return connectionId;
    }
    
    public SerialPortService getSerialPortService() {
        return serialPortService;
    }
    
    /**
     * 网络连接断开时的处理
     */
    public void onDisconnected() {
        log.info("网络连接断开，暂停串口数据发送");
        // 断开后立即关闭所有打开的串口，避免长时间无人接收仍保持占用
        try {
            serialPortService.closeAllPorts();
        } catch (Exception e) {
            log.warn("断开时关闭串口异常: {}", e.getMessage());
        }
        // 可以在这里添加其他断开连接时的清理逻辑
    }
    
    /**
     * 尝试重连
     */
    public void reconnect() {
        log.info("尝试重新连接服务器...");
        // 延迟重连，避免立即重连
        scheduler.schedule(() -> {
            try {
                start();
            } catch (Exception e) {
                log.error("重连失败", e);
                // 如果重连失败，继续尝试
                reconnect();
            }
        }, 5, TimeUnit.SECONDS);
    }
    
    /**
     * 检查是否已连接
     */
    public boolean isConnected() {
        return channel != null && channel.isActive();
    }
    
    /**
     * 获取连接ID
     */
} 