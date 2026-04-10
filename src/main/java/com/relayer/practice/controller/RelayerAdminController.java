package com.relayer.practice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayer.practice.model.TransactionRecord;
import com.relayer.practice.service.AccountPool;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 管理員監控與操作接口
 */
@RestController
@RequestMapping("/api/admin")
public class RelayerAdminController {

    private final StringRedisTemplate redisTemplate;
    private final AccountPool accountPool;
    private final ObjectMapper objectMapper;

    private static final String TX_SLOT_PREFIX = "relayer:slot:";

    public RelayerAdminController(StringRedisTemplate redisTemplate, AccountPool accountPool, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.accountPool = accountPool;
        this.objectMapper = objectMapper;
    }

    /**
     * 查詢指定交易雜湊的狀態
     * @param txHash 交易雜湊
     * @return 交易記錄物件
     */
    @GetMapping("/status/{txHash}")
    public TransactionRecord getStatus(@PathVariable String txHash) {
        try {
            String slotLabel = redisTemplate.opsForValue().get("relayer:tx_to_slot:" + txHash);
            if (slotLabel == null) {
                return null;
            }
            String json = redisTemplate.opsForValue().get(TX_SLOT_PREFIX + slotLabel);
            return json == null ? null : objectMapper.readValue(json, TransactionRecord.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get status", e);
        }
    }

    @GetMapping("/accounts")
    public Object getAccounts() {
        return accountPool.getAllAccountAddresses();
    }

    @GetMapping("/pending")
    public Set<String> getPendingSlots() {
        return redisTemplate.opsForZSet().range("relayer:pending_slots", 0, -1);
    }
}
