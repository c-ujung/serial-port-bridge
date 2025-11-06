package de.beiai.serial.common;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 串口配置
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SerialPortConfig {
    /**
     * 串口名称
     */
    private String portName;
    
    /**
     * 波特率
     */
    private int baudRate = 9600;
    
    /**
     * 数据位
     */
    private int dataBits = 8;
    
    /**
     * 停止位
     */
    private int stopBits = 1;
    
    /**
     * 校验位
     */
    private int parity = 0;
    
    /**
     * 超时时间(毫秒)
     */
    private int timeout = 1000;
    
    public SerialPortConfig(String portName, int baudRate) {
        this.portName = portName;
        this.baudRate = baudRate;
    }
} 