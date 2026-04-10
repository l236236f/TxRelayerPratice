package com.relayer.practice.service;

import com.relayer.practice.config.RelayerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 帳號池管理服務
 * 職責：管理多個發送私鑰，並透過輪詢（Round Robin）實現負載均衡，突破單位址 Nonce 串行限制。
 */
@Slf4j
@Service
public class AccountPool {

    private final RelayerConfig relayerConfig;
    private final Environment env;
    private final List<Credentials> credentialsPool = new ArrayList<>();
    private final AtomicInteger index = new AtomicInteger(0);

    public AccountPool(RelayerConfig relayerConfig, Environment env) {
        this.relayerConfig = relayerConfig;
        this.env = env;
    }

    /**
     * 初始化帳號池
     * 從設定檔讀取私鑰清單並轉換為 Web3j 的 Credentials 對象。
     */
    @PostConstruct
    public void init() {
        String keysStr = relayerConfig.getAccount().getPrivateKeys();
        
        // Demo 模式備援：透過 Environment 偵測命令行參數 --demo.enabled=true
        if ((keysStr == null || keysStr.isEmpty()) && "true".equals(env.getProperty("demo.enabled"))) {
            log.info("Demo Mode: Injecting default test private key via Environment check...");
            keysStr = "0x0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        }

        if (keysStr == null || keysStr.isEmpty()) {
            log.warn("Account pool is empty! Please check your configuration.");
            return;
        }

        String[] keys = keysStr.split(",");
        for (String key : keys) {
            String trimmedKey = key.trim();
            if (trimmedKey.isEmpty()) continue;
            try {
                Credentials credentials = Credentials.create(trimmedKey);
                credentialsPool.add(credentials);
                log.info("Account added to pool: {}", credentials.getAddress());
            } catch (Exception e) {
                log.error("Failed to load private key: {}", e.getMessage());
            }
        }
    }

    /**
     * 獲取下一個可用帳號 (Round Robin)
     * 利用 AtomicInteger 實現線程安全的簡單輪詢。
     */
    public Credentials getNextAccount() {
        if (credentialsPool.isEmpty()) {
            throw new RuntimeException("No accounts available in the pool");
        }
        int i = index.getAndIncrement() % credentialsPool.size();
        return credentialsPool.get(Math.abs(i));
    }

    /**
     * 根據位址獲取帳號憑證 (用於 RBF 重新簽名)
     */
    public Credentials getAccountByAddress(String address) {
        return credentialsPool.stream()
                .filter(c -> c.getAddress().equalsIgnoreCase(address))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Account not found: " + address));
    }

    /**
     * 獲取所有帳號 (用於管理員界面)
     */
    public List<String> getAllAccountAddresses() {
        List<String> addresses = new java.util.ArrayList<>();
        for (Credentials c : credentialsPool) {
            addresses.add(c.getAddress());
        }
        return addresses;
    }
}
