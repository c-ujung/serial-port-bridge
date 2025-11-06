package de.beiai.serial.common;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 网络传输消息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Message {
    /**
     * 消息类型
     */
    private MessageType type;
    
    /**
     * 消息ID
     */
    private String messageId;
    
    /**
     * 发送方ID
     */
    private String senderId;
    
    /**
     * 接收方ID
     */
    private String receiverId;
    
    /**
     * 消息内容
     */
    private String content;
    
    /**
     * 二进制数据
     */
    private byte[] data;
    
    /**
     * 时间戳
     */
    private long timestamp;
    
    public Message(MessageType type, String senderId) {
        this.type = type;
        this.senderId = senderId;
        this.timestamp = System.currentTimeMillis();
        this.messageId = java.util.UUID.randomUUID().toString();
    }
    
    public enum MessageType {
        // 连接相关
        CONNECT,           // 连接请求
        CONNECT_ACK,       // 连接确认
        DISCONNECT,        // 断开连接
        
        // 心跳相关
        HEARTBEAT,         // 心跳
        HEARTBEAT_ACK,     // 心跳确认
        
        // 配对相关
        PAIR_REQUEST,      // 配对请求
        PAIR_RESPONSE,     // 配对响应
        PAIR_CONFIRM,      // 配对确认
        
        // 串口相关
        SERIAL_DATA,       // 串口数据
        SERIAL_OPEN,       // 打开串口
        SERIAL_CLOSE,      // 关闭串口
        SERIAL_CONFIG,     // 串口配置
        SERIAL_MAP,        // 串口映射
        SERIAL_UNMAP,      // 取消串口映射
        
        // 状态相关
        STATUS_UPDATE,     // 状态更新
        PORT_LIST_UPDATE,  // 串口列表更新
        NODE_UPDATE,       // 节点信息更新
        ERROR              // 错误消息
    }
} 