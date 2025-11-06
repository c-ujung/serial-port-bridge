package de.beiai.serial.client;

import de.beiai.serial.client.network.ClientNetworkManager;
import de.beiai.serial.client.service.VirtualSerialPortService;
import lombok.extern.slf4j.Slf4j;

import java.util.Scanner;

/**
 * 串口客户端主类
 */
@Slf4j
public class SerialPortClient {

    private static final String DEFAULT_SERVER_HOST = "localhost";
    private static final int DEFAULT_SERVER_PORT = 38888;
    private static final String CLIENT_NAME = "Client-" + System.getProperty("user.name", "Unknown");

    public static void main(String[] args) {
        String serverHost = DEFAULT_SERVER_HOST;
        int serverPort = DEFAULT_SERVER_PORT;

        if (args.length >= 2) {
            serverHost = args[0];
            try {
                serverPort = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                log.error("无效的端口号 '{}'。将使用默认端口 {}。", args[1], DEFAULT_SERVER_PORT);
            }
        } else if (args.length > 0) {
            log.warn("用法: java -jar your-client.jar <服务器地址> <端口号>");
            log.warn("参数不足，将使用默认配置。");
        }

        log.info("启动串口客户端: {}", CLIENT_NAME);
        log.info("连接服务器: {}:{}", serverHost, serverPort);

        // 创建虚拟串口服务
        VirtualSerialPortService virtualSerialPortService = new VirtualSerialPortService();

        // 创建网络管理器
        ClientNetworkManager networkManager = new ClientNetworkManager(serverHost, serverPort, CLIENT_NAME, virtualSerialPortService);

        // 启动网络管理器
        networkManager.start();

        // 命令行交互
        Scanner scanner = new Scanner(System.in);
        System.out.println("串口客户端已启动，输入 'help' 查看命令");

        while (true) {
            try {
                System.out.print("> ");
                String command = scanner.nextLine().trim();

                if ("quit".equals(command) || "exit".equals(command)) {
                    break;
                } else if ("help".equals(command)) {
                    showHelp();
                } else if ("list".equals(command)) {
                    networkManager.listNodes();
                } else if ("status".equals(command)) {
                    networkManager.showStatus();
                } else if ("ports".equals(command)) {
                    virtualSerialPortService.listVirtualPorts();
                } else if ("portinfo".equals(command)) {
                    virtualSerialPortService.showVirtualPortInfo();
                } else if ("guide".equals(command)) {
                    virtualSerialPortService.showVirtualPortGuide();
                } else if (command.startsWith("pair ")) {
                    String nodeId = command.substring(5);
                    networkManager.requestPair(nodeId);
                } else if (command.startsWith("unpair")) {
                    networkManager.unpair();
                } else if (command.startsWith("open ")) {
                    String[] parts = command.split("\\s+");
                    if (parts.length >= 3) {
                        String portName = parts[1];
                        int baudRate = Integer.parseInt(parts[2]);
                        networkManager.openSerialPort(portName, baudRate);
                    } else {
                        System.out.println("用法: open <端口名> <波特率>");
                    }
                } else if (command.startsWith("openall ")) {
                    String[] parts = command.split("\\s+");
                    if (parts.length >= 2) {
                        int baudRate = Integer.parseInt(parts[1]);
                        networkManager.openAllSerialPorts(baudRate);
                    } else {
                        System.out.println("用法: openall <波特率>");
                    }
                } else if (command.startsWith("map ")) {
                    String[] parts = command.split("\\s+");
                    if (parts.length >= 4) {
                        String nodePortName = parts[1];
                        String clientPortName = parts[2];
                        int baudRate = Integer.parseInt(parts[3]);
                        networkManager.createPortMapping(nodePortName, clientPortName, baudRate);
                    } else {
                        System.out.println("用法: map <节点端串口> <客户端串口> <波特率>");
                    }
                } else if (command.startsWith("unmap ")) {
                    String nodePortName = command.substring(6);
                    networkManager.removePortMapping(nodePortName);
                } else if (command.equals("mappings")) {
                    networkManager.showPortMappings();
                } else if (command.startsWith("close")) {
                    if (command.equals("close")) {
                        networkManager.closeSerialPort();
                    } else {
                        String portName = command.substring(6);
                        networkManager.closeSerialPort(portName);
                    }
                } else if (!command.isEmpty()) {
                    System.out.println("未知命令: " + command);
                }

            } catch (Exception e) {
                log.error("命令执行错误", e);
            }
        }

        // 关闭网络管理器
        networkManager.stop();
        log.info("串口客户端已关闭");
    }

    private static void showHelp() {
        System.out.println("可用命令:");
        System.out.println("  网络相关:");
        System.out.println("    list          - 列出可用节点");
        System.out.println("    status        - 显示连接状态");
        System.out.println("    pair <节点ID>  - 请求配对");
        System.out.println("    unpair        - 解除配对");
        System.out.println();
        System.out.println("  虚拟串口相关:");
        System.out.println("    ports         - 列出可用串口");
        System.out.println("    portinfo      - 显示虚拟串口信息");
        System.out.println("    guide         - 显示虚拟串口创建指南");
        System.out.println("    open <端口> <波特率> - 打开虚拟串口");
        System.out.println("    openall <波特率> - 打开所有可用串口");
        System.out.println("    close         - 关闭虚拟串口");
        System.out.println("    close <端口>  - 关闭指定端口");
        System.out.println("    map <节点端串口> <客户端串口> <波特率> - 创建串口映射");
        System.out.println("    unmap <节点端串口> - 移除串口映射");
        System.out.println("    mappings      - 显示串口映射信息");
        System.out.println();
        System.out.println("  系统相关:");
        System.out.println("    help          - 显示帮助信息");
        System.out.println("    quit/exit     - 退出程序");
        System.out.println();
        System.out.println("使用说明:");
        System.out.println("  1. 首先使用 'guide' 命令查看如何创建虚拟串口");
        System.out.println("  2. 使用外部工具创建虚拟串口对");
        System.out.println("  3. 使用 'ports' 命令查看可用串口");
        System.out.println("  4. 使用 'list' 命令查看可用节点");
        System.out.println("  5. 使用 'pair <节点ID>' 与节点配对");
        System.out.println("  6. 使用 'map <节点端串口> <客户端串口> <波特率>' 创建串口映射");
        System.out.println("  7. 使用 'mappings' 查看串口映射状态");
        System.out.println("  8. 使用 'unmap <节点端串口>' 移除串口映射");
    }
} 