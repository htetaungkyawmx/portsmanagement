package org.sks.portsmanagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PortsmanagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(PortsmanagementApplication.class, args);
    }

}
