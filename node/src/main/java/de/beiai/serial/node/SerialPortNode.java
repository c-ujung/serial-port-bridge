package de.beiai.serial.node;

import de.beiai.serial.node.network.NodeClient;
import de.beiai.serial.node.service.SerialPortService;
import lombok.extern.slf4j.Slf4j;

import java.util.Scanner;

/**
 * 串口节点端主类
 */
@Slf4j
public class SerialPortNode {

    private static final String DEFAULT_SERVER_HOST = "localhost";
    private static final int DEFAULT_SERVER_PORT = 38888;
    private static final String NODE_NAME = "Node-" + System.getProperty("user.name", "Unknown");

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

        log.info("启动串口节点端: {}", NODE_NAME);
        log.info("连接服务器: {}:{}", serverHost, serverPort);

        // 创建串口服务
        SerialPortService serialPortService = new SerialPortService();

        // 启动网络管理器
        NodeClient nodeClient = new NodeClient(serverHost, serverPort, NODE_NAME, serialPortService);
        nodeClient.start();

        // 设置串口服务的网络客户端引用并启动
        serialPortService.setNodeClient(nodeClient);
        serialPortService.start();

        // 启动命令行界面
        startCommandLineInterface(nodeClient, serialPortService);
    }

    private static void startCommandLineInterface(NodeClient nodeClient, SerialPortService serialPortService) {
        // 命令行交互
        Scanner scanner = new Scanner(System.in);
        System.out.println("串口节点端已启动，输入 'help' 查看命令");

        while (true) {
            try {
                System.out.print("> ");
                String command = scanner.nextLine().trim();

                if ("quit".equals(command) || "exit".equals(command)) {
                    break;
                } else if ("help".equals(command)) {
                    showHelp();
                } else if ("list".equals(command)) {
                    serialPortService.listPorts();
                } else if ("status".equals(command)) {
                    serialPortService.showStatus();
                } else if (command.startsWith("open ")) {
                    String[] parts = command.split("\\s+");
                    if (parts.length >= 3) {
                        String portName = parts[1];
                        int baudRate = Integer.parseInt(parts[2]);
                        serialPortService.openPort(portName, baudRate);
                    } else {
                        System.out.println("用法: open <端口名> <波特率>");
                    }
                } else if (command.startsWith("close ")) {
                    String portName = command.substring(6);
                    serialPortService.closePort(portName);
                } else if (!command.isEmpty()) {
                    System.out.println("未知命令: " + command);
                }

            } catch (Exception e) {
                log.error("命令执行错误", e);
            }
        }

        // 关闭客户端
        nodeClient.stop();
        log.info("串口节点端已关闭");
    }

    private static void showHelp() {
        System.out.println("可用命令:");
        System.out.println("  help          - 显示帮助信息");
        System.out.println("  list          - 列出可用串口");
        System.out.println("  status        - 显示串口状态");
        System.out.println("  open <端口> <波特率> - 打开串口");
        System.out.println("  close <端口>  - 关闭串口");
        System.out.println("  quit/exit     - 退出程序");
    }
} 