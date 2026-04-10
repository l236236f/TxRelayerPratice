package com.relayer.practice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

@Configuration
@ConfigurationProperties(prefix = "relayer")
@Data
public class RelayerConfig {

    private String rpcUrl;
    private AccountConfig account;
    private MonitorConfig monitor;

    @Bean
    public Web3j web3j() {
        return Web3j.build(new HttpService(rpcUrl));
    }

    @Data
    public static class AccountConfig {
        private String privateKeys;
        private double gasMultiplier;
    }

    @Data
    public static class MonitorConfig {
        private long scanInterval;
        private int rbfTimeoutSeconds;
        private int confirmationBlocks;
        private int maxRbfRetries = 3;
    }
}
