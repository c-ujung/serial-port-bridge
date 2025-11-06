package de.beiai.serial.server.controller;

import de.beiai.serial.common.ConnectionInfo;
import de.beiai.serial.server.service.ConnectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Web控制器
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class WebController {
    
    @Autowired
    private ConnectionManager connectionManager;
    
    /**
     * 获取状态信息
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("nodeCount", connectionManager.getNodes().size());
        status.put("clientCount", connectionManager.getClients().size());
        status.put("pairingCount", connectionManager.getPairings().size());
        
        // 计算串口映射数量
        int mappingCount = 0;
        for (String nodeId : connectionManager.getNodes().keySet()) {
            mappingCount += connectionManager.getSerialPortMappings(nodeId).size();
        }
        status.put("mappingCount", mappingCount);
        
        return status;
    }
    
    /**
     * 获取节点列表
     */
    @GetMapping("/nodes")
    public Map<String, ConnectionInfo> getNodes() {
        return connectionManager.getNodes();
    }
    
    /**
     * 获取客户端列表
     */
    @GetMapping("/clients")
    public Map<String, ConnectionInfo> getClients() {
        return connectionManager.getClients();
    }
    
    /**
     * 获取配对信息
     */
    @GetMapping("/pairings")
    public Map<String, java.util.Set<String>> getPairings() {
        return connectionManager.getPairings();
    }
    
    /**
     * 获取串口映射信息
     */
    @GetMapping("/mappings")
    public Map<String, List<de.beiai.serial.common.SerialPortMapping>> getMappings() {
        Map<String, List<de.beiai.serial.common.SerialPortMapping>> result = new HashMap<>();
        for (String nodeId : connectionManager.getNodes().keySet()) {
            List<de.beiai.serial.common.SerialPortMapping> mappings = connectionManager.getSerialPortMappings(nodeId);
            if (!mappings.isEmpty()) {
                result.put(nodeId, mappings);
            }
        }
        return result;
    }
    
    /**
     * 强制断开连接
     */
    @PostMapping("/disconnect/{connectionId}")
    public Map<String, Object> disconnect(@PathVariable String connectionId) {
        connectionManager.removeConnection(connectionId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "连接已断开");
        return result;
    }
} 