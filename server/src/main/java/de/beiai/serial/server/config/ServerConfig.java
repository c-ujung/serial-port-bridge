package de.beiai.serial.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 服务端配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "serial.server")
public class ServerConfig {
    
    /**
     * TCP服务端口
     */
    private int tcpPort = 8888;
    
    /**
     * WebSocket端口
     */
    private int websocketPort = 8889;
    
    /**
     * HTTP服务端口
     */
    private int httpPort = 8080;
    
    /**
     * 心跳超时时间(毫秒)
     */
    private long heartbeatTimeout = 30000;
    
    /**
     * 连接超时时间(毫秒)
     */
    private long connectionTimeout = 60000;
} 