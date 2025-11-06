package de.beiai.serial.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 串口管理服务端启动类
 */
@SpringBootApplication
public class SerialPortServerApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(SerialPortServerApplication.class, args);
    }
} 