package com.relayer.practice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayer.practice.config.RelayerConfig;
import com.relayer.practice.model.TransactionRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.Optional;
import java.util.Set;

/**
 * 交易狀態監控器
 * 核心職責：追蹤 PENDING 交易、實作 RBF 代補發加速，以及確保最終一致度（Confirmation）。
 */
@Slf4j
@Service
public class TransactionMonitor {

    private final Web3j web3j;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RelayerConfig relayerConfig;
    private final AccountPool accountPool;

    // Redis Key 定義
    private static final String PENDING_SLOTS_KEY = "relayer:slots:pending";
    private static final String CONFIRMING_SLOTS_KEY = "relayer:slots:confirming";

    public TransactionMonitor(Web3j web3j, StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper, RelayerConfig relayerConfig,
                              AccountPool accountPool) {
        this.web3j = web3j;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.relayerConfig = relayerConfig;
        this.accountPool = accountPool;
    }

    /**
     * 持續監控週期
     * 每隔一段時間 (預設 10s) 掃描一次 Pending 與 Confirming 隊列。
     */
    @Scheduled(fixedDelayString = "${relayer.monitor.scan-interval:10000}")
    public void monitor() {
        log.info("Transaction monitor cycle triggered.");
        handlePendingSlots();
        handleConfirmingSlots();
    }

    /**
     * 處理待入塊 (PENDING) 的 Slot
     * 如果發現已入塊 -> 轉移至 CONFIRMING 清單。
     * 如果超時未入塊 -> 觸發 RBF 加速。
     */
    private void handlePendingSlots() {
        Set<String> slotKeys = redisTemplate.opsForZSet().range(PENDING_SLOTS_KEY, 0, -1);
        if (slotKeys == null || slotKeys.isEmpty()) return;

        for (String slotKey : slotKeys) {
            try {
                TransactionRecord record = getRecord(slotKey);
                if (record == null) continue;

                // 調用 eth_getTransactionReceipt 檢查最新 Hash 是否已入塊
                Optional<TransactionReceipt> receipt = web3j.ethGetTransactionReceipt(record.getLatestTxHash())
                        .send().getTransactionReceipt();

                if (receipt.isPresent()) {
                    log.info("Transaction in slot {}:{} MINED in block {}", record.getFrom(), record.getNonce(), receipt.get().getBlockNumber());
                    record.setStatus(TransactionRecord.Status.MINED);
                    record.setMinedBlockNumber(receipt.get().getBlockNumber());
                    record.setLastUpdatedAt(System.currentTimeMillis());
                    
                    updateRecord(slotKey, record);
                    
                    // 從 PENDING 移至 CONFIRMING 隊列
                    redisTemplate.opsForZSet().remove(PENDING_SLOTS_KEY, slotKey);
                    redisTemplate.opsForZSet().add(CONFIRMING_SLOTS_KEY, slotKey, record.getMinedBlockNumber().doubleValue());
                } else {
                    // 超時偵測：若超過 RBF Timeout 則重發
                    long pendingDuration = System.currentTimeMillis() - record.getLastUpdatedAt();
                    if (pendingDuration > relayerConfig.getMonitor().getRbfTimeoutSeconds() * 1000L) {
                        triggerSlotRBF(slotKey, record);
                    }
                }
            } catch (Exception e) {
                log.error("Monitoring failed for slot {}: {}", slotKey, e.getMessage());
            }
        }
    }

    /**
     * 觸發 Replace-By-Fee (RBF)
     * 解決方案：發送具備更高 Gas Price (1.1x) 的相同 Nonce 交易來「覆蓋」原交易。
     */
    private void triggerSlotRBF(String slotKey, TransactionRecord record) {
        try {
            log.warn("RBF Triggered: Slot {}:{} is stuck. Retrying with higher gas.", record.getFrom(), record.getNonce());
            
            Credentials credentials = accountPool.getAccountByAddress(record.getFrom());
            
            // 提升 10% Gas Price
            BigInteger newGasPrice = record.getLatestGasPrice()
                    .multiply(BigInteger.valueOf(110)).divide(BigInteger.valueOf(100));

            // 重新簽署並廣播
            RawTransaction rawTransaction = RawTransaction.createTransaction(
                    record.getNonce(), newGasPrice, BigInteger.valueOf(100000), 
                    record.getTo(), record.getValue(), record.getData());
            
            byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, credentials);
            String hexValue = Numeric.toHexString(signedMessage);

            EthSendTransaction sendResp = web3j.ethSendRawTransaction(hexValue).send();
            if (sendResp.hasError()) {
                log.error("RBF Broadcast rejected: {}", sendResp.getError().getMessage());
                return;
            }

            String newHash = sendResp.getTransactionHash();
            log.info("RBF success: New TxHash -> {}", newHash);

            // 更新 Slot 紀錄，並將新 Hash 加入歷史列表
            record.setLatestTxHash(newHash);
            record.setLatestGasPrice(newGasPrice);
            record.getHistoryTxHashes().add(newHash);
            record.setLastUpdatedAt(System.currentTimeMillis());
            
            updateRecord(slotKey, record);

        } catch (Exception e) {
            log.error("RBF Implementation error for slot {}: {}", slotKey, e.getMessage());
        }
    }

    /**
     * 處理已入塊待確認 (CONFIRMING) 的 Slot
     * 只有當「區塊確認深度」達到要求 (預設 6 個塊) 才會標記為 SUCCESS。
     */
    private void handleConfirmingSlots() {
        Set<String> slotKeys = redisTemplate.opsForZSet().range(CONFIRMING_SLOTS_KEY, 0, -1);
        if (slotKeys == null || slotKeys.isEmpty()) return;

        try {
            BigInteger currentBlock = web3j.ethBlockNumber().send().getBlockNumber();

            for (String slotKey : slotKeys) {
                TransactionRecord record = getRecord(slotKey);
                if (record == null) continue;

                BigInteger depth = currentBlock.subtract(record.getMinedBlockNumber());
                if (depth.intValue() >= relayerConfig.getMonitor().getConfirmationBlocks()) {
                    log.info("Slot FINALIZED: {}:{} (Confirmations: {})", record.getFrom(), record.getNonce(), depth);
                    record.setStatus(TransactionRecord.Status.SUCCESS);
                    record.setLastUpdatedAt(System.currentTimeMillis());
                    
                    updateRecord(slotKey, record);
                    redisTemplate.opsForZSet().remove(CONFIRMING_SLOTS_KEY, slotKey);
                }
            }
        } catch (Exception e) {
            log.error("Confirmation scan error: {}", e.getMessage());
        }
    }

    private TransactionRecord getRecord(String key) throws Exception {
        String data = redisTemplate.opsForValue().get(key);
        return data == null ? null : objectMapper.readValue(data, TransactionRecord.class);
    }

    private void updateRecord(String key, TransactionRecord record) throws Exception {
        redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(record));
    }
}
