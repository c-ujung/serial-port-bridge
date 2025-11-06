package de.beiai.serial.client.service;

import cn.hutool.core.util.HexUtil;
import com.fazecast.jSerialComm.SerialPort;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Map;

/**
 * 虚拟串口服务
 * 注意：此服务不直接创建虚拟串口，而是通过外部工具（如com0com、Virtual Serial Port Driver等）创建
 * 客户端只负责将接收到的数据转发到已存在的虚拟串口
 */
@Slf4j
public class VirtualSerialPortService {
    
    /**
     * 虚拟串口映射 (portName -> VirtualSerialPortInfo)
     */
    private final ConcurrentHashMap<String, VirtualSerialPortInfo> virtualPorts = new ConcurrentHashMap<>();
    
    /**
     * 串口映射关系 (nodePortName -> clientPortName)
     */
    private final ConcurrentHashMap<String, String> portMappings = new ConcurrentHashMap<>();
    
    /**
     * 反向映射关系 (clientPortName -> nodePortName)
     */
    private final ConcurrentHashMap<String, String> reversePortMappings = new ConcurrentHashMap<>();
    
    /**
     * 虚拟串口信息类
     */
    private static class VirtualSerialPortInfo {
        private final String portName;
        private final int baudRate;
        private final SerialPort serialPort;
        private final long createTime;
        
        public VirtualSerialPortInfo(String portName, int baudRate, SerialPort serialPort) {
            this.portName = portName;
            this.baudRate = baudRate;
            this.serialPort = serialPort;
            this.createTime = System.currentTimeMillis();
        }
        
        public String getPortName() { return portName; }
        public int getBaudRate() { return baudRate; }
        public SerialPort getSerialPort() { return serialPort; }
        public long getCreateTime() { return createTime; }
        public boolean isOpen() { return serialPort != null && serialPort.isOpen(); }
    }
    
    /**
     * 调度器
     */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    public VirtualSerialPortService() {
        // 启动串口检测任务
        scheduler.scheduleAtFixedRate(this::checkVirtualPorts, 5, 10, TimeUnit.SECONDS);
    }
    
    /**
     * 创建虚拟串口连接
     * 注意：这里假设虚拟串口已经通过外部工具创建好了
     */
    public boolean createVirtualPort(String portName, int baudRate) {
        try {
            // 检查串口是否已存在
            if (virtualPorts.containsKey(portName)) {
                log.warn("虚拟串口已存在: {}", portName);
                System.out.println("警告: 虚拟串口 " + portName + " 已存在");
                return true;
            }
            
            // 检查串口是否存在
            SerialPort[] ports = SerialPort.getCommPorts();
            boolean portExists = false;
            
            for (SerialPort port : ports) {
                if (port.getSystemPortName().equalsIgnoreCase(portName)) {
                    portExists = true;
                    break;
                }
            }
            
            if (!portExists) {
                log.error("虚拟串口不存在: {}，请先使用外部工具创建", portName);
                System.out.println("错误: 虚拟串口 " + portName + " 不存在");
                System.out.println("请使用以下工具之一创建虚拟串口:");
                System.out.println("  - com0com (Windows)");
                System.out.println("  - Virtual Serial Port Driver (Windows)");
                System.out.println("  - socat (Linux)");
                System.out.println("  - tty0tty (Linux)");
                return false;
            }
            
            // 打开串口
            SerialPort virtualPort = SerialPort.getCommPort(portName);
            virtualPort.setBaudRate(baudRate);
            virtualPort.setNumDataBits(8);
            virtualPort.setNumStopBits(1);
            virtualPort.setParity(SerialPort.NO_PARITY);
            virtualPort.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
            virtualPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING, 1000, 1000);
            
            if (virtualPort.openPort()) {
                VirtualSerialPortInfo portInfo = new VirtualSerialPortInfo(portName, baudRate, virtualPort);
                virtualPorts.put(portName, portInfo);
                
                log.info("成功连接到虚拟串口: {} (波特率:{})", portName, baudRate);
                System.out.println("成功连接到虚拟串口: " + portName + " (波特率:" + baudRate + ")");
                return true;
            } else {
                log.error("无法打开虚拟串口: {}", portName);
                System.out.println("错误: 无法打开虚拟串口 " + portName);
                return false;
            }
            
        } catch (Exception e) {
            log.error("创建虚拟串口连接失败: {} - {}", portName, e.getMessage());
            System.out.println("错误: 创建虚拟串口连接失败 - " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 关闭虚拟串口
     */
    public void closeVirtualPort() {
        virtualPorts.values().forEach(portInfo -> {
            if (portInfo.isOpen()) {
                portInfo.getSerialPort().closePort();
                log.info("已关闭虚拟串口: {}", portInfo.getPortName());
            }
        });
        virtualPorts.clear();
        log.info("所有虚拟串口已关闭");
        System.out.println("所有虚拟串口已关闭");
    }
    
    /**
     * 关闭指定虚拟串口
     */
    public boolean closeVirtualPort(String portName) {
        VirtualSerialPortInfo portInfo = virtualPorts.remove(portName);
        if (portInfo != null && portInfo.isOpen()) {
            portInfo.getSerialPort().closePort();
            log.info("虚拟串口已关闭: {}", portName);
            System.out.println("虚拟串口已关闭: " + portName);
            return true;
        }
        return false;
    }
    
    /**
     * 检查虚拟串口是否打开
     */
    public boolean isVirtualPortOpen() {
        return !virtualPorts.isEmpty() && virtualPorts.values().stream().anyMatch(VirtualSerialPortInfo::isOpen);
    }
    
    /**
     * 向虚拟串口写入数据
     */
    public void writeToVirtualPort(byte[] data) {
        for (Map.Entry<String, VirtualSerialPortInfo> entry : virtualPorts.entrySet()) {
            String portName = entry.getKey();
            VirtualSerialPortInfo portInfo = entry.getValue();
            
            if (portInfo.isOpen()) {
                try {
                    int sent = portInfo.getSerialPort().writeBytes(data, data.length);
                    log.debug("虚拟串口数据发送|port={}|size={}|data={}", 
                            portName, sent, HexUtil.encodeHexStr(data));
                } catch (Exception e) {
                    log.error("虚拟串口数据发送失败|port={}|error={}", portName, e.getMessage());
                }
            }
        }
    }
    
    /**
     * 向指定虚拟串口写入数据
     */
    public boolean writeToVirtualPort(String portName, byte[] data) {
        VirtualSerialPortInfo portInfo = virtualPorts.get(portName);
        if (portInfo != null && portInfo.isOpen()) {
            try {
                int sent = portInfo.getSerialPort().writeBytes(data, data.length);
                log.debug("虚拟串口数据发送|port={}|size={}|data={}", 
                        portName, sent, HexUtil.encodeHexStr(data));
                return sent == data.length;
            } catch (Exception e) {
                log.error("虚拟串口数据发送失败|port={}|error={}", portName, e.getMessage());
            }
        }
        return false;
    }
    
    /**
     * 从虚拟串口读取数据
     */
    public byte[] readFromVirtualPort(String portName) {
        VirtualSerialPortInfo portInfo = virtualPorts.get(portName);
        if (portInfo != null && portInfo.isOpen()) {
            int bytesAvailable = portInfo.getSerialPort().bytesAvailable();
            if (bytesAvailable > 0) {
                byte[] data = new byte[bytesAvailable];
                int numRead = portInfo.getSerialPort().readBytes(data, data.length);
                if (numRead > 0) {
                    log.debug("虚拟串口数据读取|port={}|size={}|data={}", 
                            portName, numRead, HexUtil.encodeHexStr(data));
                    return data;
                }
            }
        }
        return new byte[0];
    }
    
    /**
     * 检查虚拟串口状态
     */
    private void checkVirtualPorts() {
        virtualPorts.entrySet().removeIf(entry -> {
            String portName = entry.getKey();
            VirtualSerialPortInfo portInfo = entry.getValue();
            
            if (!portInfo.isOpen()) {
                log.warn("虚拟串口已断开: {}", portName);
                System.out.println("警告: 虚拟串口 " + portName + " 已断开");
                return true;
            }
            return false;
        });
    }
    
    /**
     * 列出可用的虚拟串口
     */
    public void listVirtualPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        System.out.println("可用串口列表:");
        
        if (ports.length == 0) {
            System.out.println("  暂无可用串口");
        } else {
            for (SerialPort port : ports) {
                String status = virtualPorts.containsKey(port.getSystemPortName()) ? "已连接" : "可用";
                System.out.println("  " + port.getSystemPortName() + " - " + status);
            }
        }
    }
    
    /**
     * 获取虚拟串口信息
     */
    public void showVirtualPortInfo() {
        System.out.println("虚拟串口信息:");
        if (virtualPorts.isEmpty()) {
            System.out.println("  暂无连接的虚拟串口");
        } else {
            for (Map.Entry<String, VirtualSerialPortInfo> entry : virtualPorts.entrySet()) {
                VirtualSerialPortInfo portInfo = entry.getValue();
                System.out.println("  " + portInfo.getPortName() + ": " + 
                        (portInfo.isOpen() ? "已连接" : "已断开") + 
                        " (波特率:" + portInfo.getBaudRate() + 
                        ", 创建时间:" + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(portInfo.getCreateTime())) + ")");
            }
        }
    }
    
    /**
     * 获取虚拟串口列表
     */
    public String[] getVirtualPortNames() {
        return virtualPorts.keySet().toArray(new String[0]);
    }
    
    /**
     * 获取虚拟串口数量
     */
    public int getVirtualPortCount() {
        return virtualPorts.size();
    }
    
    /**
     * 检查指定串口是否已连接
     */
    public boolean isPortConnected(String portName) {
        return virtualPorts.containsKey(portName);
    }
    
    /**
     * 创建串口映射
     * @param nodePortName 节点端串口名称
     * @param clientPortName 客户端虚拟串口名称
     * @param baudRate 波特率
     * @return 是否成功
     */
    public boolean createPortMapping(String nodePortName, String clientPortName, int baudRate) {
        try {
            // 检查映射是否已存在
            if (portMappings.containsKey(nodePortName)) {
                log.warn("串口映射已存在: {} -> {}", nodePortName, portMappings.get(nodePortName));
                return false;
            }
            
            if (reversePortMappings.containsKey(clientPortName)) {
                log.warn("客户端串口已被映射: {} -> {}", clientPortName, reversePortMappings.get(clientPortName));
                return false;
            }
            
            // 创建虚拟串口
            if (createVirtualPort(clientPortName, baudRate)) {
                // 建立映射关系
                portMappings.put(nodePortName, clientPortName);
                reversePortMappings.put(clientPortName, nodePortName);
                
                log.info("串口映射创建成功: {} -> {} (波特率:{})", nodePortName, clientPortName, baudRate);
                System.out.println("串口映射创建成功: " + nodePortName + " -> " + clientPortName + " (波特率:" + baudRate + ")");
                return true;
            }
            
            return false;
        } catch (Exception e) {
            log.error("创建串口映射失败: {} -> {}", nodePortName, clientPortName, e);
            return false;
        }
    }
    
    /**
     * 移除串口映射
     * @param nodePortName 节点端串口名称
     * @return 是否成功
     */
    public boolean removePortMapping(String nodePortName) {
        String clientPortName = portMappings.remove(nodePortName);
        if (clientPortName != null) {
            reversePortMappings.remove(clientPortName);
            
            // 关闭虚拟串口
            closeVirtualPort(clientPortName);
            
            log.info("串口映射已移除: {} -> {}", nodePortName, clientPortName);
            System.out.println("串口映射已移除: " + nodePortName + " -> " + clientPortName);
            return true;
        }
        return false;
    }
    
    /**
     * 获取所有串口映射
     */
    public void showPortMappings() {
        System.out.println("串口映射信息:");
        if (portMappings.isEmpty()) {
            System.out.println("  暂无串口映射");
        } else {
            for (Map.Entry<String, String> entry : portMappings.entrySet()) {
                String nodePort = entry.getKey();
                String clientPort = entry.getValue();
                VirtualSerialPortInfo portInfo = virtualPorts.get(clientPort);
                
                System.out.println("  " + nodePort + " -> " + clientPort + 
                        (portInfo != null ? " (波特率:" + portInfo.getBaudRate() + ")" : ""));
            }
        }
    }
    
    /**
     * 根据节点端串口名称获取客户端虚拟串口名称
     */
    public String getClientPortName(String nodePortName) {
        return portMappings.get(nodePortName);
    }
    
    /**
     * 根据客户端虚拟串口名称获取节点端串口名称
     */
    public String getNodePortName(String clientPortName) {
        return reversePortMappings.get(clientPortName);
    }
    
    /**
     * 获取所有映射的节点端串口名称
     */
    public java.util.Set<String> getMappedNodePorts() {
        return portMappings.keySet();
    }
    
    /**
     * 获取所有映射的客户端虚拟串口名称
     */
    public java.util.Set<String> getMappedClientPorts() {
        return reversePortMappings.keySet();
    }
    
    /**
     * 查询指定客户端虚拟串口的波特率
     */
    public Integer getBaudRateForClientPort(String clientPortName) {
        VirtualSerialPortInfo info = virtualPorts.get(clientPortName);
        return info != null ? info.getBaudRate() : null;
    }
    
    /**
     * 显示虚拟串口创建指南
     */
    public void showVirtualPortGuide() {
        System.out.println("虚拟串口创建指南:");
        System.out.println();
        System.out.println("Windows系统:");
        System.out.println("  1. 下载并安装 com0com (https://sourceforge.net/projects/com0com/)");
        System.out.println("  2. 运行 Setupc.exe");
        System.out.println("  3. 添加新的串口对，例如 COM10 <-> COM11");
        System.out.println("  4. 设置波特率、数据位等参数");
        System.out.println();
        System.out.println("Linux系统:");
        System.out.println("  1. 安装 socat: sudo apt-get install socat");
        System.out.println("  2. 创建虚拟串口对: socat -d -d PTY,link=/dev/ttyS10,mode=666 PTY,link=/dev/ttyS11,mode=666");
        System.out.println("  3. 或者使用 tty0tty: sudo apt-get install tty0tty");
        System.out.println();
        System.out.println("Mac系统:");
        System.out.println("  1. 安装 socat: brew install socat");
        System.out.println("  2. 创建虚拟串口对: socat -d -d PTY,link=/dev/tty.usbserial10 PTY,link=/dev/tty.usbserial11");
        System.out.println();
        System.out.println("创建完成后，使用 'list' 命令查看可用串口");
    }
} 