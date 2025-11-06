package de.beiai.serial.common;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 串口映射信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SerialPortMapping {
    /**
     * 映射ID
     */
    private String mappingId;
    
    /**
     * 节点端串口名称
     */
    private String nodePortName;
    
    /**
     * 客户端虚拟串口名称
     */
    private String clientPortName;
    
    /**
     * 节点端连接ID
     */
    private String nodeConnectionId;
    
    /**
     * 客户端连接ID
     */
    private String clientConnectionId;
    
    /**
     * 波特率
     */
    private int baudRate;
    
    /**
     * 映射状态
     */
    private MappingStatus status;
    
    /**
     * 创建时间
     */
    private long createTime;
    
    /**
     * 最后活动时间
     */
    private long lastActivityTime;
    
    public enum MappingStatus {
        ACTIVE,         // 活跃
        INACTIVE,       // 非活跃
        ERROR           // 错误
    }
    
    public SerialPortMapping(String nodePortName, String clientPortName, 
                           String nodeConnectionId, String clientConnectionId, int baudRate) {
        this.mappingId = java.util.UUID.randomUUID().toString();
        this.nodePortName = nodePortName;
        this.clientPortName = clientPortName;
        this.nodeConnectionId = nodeConnectionId;
        this.clientConnectionId = clientConnectionId;
        this.baudRate = baudRate;
        this.status = MappingStatus.ACTIVE;
        this.createTime = System.currentTimeMillis();
        this.lastActivityTime = System.currentTimeMillis();
    }
    
    /**
     * 更新活动时间
     */
    public void updateActivity() {
        this.lastActivityTime = System.currentTimeMillis();
    }
    
    /**
     * 检查映射是否有效
     */
    public boolean isValid() {
        return status == MappingStatus.ACTIVE;
    }
} 