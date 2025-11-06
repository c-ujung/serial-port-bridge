package de.beiai.serial.node.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.HexUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.fazecast.jSerialComm.*;
import de.beiai.serial.common.Message;
import de.beiai.serial.common.SerialPortConfig;
import de.beiai.serial.node.network.NodeClient;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 节点端串口服务
 */
@Slf4j
public class SerialPortService {
    
    /**
     * 存储所有已打开的串口（线程安全）
     */
    private final Map<String, SerialPort> openPorts = new ConcurrentHashMap<>();
    
    /**
     * 串口配置映射
     */
    private final Map<String, SerialPortConfig> portConfigs = new ConcurrentHashMap<>();
    
    /**
     * 网络客户端引用
     */
    private NodeClient nodeClient;
    
    /**
     * 调度器
     */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    /**
     * 串口监控线程
     */
    private Thread monitorThread;
    
    public SerialPortService() {
        initGlobalSettings();
    }
    
    /**
     * 初始化全局设置
     */
    private void initGlobalSettings() {
        String appId = "EFOAI_SerialPort_Node_" + IdUtil.getSnowflakeNextIdStr();
        System.setProperty("fazecast.jSerialComm.appid", appId);
        log.info("节点端串口唯一应用ID：{}", appId);
        
        // 注册JVM关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            closeAllPorts();
            scheduler.shutdown();
            log.info("节点端全局资源已释放");
        }));
    }
    
    /**
     * 设置网络客户端引用
     */
    public void setNodeClient(NodeClient nodeClient) {
        this.nodeClient = nodeClient;
    }
    
    /**
     * 获取可使用串口名称列表
     */
    public ArrayList<String> findPorts() {
        return (ArrayList<String>) Arrays.stream(SerialPort.getCommPorts())
                .map(SerialPort::getSystemPortName)
                .collect(Collectors.toList());
    }
    
    /**
     * 添加数据监听器
     */
    private void addDataListener(SerialPort port, String portName) {
        port.addDataListener(new SerialPortDataListener() {
            @Override
            public int getListeningEvents() {
                return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
            }
            
            @Override
            public void serialEvent(SerialPortEvent event) {
                if (event.getEventType() == SerialPort.LISTENING_EVENT_DATA_AVAILABLE) {
                    SerialPort comPort = event.getSerialPort();
                    int bytesAvailable = comPort.bytesAvailable();
                    
                    if (bytesAvailable > 0) {
                        byte[] data = new byte[bytesAvailable];
                        int numRead = comPort.readBytes(data, data.length);
                        
                        if (numRead > 0) {
                            log.debug("串口数据接收|port={}|size={}|data={}", 
                                    portName, numRead, HexUtil.encodeHexStr(data));
                            
                            // 发送数据到网络，在content中包含串口名称
                            if (nodeClient != null && nodeClient.isConnected()) {
                                Message message = new Message(Message.MessageType.SERIAL_DATA, nodeClient.getConnectionId());
                                message.setContent(portName); // 在content中指定串口名称
                                message.setData(data);
                                nodeClient.sendMessage(message);
                                log.debug("串口数据已发送到网络|port={}|size={}", portName, data.length);
                            } else {
                                log.warn("无法发送串口数据，网络连接不可用|port={}|size={}", portName, data.length);
                            }
                        }
                    }
                }
            }
        });
    }
    
    /**
     * 更新串口列表到服务端
     */
    public void updatePortListToServer() {
        if (nodeClient != null) {
            try {
                // 获取所有可用串口
                ArrayList<String> allPorts = findPorts();
                String portList = String.join(",", allPorts);
                
                // 发送串口列表更新消息
                Message message = new Message(Message.MessageType.PORT_LIST_UPDATE, nodeClient.getConnectionId());
                message.setData(portList.getBytes());
                nodeClient.sendMessage(message);
                
                log.info("串口列表已更新到服务端: {}", portList);
            } catch (Exception e) {
                log.error("更新串口列表到服务端失败", e);
            }
        }
    }
    
    /**
     * 列出可用串口
     */
    public void listPorts() {
        ArrayList<String> ports = findPorts();
        System.out.println("可用串口列表:");
        for (String port : ports) {
            System.out.println("  " + port);
        }
        
        // 更新到服务端
        updatePortListToServer();
    }
    
    /**
     * 显示串口状态
     */
    public void showStatus() {
        System.out.println("串口状态:");
        for (Map.Entry<String, SerialPort> entry : openPorts.entrySet()) {
            String portName = entry.getKey();
            SerialPort port = entry.getValue();
            SerialPortConfig config = portConfigs.get(portName);
            
            System.out.println("  " + portName + ": " + 
                    (port.isOpen() ? "已打开" : "已关闭") +
                    (config != null ? " (波特率:" + config.getBaudRate() + ")" : ""));
        }
    }
    
    /**
     * 打开串口
     */
    public boolean openPort(String portName, int baudRate) {
        return openPort(portName, baudRate, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
    }
    
    /**
     * 打开串口
     */
    public synchronized boolean openPort(String portName, int baudRate, int dataBits, int stopBits, int parity) {
        if (StrUtil.isBlank(portName)) {
            return false;
        }
        
        String upperPort = portName.toUpperCase();
        SerialPort existing = openPorts.get(upperPort);
        if (existing != null && existing.isOpen()) {
            log.info("串口已打开: {}", upperPort);
            return true;
        }
        
        SerialPort port = SerialPort.getCommPort(upperPort);
        if (port == null) {
            log.error("串口不存在: {}", upperPort);
            return false;
        }
        
        try {
            port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
            port.setComPortParameters(baudRate, dataBits, stopBits, parity);
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING, 1000, 1000);
            
            if (port.openPort(1500)) {
                openPorts.put(upperPort, port);
                
                SerialPortConfig config = new SerialPortConfig(upperPort, baudRate, dataBits, stopBits, parity, 1000);
                portConfigs.put(upperPort, config);
                
                // 添加数据监听器
                addDataListener(port, upperPort);
                
                log.info("成功打开串口: {} (波特率:{})", upperPort, baudRate);
                return true;
            }
        } catch (Exception e) {
            log.error("打开串口失败: {} - {}", upperPort, e.getMessage());
        }
        
        return false;
    }
    
    /**
     * 关闭串口
     */
    public boolean closePort(String portName) {
        SerialPort port = openPorts.get(portName.toUpperCase());
        if (port != null) {
            port.removeDataListener();
            boolean result = port.closePort();
            if (result) {
                openPorts.remove(portName.toUpperCase());
                portConfigs.remove(portName.toUpperCase());
                log.info("已关闭串口: {}", portName);
            }
            return result;
        }
        return false;
    }
    
    /**
     * 关闭所有串口
     */
    public void closeAllPorts() {
        openPorts.keySet().forEach(portName -> {
            SerialPort port = openPorts.get(portName);
            if (port != null) {
                port.removeDataListener();
                port.closePort();
                log.debug("强制关闭串口: {}", portName);
            }
        });
        openPorts.clear();
        portConfigs.clear();
    }
    
    /**
     * 向所有串口写入数据
     */
    public void writeToAllPorts(byte[] data) {
        for (Map.Entry<String, SerialPort> entry : openPorts.entrySet()) {
            String portName = entry.getKey();
            SerialPort port = entry.getValue();
            
            if (port.isOpen()) {
                try {
                    int sent = port.writeBytes(data, data.length);
                    log.debug("串口数据发送|port={}|size={}|data={}", 
                            portName, sent, HexUtil.encodeHexStr(data));
                } catch (Exception e) {
                    log.error("串口数据发送失败|port={}|error={}", portName, e.getMessage());
                }
            }
        }
    }
    
    /**
     * 向指定串口写入数据
     */
    public boolean writeToPort(String portName, byte[] data) {
        SerialPort port = openPorts.get(portName.toUpperCase());
        if (port != null && port.isOpen()) {
            try {
                int sent = port.writeBytes(data, data.length);
                log.debug("串口数据发送|port={}|size={}|data={}", 
                        portName, sent, HexUtil.encodeHexStr(data));
                return sent == data.length;
            } catch (Exception e) {
                log.error("串口数据发送失败|port={}|error={}", portName, e.getMessage());
            }
        }
        return false;
    }
    
    /**
     * 启动串口服务
     * 启动时推送可用串口信息到服务端
     * 启动串口监控线程监控串口变化
     * 注意：不会自动打开串口，串口打开由客户端映射请求触发
     */
    public void start() {
        // 启动时自动推送串口信息
        pushPortListToServer();
        
        // 启动串口监控线程
        monitorThread = new Thread(this::monitorSerialPorts);
        monitorThread.setDaemon(true);
        monitorThread.start();
        
        log.info("串口服务已启动");
    }
    
    /**
     * 启动时自动推送串口信息到服务端
     */
    private void pushPortListToServer() {
        if (nodeClient != null) {
            List<String> availablePorts = findPorts();
            if (!availablePorts.isEmpty()) {
                String portList = String.join(",", availablePorts);
                Message message = new Message(Message.MessageType.PORT_LIST_UPDATE, nodeClient.getConnectionId());
                message.setData(portList.getBytes());
                nodeClient.sendMessage(message);
                log.info("启动时自动推送串口列表到服务端: {}", portList);
            }
        }
    }
    
    /**
     * 串口监控线程
     * 监控系统串口变化，但不自动打开串口
     * 串口打开由客户端映射请求触发
     */
    private void monitorSerialPorts() {
        while (true) {
            try {
                // 每5秒检查一次可用串口
                TimeUnit.SECONDS.sleep(5);
                
                // 获取所有可用串口
                List<String> allAvailablePorts = findPorts();
                
                // 检查是否有串口变化（新增或移除）
                boolean hasChanges = false;
                
                // 检查新增的串口
                for (String portName : allAvailablePorts) {
                    if (!openPorts.containsKey(portName.toUpperCase())) {
                        hasChanges = true;
                        log.info("检测到新增串口: {}", portName);
                    }
                }
                
                // 检查已移除的串口
                for (String portName : openPorts.keySet()) {
                    if (!allAvailablePorts.contains(portName.toUpperCase())) {
                        hasChanges = true;
                        log.info("检测到串口已移除: {}", portName);
                        // 关闭已移除的串口
                        closePort(portName);
                    }
                }
                
                // 如果有变化，更新串口列表到服务端
                if (hasChanges) {
                    updatePortListToServer();
                }
                
            } catch (InterruptedException e) {
                log.error("串口监控线程中断", e);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("串口监控线程异常", e);
            }
        }
    }
} 