package de.beiai.serial.server.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import de.beiai.serial.common.ConnectionInfo;
import de.beiai.serial.common.Message;
import de.beiai.serial.common.SerialPortMapping;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.ArrayList;

/**
 * 连接管理器
 */
@Slf4j
@Service
public class ConnectionManager {
    
    /**
     * 连接映射 (connectionId -> Channel)
     */
    private final Map<String, Channel> connections = new ConcurrentHashMap<>();
    
    /**
     * 连接信息映射 (connectionId -> ConnectionInfo)
     */
    private final Map<String, ConnectionInfo> connectionInfos = new ConcurrentHashMap<>();
    
    /**
     * 节点端映射 (connectionId -> ConnectionInfo)
     */
    private final Map<String, ConnectionInfo> nodes = new ConcurrentHashMap<>();
    
    /**
     * 客户端映射 (connectionId -> ConnectionInfo)
     */
    private final Map<String, ConnectionInfo> clients = new ConcurrentHashMap<>();
    
    /**
     * 配对映射 (nodeId -> Set<clientId>)
     */
    private final Map<String, java.util.Set<String>> nodeToClients = new ConcurrentHashMap<>();
    
    /**
     * 配对映射 (clientId -> Set<nodeId>)
     */
    private final Map<String, java.util.Set<String>> clientToNodes = new ConcurrentHashMap<>();
    
    /**
     * 串口映射 (nodeId -> List<SerialPortMapping>)
     */
    private final Map<String, List<SerialPortMapping>> serialPortMappings = new ConcurrentHashMap<>();
    
    /**
     * 调度器
     */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    public ConnectionManager() {
        // 启动心跳检测
        scheduler.scheduleAtFixedRate(this::checkHeartbeats, 10, 30, TimeUnit.SECONDS);
    }
    
    /**
     * 处理消息
     */
    public void handleMessage(Channel channel, Message message) {
        String connectionId = message.getSenderId();
        
        switch (message.getType()) {
            case CONNECT:
                handleConnect(channel, connectionId, message);
                break;
            case HEARTBEAT:
                handleHeartbeat(connectionId);
                break;
            case PAIR_REQUEST:
                handlePairRequest(connectionId, message);
                break;
            case PAIR_RESPONSE:
                handlePairResponse(connectionId, message);
                break;
            case PAIR_CONFIRM:
                handlePairConfirm(connectionId, message);
                break;
            case SERIAL_DATA:
                handleSerialData(connectionId, message);
                break;
            case SERIAL_OPEN:
                handleSerialOpen(connectionId, message);
                break;
            case SERIAL_CLOSE:
                handleSerialClose(connectionId, message);
                break;
            case STATUS_UPDATE:
                handleStatusUpdate(connectionId, message);
                break;
            case PORT_LIST_UPDATE:
                handlePortListUpdate(connectionId, message);
                break;
            case SERIAL_MAP:
                handleSerialMap(connectionId, message);
                break;
            case SERIAL_UNMAP:
                handleSerialUnmap(connectionId, message);
                break;
            default:
                log.warn("未知消息类型: {}", message.getType());
        }
    }
    
    /**
     * 处理连接请求
     */
    private void handleConnect(Channel channel, String connectionId, Message message) {
        String type = message.getContent();
        String name = message.getData() != null ? new String(message.getData()) : connectionId;
        
        ConnectionInfo info = new ConnectionInfo(connectionId, type, name, connectionId);
        connections.put(connectionId, channel);
        connectionInfos.put(connectionId, info);
        
        if ("NODE".equals(type)) {
            nodes.put(connectionId, info);
            nodeToClients.put(connectionId, ConcurrentHashMap.newKeySet());
            serialPortMappings.put(connectionId, new ArrayList<>());
            
            // 通知所有客户端有新节点连接
            notifyClientsOfNodeUpdate();
        } else if ("CLIENT".equals(type)) {
            clients.put(connectionId, info);
            clientToNodes.put(connectionId, ConcurrentHashMap.newKeySet());
            
            // 向新连接的客户端发送当前节点列表
            sendNodeListToClient(connectionId);
        }
        
        // 发送连接确认
        Message ack = new Message(Message.MessageType.CONNECT_ACK, "SERVER");
        ack.setReceiverId(connectionId);
        sendMessage(connectionId, ack);
        
        log.info("客户端连接成功: {} ({})", name, type);
    }
    
    /**
     * 处理心跳
     */
    private void handleHeartbeat(String connectionId) {
        ConnectionInfo info = connectionInfos.get(connectionId);
        if (info != null) {
            info.setLastHeartbeat(System.currentTimeMillis());
            
            // 发送心跳确认
            Message ack = new Message(Message.MessageType.HEARTBEAT_ACK, "SERVER");
            ack.setReceiverId(connectionId);
            sendMessage(connectionId, ack);
        }
    }
    
    /**
     * 处理配对请求
     */
    private void handlePairRequest(String connectionId, Message message) {
        String targetId = message.getReceiverId();
        ConnectionInfo source = connectionInfos.get(connectionId);
        ConnectionInfo target = connectionInfos.get(targetId);
        
        if (source == null || target == null) {
            log.warn("配对请求失败: 连接不存在");
            return;
        }
        
        // 转发配对请求
        Message forward = new Message(Message.MessageType.PAIR_REQUEST, connectionId);
        forward.setReceiverId(targetId);
        forward.setContent(source.getName());
        sendMessage(targetId, forward);
        
        log.info("转发配对请求: {} -> {}", source.getName(), target.getName());
    }
    
    /**
     * 处理配对响应
     */
    private void handlePairResponse(String connectionId, Message message) {
        String targetId = message.getReceiverId();
        boolean accepted = "true".equals(message.getContent());
        
        if (accepted) {
            // 建立配对关系
            ConnectionInfo source = connectionInfos.get(connectionId);
            ConnectionInfo target = connectionInfos.get(targetId);
            
            if (source != null && target != null) {
                source.addPairing(targetId);
                target.addPairing(connectionId);
                
                // 更新配对映射
                if ("NODE".equals(source.getType())) {
                    nodeToClients.get(connectionId).add(targetId);
                    clientToNodes.get(targetId).add(connectionId);
                } else {
                    nodeToClients.get(targetId).add(connectionId);
                    clientToNodes.get(connectionId).add(targetId);
                }
                
                log.info("配对成功: {} <-> {}", source.getName(), target.getName());
            }
        }
        
        // 转发配对响应
        Message forward = new Message(Message.MessageType.PAIR_RESPONSE, connectionId);
        forward.setReceiverId(targetId);
        forward.setContent(message.getContent());
        sendMessage(targetId, forward);
    }
    
    /**
     * 处理配对确认
     */
    private void handlePairConfirm(String connectionId, Message message) {
        String targetId = message.getReceiverId();
        
        // 转发配对确认
        Message forward = new Message(Message.MessageType.PAIR_CONFIRM, connectionId);
        forward.setReceiverId(targetId);
        sendMessage(targetId, forward);
        
        log.info("配对确认: {} -> {}", connectionInfos.get(connectionId).getName(), 
                connectionInfos.get(targetId).getName());
    }
    
    /**
     * 处理串口数据
     */
    private void handleSerialData(String connectionId, Message message) {
        ConnectionInfo source = connectionInfos.get(connectionId);
        if (source == null) {
            log.warn("源连接信息不存在: {}", connectionId);
            return;
        }
        
        // 如果是节点端发送的数据，需要根据串口映射转发
        if ("NODE".equals(source.getType())) {
            // 获取节点端发送的串口名称
            String nodePortName = message.getContent();
            if (nodePortName == null || nodePortName.trim().isEmpty()) {
                log.warn("节点端发送的数据缺少串口名称信息");
                return;
            }
            
            log.debug("节点端 {} 发送串口数据: 串口={}, 数据大小={}", 
                    connectionId, nodePortName, message.getData() != null ? message.getData().length : 0);
            
            // 查找该节点端的所有串口映射
            List<SerialPortMapping> mappings = serialPortMappings.get(connectionId);
            if (mappings != null && !mappings.isEmpty()) {
                // 根据串口名称查找对应的映射
                SerialPortMapping targetMapping = null;
                for (SerialPortMapping mapping : mappings) {
                    if (mapping.isValid() && nodePortName.equals(mapping.getNodePortName())) {
                        targetMapping = mapping;
                        break;
                    }
                }
                
                if (targetMapping != null) {
                    // 转发数据到对应的客户端虚拟串口
                    Message forward = new Message(Message.MessageType.SERIAL_DATA, connectionId);
                    forward.setReceiverId(targetMapping.getClientConnectionId());
                    forward.setContent(targetMapping.getClientPortName()); // 在content中指定目标虚拟串口
                    forward.setData(message.getData());
                    sendMessage(targetMapping.getClientConnectionId(), forward);
                    log.debug("转发数据: 节点端 {} (串口:{}) -> 客户端 {} (虚拟串口: {})", 
                            connectionId, nodePortName, targetMapping.getClientConnectionId(), targetMapping.getClientPortName());
                } else {
                    log.warn("节点端 {} 的串口 {} 没有对应的映射", connectionId, nodePortName);
                }
            } else {
                log.warn("节点端 {} 没有有效的串口映射，无法转发数据", connectionId);
            }
        } else if ("CLIENT".equals(source.getType()) && source.isPaired()) {
            // 如果是客户端发送的数据，转发到配对的节点端
            for (String pairedId : source.getPairedWith()) {
                Message forward = new Message(Message.MessageType.SERIAL_DATA, connectionId);
                forward.setReceiverId(pairedId);
                forward.setContent(message.getContent()); // 传递目标串口信息
                forward.setData(message.getData());
                sendMessage(pairedId, forward);
                log.debug("转发数据: 客户端 {} -> 节点端 {} (目标串口: {})", 
                        connectionId, pairedId, message.getContent());
            }
        } else {
            log.warn("无法处理串口数据: 源类型={}, 是否配对={}", source.getType(), source.isPaired());
        }
    }
    
    /**
     * 处理串口打开
     */
    private void handleSerialOpen(String connectionId, Message message) {
        // 查找所有配对的连接
        ConnectionInfo source = connectionInfos.get(connectionId);
        if (source != null && source.isPaired()) {
            for (String pairedId : source.getPairedWith()) {
                // 转发串口打开请求
                Message forward = new Message(Message.MessageType.SERIAL_OPEN, connectionId);
                forward.setReceiverId(pairedId);
                forward.setContent(message.getContent());
                sendMessage(pairedId, forward);
            }
        }
    }
    
    /**
     * 处理串口关闭
     */
    private void handleSerialClose(String connectionId, Message message) {
        // 查找所有配对的连接
        ConnectionInfo source = connectionInfos.get(connectionId);
        if (source != null && source.isPaired()) {
            for (String pairedId : source.getPairedWith()) {
                // 转发串口关闭请求
                Message forward = new Message(Message.MessageType.SERIAL_CLOSE, connectionId);
                forward.setReceiverId(pairedId);
                sendMessage(pairedId, forward);
            }
        }
    }
    
    /**
     * 处理状态更新
     */
    private void handleStatusUpdate(String connectionId, Message message) {
        // 查找所有配对的连接
        ConnectionInfo source = connectionInfos.get(connectionId);
        if (source != null && source.isPaired()) {
            for (String pairedId : source.getPairedWith()) {
                // 转发状态更新
                Message forward = new Message(Message.MessageType.STATUS_UPDATE, connectionId);
                forward.setReceiverId(pairedId);
                forward.setContent(message.getContent());
                sendMessage(pairedId, forward);
            }
        }
    }
    
    /**
     * 处理串口列表更新
     */
    private void handlePortListUpdate(String connectionId, Message message) {
        ConnectionInfo node = connectionInfos.get(connectionId);
        if (node != null && "NODE".equals(node.getType()) && message.getData() != null) {
            String portList = new String(message.getData());
            String[] ports = portList.split(",");
            
            // 清空现有串口列表
            node.getAvailablePorts().clear();
            
            // 添加新的串口列表
            for (String port : ports) {
                if (!port.trim().isEmpty()) {
                    node.addAvailablePort(port.trim());
                }
            }
            
            log.info("节点端 {} 串口列表更新: {}", node.getName(), node.getAvailablePorts());
            
            // 自动推送串口信息到配对的客户端
            pushPortListToPairedClients(connectionId, portList);
        }
    }
    
    /**
     * 处理串口映射
     */
    private void handleSerialMap(String connectionId, Message message) {
        // 解析映射信息
        String mappingInfo = message.getContent();
        if (mappingInfo != null) {
            try {
                // 格式: nodePortName,clientPortName,baudRate
                String[] parts = mappingInfo.split(",");
                if (parts.length >= 3) {
                    String nodePortName = parts[0];
                    String clientPortName = parts[1];
                    int baudRate = Integer.parseInt(parts[2]);
                    
                    // 创建串口映射
                    SerialPortMapping mapping = new SerialPortMapping(
                        nodePortName, 
                        clientPortName, 
                        message.getReceiverId(), // nodeId
                        connectionId, // clientId
                        baudRate
                    );
                    
                    // 添加到服务端的映射管理
                    addSerialPortMapping(message.getReceiverId(), mapping);
                    
                    log.info("串口映射创建成功: {} -> {} (波特率:{})", nodePortName, clientPortName, baudRate);
                } else {
                    log.warn("串口映射信息格式错误: {}", mappingInfo);
                }
            } catch (Exception e) {
                log.error("处理串口映射失败: {}", mappingInfo, e);
            }
        } else {
            log.warn("串口映射信息为空");
        }
    }

    /**
     * 处理串口解映射
     */
    private void handleSerialUnmap(String connectionId, Message message) {
        // 解析解映射信息
        String nodePortName = message.getContent();
        if (nodePortName != null) {
            try {
                // 移除串口映射
                removeSerialPortMapping(message.getReceiverId(), nodePortName);
                
                log.info("串口映射移除成功: {}", nodePortName);
            } catch (Exception e) {
                log.error("处理串口解映射失败", e);
            }
        }
    }
    
    /**
     * 发送消息
     */
    public void sendMessage(String connectionId, Message message) {
        Channel channel = connections.get(connectionId);
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(JSONUtil.toJsonStr(message));
        }
    }
    
    /**
     * 移除连接
     */
    public void removeConnection(String connectionId) {
        connections.remove(connectionId);
        ConnectionInfo info = connectionInfos.remove(connectionId);
        if (info != null) {
            // 设置连接状态为断开
            info.setStatus(ConnectionInfo.ConnectionStatus.DISCONNECTED);
            
            // 如果是节点端断开，通知所有客户端
            if ("NODE".equals(info.getType())) {
                notifyClientsOfNodeRemoval(connectionId);
                // 同时移除该节点下的所有串口映射
                serialPortMappings.remove(connectionId);
            }
            
            // 如果是客户端断开，清理所有节点上与该客户端相关的串口映射，并通知节点关闭串口
            if ("CLIENT".equals(info.getType())) {
                for (Map.Entry<String, List<SerialPortMapping>> entry : serialPortMappings.entrySet()) {
                    String nodeId = entry.getKey();
                    List<SerialPortMapping> mappings = entry.getValue();
                    boolean removedAny = mappings.removeIf(m -> connectionId.equals(m.getClientConnectionId()));
                    if (removedAny) {
                        log.info("客户端断开，移除节点 {} 上与其相关的串口映射，并通知关闭串口", nodeId);
                        // 通知节点关闭串口
                        Message close = new Message(Message.MessageType.SERIAL_CLOSE, "SERVER");
                        close.setReceiverId(nodeId);
                        sendMessage(nodeId, close);
                    }
                }
            }
            
            nodes.remove(connectionId);
            clients.remove(connectionId);
            nodeToClients.remove(connectionId);
            clientToNodes.remove(connectionId);
            
            // 解除所有配对
            if (info.isPaired()) {
                for (String pairedId : info.getPairedWith()) {
                    ConnectionInfo paired = connectionInfos.get(pairedId);
                    if (paired != null) {
                        paired.removePairing(connectionId);
                    }
                    
                    // 更新配对映射，添加空值检查
                    if ("NODE".equals(info.getType())) {
                        java.util.Set<String> clientSet = clientToNodes.get(pairedId);
                        if (clientSet != null) {
                            clientSet.remove(connectionId);
                        }
                    } else {
                        java.util.Set<String> nodeSet = nodeToClients.get(pairedId);
                        if (nodeSet != null) {
                            nodeSet.remove(connectionId);
                        }
                    }
                }
            }
            
            log.info("连接已移除: {}", info.getName());
        }
    }
    
    /**
     * 检查心跳
     */
    private void checkHeartbeats() {
        long now = System.currentTimeMillis();
        connectionInfos.entrySet().removeIf(entry -> {
            ConnectionInfo info = entry.getValue();
            if (now - info.getLastHeartbeat() > 60000) { // 60秒超时
                log.warn("连接心跳超时: {}", info.getName());
                // 设置连接状态为断开
                info.setStatus(ConnectionInfo.ConnectionStatus.DISCONNECTED);
                removeConnection(entry.getKey());
                return true;
            }
            return false;
        });
    }
    
    /**
     * 获取所有节点
     */
    public Map<String, ConnectionInfo> getNodes() {
        return nodes;
    }
    
    /**
     * 获取所有客户端
     */
    public Map<String, ConnectionInfo> getClients() {
        return clients;
    }
    
    /**
     * 获取配对信息
     */
    public Map<String, java.util.Set<String>> getPairings() {
        return nodeToClients;
    }
    
    /**
     * 获取节点的可用串口
     */
    public java.util.Set<String> getNodePorts(String nodeId) {
        ConnectionInfo node = nodes.get(nodeId);
        return node != null ? node.getAvailablePorts() : ConcurrentHashMap.newKeySet();
    }

    /**
     * 获取节点的串口映射
     */
    public List<SerialPortMapping> getSerialPortMappings(String nodeId) {
        return serialPortMappings.getOrDefault(nodeId, new ArrayList<>());
    }

    /**
     * 添加串口映射
     */
    public void addSerialPortMapping(String nodeId, SerialPortMapping mapping) {
        List<SerialPortMapping> mappings = serialPortMappings.computeIfAbsent(nodeId, k -> new ArrayList<>());
        mappings.add(mapping);
        log.info("添加串口映射: nodeId={}, mapping={}, 当前映射数量={}", nodeId, mapping, mappings.size());
        
        // 打印所有映射信息用于调试
        log.debug("当前所有串口映射:");
        for (Map.Entry<String, List<SerialPortMapping>> entry : serialPortMappings.entrySet()) {
            log.debug("  节点 {}: {} 个映射", entry.getKey(), entry.getValue().size());
            for (SerialPortMapping m : entry.getValue()) {
                log.debug("    {} -> {}", m.getNodePortName(), m.getClientPortName());
            }
        }
    }

    /**
     * 移除串口映射
     */
    public void removeSerialPortMapping(String nodeId, String portName) {
        List<SerialPortMapping> mappings = serialPortMappings.get(nodeId);
        if (mappings != null) {
            mappings.removeIf(mapping -> mapping.getNodePortName().equals(portName));
            log.info("移除串口映射: {} -> {}", nodeId, portName);
        }
    }

    /**
     * 通知所有客户端有新的节点连接
     */
    private void notifyClientsOfNodeUpdate() {
        for (Map.Entry<String, ConnectionInfo> clientEntry : clients.entrySet()) {
            String clientId = clientEntry.getKey();
            // 向所有客户端发送节点列表更新
            for (Map.Entry<String, ConnectionInfo> nodeEntry : nodes.entrySet()) {
                String nodeId = nodeEntry.getKey();
                ConnectionInfo nodeInfo = nodeEntry.getValue();
                
                Message message = new Message(Message.MessageType.NODE_UPDATE, "SERVER");
                message.setReceiverId(clientId);
                message.setContent("NODE_ADDED");
                // data携带: nodeId|nodeName
                message.setData((nodeId + "|" + nodeInfo.getName()).getBytes());
                sendMessage(clientId, message);
            }
        }
    }

    /**
     * 向新连接的客户端发送当前节点列表
     */
    private void sendNodeListToClient(String clientId) {
        // 向新连接的客户端发送所有节点信息
        for (Map.Entry<String, ConnectionInfo> nodeEntry : nodes.entrySet()) {
            String nodeId = nodeEntry.getKey();
            ConnectionInfo nodeInfo = nodeEntry.getValue();
            
            Message message = new Message(Message.MessageType.NODE_UPDATE, "SERVER");
            message.setReceiverId(clientId);
            message.setContent("NODE_ADDED");
            // data携带: nodeId|nodeName
            message.setData((nodeId + "|" + nodeInfo.getName()).getBytes());
            sendMessage(clientId, message);
            
            // 如果节点有串口信息，也发送串口列表
            if (!nodeInfo.getAvailablePorts().isEmpty()) {
                String portList = String.join(",", nodeInfo.getAvailablePorts());
                Message portMessage = new Message(Message.MessageType.PORT_LIST_UPDATE, "SERVER");
                portMessage.setReceiverId(clientId);
                portMessage.setData(portList.getBytes());
                sendMessage(clientId, portMessage);
            }
        }
    }

    /**
     * 通知所有客户端节点断开
     */
    private void notifyClientsOfNodeRemoval(String nodeId) {
        // 在移除节点前获取节点信息
        ConnectionInfo nodeInfo = nodes.get(nodeId);
        if (nodeInfo != null) {
            for (Map.Entry<String, ConnectionInfo> clientEntry : clients.entrySet()) {
                String clientId = clientEntry.getKey();
                ConnectionInfo clientInfo = clientEntry.getValue();
                if (clientInfo.isPaired()) {
                    for (String pairedNodeId : clientInfo.getPairedWith()) {
                        if (pairedNodeId.equals(nodeId)) {
                            Message message = new Message(Message.MessageType.NODE_UPDATE, "SERVER");
                            message.setReceiverId(clientId);
                            message.setContent("NODE_REMOVED");
                            // data携带: nodeId|nodeName
                            message.setData((nodeId + "|" + nodeInfo.getName()).getBytes());
                            sendMessage(clientId, message);
                        }
                    }
                }
            }
        }
    }

    /**
     * 自动推送串口信息到配对的客户端
     */
    private void pushPortListToPairedClients(String nodeId, String portList) {
        ConnectionInfo node = nodes.get(nodeId);
        if (node != null && node.isPaired()) {
            for (String clientId : node.getPairedWith()) {
                if (clients.containsKey(clientId)) {
                    Message message = new Message(Message.MessageType.PORT_LIST_UPDATE, "SERVER");
                    message.setReceiverId(clientId);
                    message.setData(portList.getBytes());
                    sendMessage(clientId, message);
                }
            }
        }
    }
} 