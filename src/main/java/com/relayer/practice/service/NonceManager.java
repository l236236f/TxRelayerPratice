package com.relayer.practice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;

import java.math.BigInteger;
import java.util.concurrent.TimeUnit;

/**
 * 分散式 Nonce 管理器
 * 核心解決方案：使用 Redis 原子操作確保 Nonce 唯一性，並配合分佈式鎖解決廣播順序問題。
 */
@Slf4j
@Service
public class NonceManager {

    private final StringRedisTemplate redisTemplate;
    private final Web3j web3j;
    
    // Redis Key 模板
    private static final String NONCE_KEY_PREFIX = "relayer:nonce:";
    private static final String LOCK_KEY_PREFIX = "relayer:lock:";

    public NonceManager(StringRedisTemplate redisTemplate, Web3j web3j) {
        this.redisTemplate = redisTemplate;
        this.web3j = web3j;
    }

    /**
     * 獲取位址級別的分佈式鎖
     * 目的：防止 Nonce N+1 的交易比 Nonce N 先到達節點分散式系統，導致交易被拒絕或卡住。
     * @param address 錢包位址
     * @return 是否成功鎖定
     */
    public boolean lock(String address) {
        String key = LOCK_KEY_PREFIX + address.toLowerCase();
        // 設定 10 秒過期，防止開發實例當機導致鎖死（Deadlock）
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "locked", 10, TimeUnit.SECONDS));
    }

    /**
     * 釋放位址鎖
     */
    public void unlock(String address) {
        String key = LOCK_KEY_PREFIX + address.toLowerCase();
        redisTemplate.delete(key);
    }

    /**
     * 獲取並遞增下一個可用 Nonce
     * 使用 Redis INCR 確保在多實例併發下不會拿到重複的 Nonce。
     */
    public BigInteger getAndIncrementNonce(String address) {
        String key = NONCE_KEY_PREFIX + address.toLowerCase();
        
        // 如果 Redis 中沒資料，則從區塊鏈同步
        if (Boolean.FALSE.equals(redisTemplate.hasKey(key))) {
            syncNonceWithChain(address);
        }

        Long nonce = redisTemplate.opsForValue().increment(key);
        if (nonce == null) throw new RuntimeException("Redis connection error during nonce increment");
        
        // INCR 回傳的是加 1 後的值，減 1 才是本次該使用的 Nonce
        return BigInteger.valueOf(nonce - 1);
    }

    /**
     * 從區塊鏈同步位址的 Pending Nonce
     * 呼叫 eth_getTransactionCount(..., "pending") 以獲取包含內存池中交易的最新 Nonce。
     */
    public void syncNonceWithChain(String address) {
        try {
            log.info("Syncing nonce with chain for address: {}", address);
            EthGetTransactionCount ethGetTransactionCount = web3j.ethGetTransactionCount(
                    address, DefaultBlockParameterName.PENDING).send();
            
            BigInteger nonce = ethGetTransactionCount.getTransactionCount();
            String key = NONCE_KEY_PREFIX + address.toLowerCase();
            
            redisTemplate.opsForValue().set(key, nonce.toString());
            log.info("Nonce synced for {}: {}", address, nonce);
        } catch (Exception e) {
            log.error("Failed to sync nonce for address {}: {}", address, e.getMessage());
            throw new RuntimeException("Blockchain connection error during synchronization", e);
        }
    }

    /**
     * 手動修正 Redis 中的 Nonce（通常用於緊急故障修復）
     */
    public void setNonce(String address, BigInteger nonce) {
        String key = NONCE_KEY_PREFIX + address.toLowerCase();
        redisTemplate.opsForValue().set(key, nonce.toString());
    }
}
