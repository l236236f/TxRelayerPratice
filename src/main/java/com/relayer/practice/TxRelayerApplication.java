package com.relayer.practice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TxRelayerApplication {
    public static void main(String[] args) {
        SpringApplication.run(TxRelayerApplication.class, args);
    }
}
