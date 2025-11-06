package de.beiai.serial.common;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionInfo {
    /**
     * 连接ID
     */
    private String connectionId;
    
    /**
     * 连接类型 (NODE/CLIENT)
     */
    private String type;
    
    /**
     * 连接名称
     */
    private String name;
    
    /**
     * 连接地址
     */
    private String address;
    
    /**
     * 连接时间
     */
    private long connectTime;
    
    /**
     * 最后心跳时间
     */
    private long lastHeartbeat;
    
    /**
     * 连接状态
     */
    private ConnectionStatus status;
    
    /**
     * 配对信息 - 支持多串口配对
     */
    private Set<String> pairedWith = ConcurrentHashMap.newKeySet();
    
    /**
     * 节点端特有的串口信息
     */
    private Set<String> availablePorts = ConcurrentHashMap.newKeySet();
    
    public enum ConnectionStatus {
        CONNECTED,      // 已连接
        DISCONNECTED,   // 已断开
        PAIRED,         // 已配对
        ERROR           // 错误
    }
    
    public ConnectionInfo(String connectionId, String type, String name, String address) {
        this.connectionId = connectionId;
        this.type = type;
        this.name = name;
        this.address = address;
        this.connectTime = System.currentTimeMillis();
        this.lastHeartbeat = System.currentTimeMillis();
        this.status = ConnectionStatus.CONNECTED;
        this.pairedWith = ConcurrentHashMap.newKeySet();
        this.availablePorts = ConcurrentHashMap.newKeySet();
    }
    
    /**
     * 添加配对
     */
    public void addPairing(String connectionId) {
        pairedWith.add(connectionId);
        if (!pairedWith.isEmpty()) {
            status = ConnectionStatus.PAIRED;
        }
    }
    
    /**
     * 移除配对
     */
    public void removePairing(String connectionId) {
        pairedWith.remove(connectionId);
        if (pairedWith.isEmpty()) {
            status = ConnectionStatus.CONNECTED;
        }
    }
    
    /**
     * 添加可用串口
     */
    public void addAvailablePort(String portName) {
        availablePorts.add(portName);
    }
    
    /**
     * 移除可用串口
     */
    public void removeAvailablePort(String portName) {
        availablePorts.remove(portName);
    }
    
    /**
     * 检查是否已配对
     */
    public boolean isPaired() {
        return !pairedWith.isEmpty();
    }
    
    /**
     * 检查是否与指定连接配对
     */
    public boolean isPairedWith(String connectionId) {
        return pairedWith.contains(connectionId);
    }
} 