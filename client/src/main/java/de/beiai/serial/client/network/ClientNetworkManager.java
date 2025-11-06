package de.beiai.serial.client.network;

import cn.hutool.json.JSONUtil;
import de.beiai.serial.common.Message;
import de.beiai.serial.client.service.VirtualSerialPortService;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.json.JsonObjectDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 客户端网络管理器
 */
@Slf4j
public class ClientNetworkManager {
	
	private final String serverHost;
	private final int serverPort;
	private final String clientName;
	private final VirtualSerialPortService virtualSerialPortService;
	
	private EventLoopGroup group;
	private Channel channel;
	private ScheduledExecutorService scheduler;
	private String connectionId;
	
	/**
	 * 专用于重连的调度器，避免与心跳调度互相影响
	 */
	private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
	
	/**
	 * 断线后延迟清理任务，避免闪断造成误关闭
	 */
	private java.util.concurrent.ScheduledFuture<?> cleanupFuture;
	
	/**
	 * 是否正在重连中
	 */
	private volatile boolean reconnecting = false;
	
	/**
	 * 存储节点信息
	 */
	private final Map<String, String> nodes = new ConcurrentHashMap<>();
	
	/**
	 * 存储节点串口信息 (nodeId -> Set<portName>)
	 */
	private final Map<String, java.util.Set<String>> nodePorts = new ConcurrentHashMap<>();
	
	/**
	 * 当前配对的节点ID
	 */
	private String pairedNodeId;
	
	public ClientNetworkManager(String serverHost, int serverPort, String clientName, VirtualSerialPortService virtualSerialPortService) {
		this.serverHost = serverHost;
		this.serverPort = serverPort;
		this.clientName = clientName;
		this.virtualSerialPortService = virtualSerialPortService;
	}
	
	public void start() {
		group = new NioEventLoopGroup();
		scheduler = Executors.newScheduledThreadPool(2);
		
		try {
			Bootstrap bootstrap = new Bootstrap();
			bootstrap.group(group)
					.channel(NioSocketChannel.class)
					.handler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel ch) {
							ChannelPipeline pipeline = ch.pipeline();
							pipeline.addLast(new JsonObjectDecoder());
							pipeline.addLast(new StringDecoder(CharsetUtil.UTF_8));
							pipeline.addLast(new StringEncoder(CharsetUtil.UTF_8));
							pipeline.addLast(new ClientNetworkHandler(ClientNetworkManager.this));
						}
					});
			
			ChannelFuture future = bootstrap.connect(serverHost, serverPort).sync();
			channel = future.channel();
			
			// 发送连接请求
			sendConnectRequest();
			
			// 启动心跳
			startHeartbeat();
			
			log.info("客户端连接服务器成功: {}:{}", serverHost, serverPort);
			reconnecting = false;
			
		} catch (Exception e) {
			log.error("客户端连接服务器失败", e);
			// 连接失败则触发重连
			reconnect();
		}
	}
	
	public void stop() {
		try {
			if (cleanupFuture != null) {
				cleanupFuture.cancel(false);
			}
			if (scheduler != null) {
				scheduler.shutdown();
				scheduler = null;
			}
			if (channel != null) {
				channel.close();
				channel = null;
			}
			if (group != null) {
				group.shutdownGracefully();
				group = null;
			}
		} finally {
			log.info("客户端已断开连接");
		}
	}
	
	private void sendConnectRequest() {
		Message message = new Message(Message.MessageType.CONNECT, connectionId);
		message.setContent("CLIENT");
		message.setData(clientName.getBytes());
		sendMessage(message);
	}
	
	private void startHeartbeat() {
		scheduler.scheduleAtFixedRate(() -> {
			Message heartbeat = new Message(Message.MessageType.HEARTBEAT, connectionId);
			sendMessage(heartbeat);
		}, 10, 30, TimeUnit.SECONDS);
	}
	
	public boolean isConnected() {
		return channel != null && channel.isActive();
	}
	
	/**
	 * 断开回调：清理资源并启动延迟清理任务
	 */
	public void onDisconnected() {
		// 停止心跳与I/O线程，避免资源泄漏
		if (scheduler != null) {
			scheduler.shutdown();
			scheduler = null;
		}
		if (group != null) {
			group.shutdownGracefully();
			group = null;
		}
		channel = null;
		
		// 安排延迟清理：若一段时间仍未恢复，就解除配对并关闭虚拟串口
		if (cleanupFuture != null) {
			cleanupFuture.cancel(false);
		}
		cleanupFuture = reconnectExecutor.schedule(() -> {
			if (!isConnected()) {
				pairedNodeId = null;
				virtualSerialPortService.closeVirtualPort();
				log.info("长时间未重连成功，已解除配对并关闭虚拟串口");
			}
		}, 60, TimeUnit.SECONDS);
	}
	
	/**
	 * 重连逻辑：避免并发重连，失败则继续重试
	 */
	public void reconnect() {
		if (reconnecting) {
			return;
		}
		reconnecting = true;
		reconnectExecutor.schedule(() -> {
			try {
				start();
			} catch (Exception e) {
				log.error("重连失败", e);
				reconnecting = false;
				// 继续尝试重连
				reconnect();
			}
		}, 5, TimeUnit.SECONDS);
	}
	
	public void sendMessage(Message message) {
		if (channel != null && channel.isActive()) {
			channel.writeAndFlush(JSONUtil.toJsonStr(message));
		}
	}
	
	public void handleMessage(Message message) {
		switch (message.getType()) {
			case CONNECT_ACK:
				handleConnectAck(message);
				break;
			case HEARTBEAT_ACK:
				// 心跳确认，无需处理
				break;
			case PAIR_REQUEST:
				handlePairRequest(message);
				break;
			case PAIR_RESPONSE:
				handlePairResponse(message);
				break;
			case PAIR_CONFIRM:
				handlePairConfirm(message);
				break;
			case SERIAL_DATA:
				handleSerialData(message);
				break;
			case STATUS_UPDATE:
				handleStatusUpdate(message);
				break;
			case PORT_LIST_UPDATE:
				handlePortListUpdate(message);
				break;
			case NODE_UPDATE:
				handleNodeUpdate(message);
				break;
			default:
				log.warn("未知消息类型: {}", message.getType());
		}
	}
	
	private void handleConnectAck(Message message) {
		log.info("连接确认收到");
		// 自动尝试恢复配对与映射
		attemptAutoRestore();
	}
	
	private void handlePairRequest(Message message) {
		String nodeName = message.getContent();
		log.info("收到配对请求: {}", nodeName);
		
		// 自动接受配对请求
		Message response = new Message(Message.MessageType.PAIR_RESPONSE, connectionId);
		response.setReceiverId(message.getSenderId());
		response.setContent("true");
		sendMessage(response);
		
		pairedNodeId = message.getSenderId();
	}
	
	private void handlePairResponse(Message message) {
		boolean accepted = "true".equals(message.getContent());
		if (accepted) {
			log.info("配对成功");
			pairedNodeId = message.getSenderId();
			
			// 发送配对确认
			Message confirm = new Message(Message.MessageType.PAIR_CONFIRM, connectionId);
			confirm.setReceiverId(message.getSenderId());
			sendMessage(confirm);
		} else {
			log.info("配对被拒绝");
		}
	}
	
	private void handlePairConfirm(Message message) {
		log.info("配对确认完成");
		// 恢复映射与串口打开
		restoreMappingsAfterReconnect();
	}
	
	private void handleSerialData(Message message) {
		if (message.getData() != null) {
			// 将数据写入虚拟串口
			virtualSerialPortService.writeToVirtualPort(message.getContent(), message.getData());
		}
	}
	
	private void handleStatusUpdate(Message message) {
		String status = message.getContent();
		log.info("收到状态更新: {}", status);
		
		if (status.startsWith("SERIAL_OPEN:")) {
			String[] parts = status.split(":");
			if (parts.length >= 3) {
				String result = parts[1];
				String portName = parts[2];
				
				if ("SUCCESS".equals(result)) {
					log.info("串口打开成功: {}", portName);
				} else {
					log.error("串口打开失败: {}", portName);
				}
			}
		} else if (status.startsWith("SERIAL_CLOSE:")) {
			String result = status.split(":")[1];
			if ("SUCCESS".equals(result)) {
				log.info("串口关闭成功");
			} else {
				log.error("串口关闭失败");
			}
		}
	}
	
	/**
	 * 处理串口列表更新
	 */
	private void handlePortListUpdate(Message message) {
		if (message.getData() != null) {
			String portList = new String(message.getData());
			String[] ports = portList.split(",");
			
			// 更新节点串口信息
			String nodeId = message.getSenderId();
			if (nodeId == null || nodeId.equals("SERVER")) {
				// 如果发送者是SERVER，需要从消息内容中获取节点ID
				// 这里暂时使用一个默认的节点ID，实际应该从消息中解析
				nodeId = pairedNodeId;
			}
			
			if (nodeId != null) {
				java.util.Set<String> portsSet = nodePorts.computeIfAbsent(nodeId, k -> ConcurrentHashMap.newKeySet());
				portsSet.clear();
				
				for (String port : ports) {
					if (!port.trim().isEmpty()) {
						portsSet.add(port.trim());
					}
				}
				
				log.info("节点端 {} 串口列表更新: {}", nodeId, portsSet);
				System.out.println("节点端串口列表已更新: " + String.join(", ", portsSet));
			}
		}
	}
	
	/**
	 * 处理节点更新消息
	 */
	private void handleNodeUpdate(Message message) {
		if (message.getData() != null) {
			try {
				String updateType = message.getContent();
				
				if ("NODE_ADDED".equals(updateType)) {
					// 节点添加（data: nodeId|nodeName）
					String payload = new String(message.getData());
					String nodeId;
					String nodeName;
					if (payload.contains("|")) {
						String[] arr = payload.split("\\|", 2);
						nodeId = arr[0];
						nodeName = arr.length > 1 ? arr[1] : ("Node-" + nodeId.substring(0, Math.min(8, nodeId.length())));
					} else {
						nodeId = payload;
						nodeName = "Node-" + nodeId.substring(0, Math.min(8, nodeId.length()));
					}
					if (nodeId != null && !nodeId.isEmpty()) {
						nodes.put(nodeId, nodeName);
						log.info("收到节点添加: {} -> {}", nodeId, nodeName);
						System.out.println("发现新节点: " + nodeId + " -> " + nodeName);
					}
				} else if ("NODE_REMOVED".equals(updateType)) {
					// 节点移除（data: nodeId|nodeName）
					String payload = new String(message.getData());
					String nodeId = payload.contains("|") ? payload.split("\\|", 2)[0] : payload;
					if (nodeId != null && !nodeId.isEmpty()) {
						String nodeName = nodes.remove(nodeId);
						nodePorts.remove(nodeId);
						log.info("节点已断开: {} -> {}", nodeId, nodeName);
						System.out.println("节点已断开: " + nodeId + " -> " + nodeName);
						// 对已配对的节点，交由自动恢复（60秒内）逻辑处理，不立即清空pairedNodeId
					}
				}
			} catch (Exception e) {
				log.error("处理节点更新消息失败", e);
			}
		}
	}
	
	public void setConnectionId(String connectionId) {
		this.connectionId = connectionId;
	}
	
	public void listNodes() {
		System.out.println("可用节点列表:");
		if (nodes.isEmpty()) {
			System.out.println("  暂无可用节点");
		} else {
			for (Map.Entry<String, String> entry : nodes.entrySet()) {
				String nodeId = entry.getKey();
				String nodeName = entry.getValue();
				java.util.Set<String> ports = nodePorts.getOrDefault(nodeId, ConcurrentHashMap.newKeySet());
				
				System.out.println("  " + nodeId + " -> " + nodeName);
				if (!ports.isEmpty()) {
					System.out.println("    可用串口: " + String.join(", ", ports));
				}
			}
		}
	}
	
	public void showStatus() {
		System.out.println("连接状态:");
		System.out.println("  服务器: " + serverHost + ":" + serverPort);
		System.out.println("  连接ID: " + connectionId);
		System.out.println("  配对节点: " + (pairedNodeId != null ? pairedNodeId : "未配对"));
		System.out.println("  虚拟串口数量: " + virtualSerialPortService.getVirtualPortCount());
		
		if (pairedNodeId != null) {
			java.util.Set<String> ports = nodePorts.get(pairedNodeId);
			if (ports != null && !ports.isEmpty()) {
				System.out.println("  节点端可用串口: " + String.join(", ", ports));
			}
		}
	}
	
	public void requestPair(String nodeId) {
		if (pairedNodeId != null) {
			System.out.println("已与节点配对，请先解除配对");
			return;
		}
		
		Message message = new Message(Message.MessageType.PAIR_REQUEST, connectionId);
		message.setReceiverId(nodeId);
		message.setContent(clientName);
		sendMessage(message);
		
		System.out.println("发送配对请求到节点: " + nodeId);
	}
	
	public void unpair() {
		if (pairedNodeId == null) {
			System.out.println("当前未配对");
			return;
		}
		
		pairedNodeId = null;
		virtualSerialPortService.closeVirtualPort();
		System.out.println("已解除配对");
	}
	
	public void openSerialPort(String portName, int baudRate) {
		if (pairedNodeId == null) {
			System.out.println("请先与节点配对");
			return;
		}
		
		// 检查串口是否在节点端可用
		java.util.Set<String> availablePorts = nodePorts.get(pairedNodeId);
		if (availablePorts == null || !availablePorts.contains(portName)) {
			System.out.println("错误: 串口 " + portName + " 在节点端不可用");
			System.out.println("可用串口: " + (availablePorts != null ? String.join(", ", availablePorts) : "无"));
			return;
		}
		
		// 创建虚拟串口
		boolean success = virtualSerialPortService.createVirtualPort(portName, baudRate);
		if (success) {
			// 请求节点端打开串口
			Message message = new Message(Message.MessageType.SERIAL_OPEN, connectionId);
			message.setReceiverId(pairedNodeId);
			message.setContent(portName + "," + baudRate);
			sendMessage(message);
			
			System.out.println("请求打开串口: " + portName + " (波特率:" + baudRate + ")");
		} else {
			System.out.println("创建虚拟串口失败");
		}
	}
	
	/**
	 * 创建串口映射
	 * @param nodePortName 节点端串口名称
	 * @param clientPortName 客户端虚拟串口名称
	 * @param baudRate 波特率
	 */
	public void createPortMapping(String nodePortName, String clientPortName, int baudRate) {
		if (pairedNodeId == null) {
			System.out.println("请先与节点配对");
			return;
		}
		
		// 检查串口是否在节点端可用
		java.util.Set<String> availablePorts = nodePorts.get(pairedNodeId);
		if (availablePorts == null || !availablePorts.contains(nodePortName)) {
			System.out.println("错误: 串口 " + nodePortName + " 在节点端不可用");
			System.out.println("可用串口: " + (availablePorts != null ? String.join(", ", availablePorts) : "无"));
			return;
		}
		
		// 创建串口映射
		boolean success = virtualSerialPortService.createPortMapping(nodePortName, clientPortName, baudRate);
		if (success) {
			// 发送串口映射消息到服务端
			Message mapMessage = new Message(Message.MessageType.SERIAL_MAP, connectionId);
			mapMessage.setReceiverId(pairedNodeId);
			mapMessage.setContent(nodePortName + "," + clientPortName + "," + baudRate);
			sendMessage(mapMessage);
			
			// 请求节点端打开串口
			Message openMessage = new Message(Message.MessageType.SERIAL_OPEN, connectionId);
			openMessage.setReceiverId(pairedNodeId);
			openMessage.setContent(nodePortName + "," + baudRate);
			sendMessage(openMessage);
			
			System.out.println("串口映射创建成功: " + nodePortName + " -> " + clientPortName + " (波特率:" + baudRate + ")");
		} else {
			System.out.println("创建串口映射失败");
		}
	}
	
	/**
	 * 移除串口映射
	 * @param nodePortName 节点端串口名称
	 */
	public void removePortMapping(String nodePortName) {
		if (pairedNodeId == null) {
			System.out.println("请先与节点配对");
			return;
		}
		
		boolean success = virtualSerialPortService.removePortMapping(nodePortName);
		if (success) {
			// 发送串口解映射消息到服务端
			Message unmapMessage = new Message(Message.MessageType.SERIAL_UNMAP, connectionId);
			unmapMessage.setReceiverId(pairedNodeId);
			unmapMessage.setContent(nodePortName);
			sendMessage(unmapMessage);
			
			// 请求节点端关闭串口
			Message closeMessage = new Message(Message.MessageType.SERIAL_CLOSE, connectionId);
			closeMessage.setReceiverId(pairedNodeId);
			closeMessage.setContent(nodePortName);
			sendMessage(closeMessage);
			
			System.out.println("串口映射已移除: " + nodePortName);
		} else {
			System.out.println("移除串口映射失败");
		}
	}
	
	/**
	 * 显示串口映射信息
	 */
	public void showPortMappings() {
		virtualSerialPortService.showPortMappings();
	}
	
	public void openAllSerialPorts(int baudRate) {
		if (pairedNodeId == null) {
			System.out.println("请先与节点配对");
			return;
		}
		
		java.util.Set<String> availablePorts = nodePorts.get(pairedNodeId);
		if (availablePorts == null || availablePorts.isEmpty()) {
			System.out.println("节点端暂无可用串口");
			return;
		}
		
		System.out.println("正在打开所有可用串口...");
		for (String portName : availablePorts) {
			openSerialPort(portName, baudRate);
		}
	}
	
	public void closeSerialPort() {
		if (pairedNodeId == null) {
			System.out.println("当前未配对");
			return;
		}
		
		// 关闭虚拟串口
		virtualSerialPortService.closeVirtualPort();
		
		// 请求节点端关闭串口
		Message message = new Message(Message.MessageType.SERIAL_CLOSE, connectionId);
		message.setReceiverId(pairedNodeId);
		sendMessage(message);
		
		System.out.println("已关闭所有虚拟串口");
	}
	
	public void closeSerialPort(String portName) {
		if (pairedNodeId == null) {
			System.out.println("当前未配对");
			return;
		}
		
		// 关闭指定虚拟串口
		boolean success = virtualSerialPortService.closeVirtualPort(portName);
		if (success) {
			System.out.println("已关闭虚拟串口: " + portName);
		} else {
			System.out.println("虚拟串口 " + portName + " 不存在或已关闭");
		}
	}
	
	public void updateNodes(Map<String, String> nodeList) {
		nodes.clear();
		nodes.putAll(nodeList);
	}
	
	/**
	 * 获取节点只读快照
	 */
	public java.util.Map<String, String> getNodesSnapshot() {
		return new java.util.HashMap<>(nodes);
	}
	
	/**
	 * 获取指定节点的端口只读快照
	 */
	public java.util.Set<String> getNodePortsSnapshot(String nodeId) {
		return nodePorts.getOrDefault(nodeId, java.util.Collections.emptySet());
	}
	
	/**
	 * 连接恢复后，若仍有已记录的配对对象，则自动发起配对
	 */
	private void attemptAutoRestore() {
		if (pairedNodeId != null) {
			log.info("检测到断线重连，尝试自动恢复配对: {}", pairedNodeId);
			Message message = new Message(Message.MessageType.PAIR_REQUEST, connectionId);
			message.setReceiverId(pairedNodeId);
			message.setContent(clientName);
			sendMessage(message);
		}
	}
	
	/**
	 * 在配对确认后，按本地映射信息恢复服务端映射并请求节点打开相应串口
	 */
	private void restoreMappingsAfterReconnect() {
		if (pairedNodeId == null) {
			return;
		}
		try {
			java.util.Set<String> mappedNodePorts = virtualSerialPortService.getMappedNodePorts();
			if (mappedNodePorts == null || mappedNodePorts.isEmpty()) {
				return;
			}
			for (String nodePortName : mappedNodePorts) {
				String clientPortName = virtualSerialPortService.getClientPortName(nodePortName);
				if (clientPortName == null) {
					continue;
				}
				Integer baudRate = virtualSerialPortService.getBaudRateForClientPort(clientPortName);
				if (baudRate == null) {
					// 默认为上次使用的波特率未知则跳过
					continue;
				}
				// 发送串口映射
				Message mapMessage = new Message(Message.MessageType.SERIAL_MAP, connectionId);
				mapMessage.setReceiverId(pairedNodeId);
				mapMessage.setContent(nodePortName + "," + clientPortName + "," + baudRate);
				sendMessage(mapMessage);
				// 请求节点端打开串口
				Message openMessage = new Message(Message.MessageType.SERIAL_OPEN, connectionId);
				openMessage.setReceiverId(pairedNodeId);
				openMessage.setContent(nodePortName + "," + baudRate);
				sendMessage(openMessage);
				log.info("已恢复映射并请求打开: {} -> {} ({} baud)", nodePortName, clientPortName, baudRate);
			}
		} catch (Exception e) {
			log.warn("恢复映射失败: {}", e.getMessage());
		}
	}
} 