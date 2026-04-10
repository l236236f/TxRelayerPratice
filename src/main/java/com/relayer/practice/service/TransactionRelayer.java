package com.relayer.practice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayer.practice.config.RelayerConfig;
import com.relayer.practice.model.TransactionRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 交易轉發器 (Relayer)
 * 系統核心組件，負責將業務請求轉化為區塊鏈交易並廣播。
 * 技術亮點：Slot-based 存儲、加鎖順序廣播、Gas Price 緩衝機制。
 */
@Slf4j
@Service
public class TransactionRelayer {

    private final Web3j web3j;
    private final NonceManager nonceManager;
    private final AccountPool accountPool;
    private final StringRedisTemplate redisTemplate;
    private final RelayerConfig relayerConfig;
    private final ObjectMapper objectMapper;

    // Redis Key 模板：位址 + Nonce 組成唯一 Slot 鍵
    private static final String SLOT_RECORD_KEY = "relayer:slot:record:%s:%s"; // address:nonce
    // Redis 待監控隊列：存儲所有 Pending 狀態的 SlotKeys
    private static final String PENDING_SLOTS_KEY = "relayer:slots:pending";
    // 雜湊映射表：用於從 TxHash 反查所屬 Slot
    private static final String HASH_TO_SLOT_KEY = "relayer:hash_to_slot:%s";

    public TransactionRelayer(Web3j web3j, NonceManager nonceManager, 
                              AccountPool accountPool, StringRedisTemplate redisTemplate,
                              RelayerConfig relayerConfig, ObjectMapper objectMapper) {
        this.web3j = web3j;
        this.nonceManager = nonceManager;
        this.accountPool = accountPool;
        this.redisTemplate = redisTemplate;
        this.relayerConfig = relayerConfig;
        this.objectMapper = objectMapper;
    }

    /**
     * 發送交易 (異步入口)
     * 使用 CompletableFuture 避免阻塞 Web API 的請求線程。
     */
    public CompletableFuture<String> sendTransaction(String to, BigInteger value, String data) {
        return CompletableFuture.supplyAsync(() -> {
            // 1. 負載均衡：輪詢獲取發送帳號
            Credentials credentials = accountPool.getNextAccount();
            String from = credentials.getAddress();

            // 2. 廣播鎖：保證同地址 Nonce 廣播順序 (分佈式鎖)
            if (!nonceManager.lock(from)) {
                throw new RuntimeException("Wait for address lock: " + from);
            }

            try {
                // 3. 獲取 Nonce (原子操作)
                BigInteger nonce = nonceManager.getAndIncrementNonce(from);

                // 4. Gas 策略：獲取鏈上價格，並為 Gas Limit 增加 Buffer
                BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
                
                EthEstimateGas estimateGas = web3j.ethEstimateGas(
                        Transaction.createEthCallTransaction(from, to, data)).send();
                
                // 增加 Buffer (預設 1.1x) 防止因鏈上狀態變化導致 Out of Gas
                BigInteger gasLimit = estimateGas.getAmountUsed()
                        .multiply(BigInteger.valueOf((long) (relayerConfig.getAccount().getGasMultiplier() * 100)))
                        .divide(BigInteger.valueOf(100));

                // 5. 簽署交易
                RawTransaction rawTransaction = RawTransaction.createTransaction(
                        nonce, gasPrice, gasLimit, to, value, data);
                byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, credentials);
                String hexValue = Numeric.toHexString(signedMessage);

                // 6. 廣播交易
                EthSendTransaction ethSendTransaction = web3j.ethSendRawTransaction(hexValue).send();
                if (ethSendTransaction.hasError()) {
                    // 若獲取到 Nonce Too Low 錯誤，自動修復 Redis 快取
                    if (ethSendTransaction.getError().getMessage().contains("nonce too low")) {
                        nonceManager.syncNonceWithChain(from);
                    }
                    throw new RuntimeException("Chain Rejection: " + ethSendTransaction.getError().getMessage());
                }

                String txHash = ethSendTransaction.getTransactionHash();
                log.info("Relayed success: {} | Nonce: {} | From: {}", txHash, nonce, from);

                // 7. 保存 Slot 狀態紀錄並放入監控隊列
                saveSlot(from, nonce, txHash, gasPrice, to, value, data);
                return txHash;

            } catch (Exception e) {
                log.error("Relay failed for address {}: {}", from, e.getMessage());
                throw new RuntimeException("Relay implementation error", e);
            } finally {
                // 8. 釋放鎖
                nonceManager.unlock(from);
            }
        });
    }

    /**
     * 保存 Slot 詳情與映射
     * 將交易資訊持久化至 Redis，並掛載進 ZSet 待監控清單中。
     */
    private void saveSlot(String address, BigInteger nonce, String txHash, BigInteger gasPrice,
                          String to, BigInteger value, String data) throws Exception {
        String recordKey = String.format(SLOT_RECORD_KEY, address.toLowerCase(), nonce);
        
        TransactionRecord record = TransactionRecord.builder()
                .from(address)
                .to(to)
                .value(value)
                .data(data)
                .nonce(nonce)
                .latestTxHash(txHash)
                .latestGasPrice(gasPrice)
                .historyTxHashes(new ArrayList<>())
                .status(TransactionRecord.Status.PENDING)
                .lastUpdatedAt(System.currentTimeMillis())
                .build();
        
        record.getHistoryTxHashes().add(txHash);

        // 寫入 Slot 詳細紀錄
        redisTemplate.opsForValue().set(recordKey, objectMapper.writeValueAsString(record));
        
        // 放入 PENDING 監控 ZSet，Score 使用目前時間（方便偵測超時）
        redisTemplate.opsForZSet().add(PENDING_SLOTS_KEY, recordKey, System.currentTimeMillis());
        
        // 建立 TxHash 到 SlotKey 的索引 (映射有效期 24h)
        redisTemplate.opsForValue().set(String.format(HASH_TO_SLOT_KEY, txHash), recordKey, 24, TimeUnit.HOURS);
    }
}
