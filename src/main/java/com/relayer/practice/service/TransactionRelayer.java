package com.relayer.practice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayer.practice.model.TransactionRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/**
 * 核心交易轉發服務
 * 負責從帳號池領取帳號、管理 Nonce 並將交易廣播至區塊鏈。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionRelayer {

    private final Web3j web3j;
    private final NonceManager nonceManager;
    private final AccountPool accountPool;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 發送以太坊交易 (非同步)
     */
    public CompletableFuture<String> sendTransaction(String to, BigInteger value, String data) {
        return CompletableFuture.supplyAsync(() -> {
            Credentials credentials = accountPool.getNextAccount();
            String from = credentials.getAddress();
            
            // 1. 原子領取 Nonce (帶鎖)
            nonceManager.lock(from);
            try {
                BigInteger nonce = nonceManager.getAndIncrementNonce(from);
                
                // 2. 獲取當前 Gas Price
                BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
                BigInteger gasLimit = BigInteger.valueOf(21000); 
                
                // 3. 簽名交易
                RawTransaction rawTx = RawTransaction.createTransaction(nonce, gasPrice, gasLimit, to, value, data);
                byte[] signedMessage = TransactionEncoder.signMessage(rawTx, credentials);
                String hexValue = Numeric.toHexString(signedMessage);

                // 4. 廣播交易
                EthSendTransaction response = web3j.ethSendRawTransaction(hexValue).send();

                if (response.hasError()) {
                    String msg = response.getError().getMessage();
                    log.error("Relay failed for {}:{} | Error: {}", from, nonce, msg);
                    
                    // 終端錯誤攔截
                    if (msg.toLowerCase().contains("insufficient funds")) {
                        saveAndQueueRecord(from, nonce, null, gasPrice, TransactionRecord.Status.FAILED);
                        throw new RuntimeException("Insufficient funds: Terminal failure for account " + from);
                    }
                    throw new RuntimeException("Blockchain rejected: " + msg);
                }

                String txHash = response.getTransactionHash();
                log.info("Relayed success: {} | Nonce: {} | From: {}", txHash, nonce, from);

                // 5. 保存狀態並排入監控
                saveAndQueueRecord(from, nonce, txHash, gasPrice, TransactionRecord.Status.PENDING);

                return txHash;

            } catch (Exception e) {
                log.error("Relay processing failed: {}", e.getMessage());
                throw new RuntimeException(e);
            } finally {
                nonceManager.unlock(from);
            }
        });
    }

    private void saveAndQueueRecord(String from, BigInteger nonce, String txHash, BigInteger gasPrice, TransactionRecord.Status status) {
        try {
            String slotId = from + ":" + nonce;
            TransactionRecord record = TransactionRecord.builder()
                    .from(from)
                    .nonce(nonce)
                    .latestTxHash(txHash)
                    .latestGasPrice(gasPrice)
                    .historyTxHashes(txHash != null ? Collections.singletonList(txHash) : Collections.emptyList())
                    .status(status)
                    .lastUpdatedAt(System.currentTimeMillis())
                    .build();

            redisTemplate.opsForValue().set(TransactionRecord.SLOT_PREFIX + slotId, objectMapper.writeValueAsString(record));
            
            if (status == TransactionRecord.Status.PENDING) {
                redisTemplate.opsForZSet().add(TransactionRecord.PENDING_ZSET, slotId, System.currentTimeMillis());
            }
        } catch (Exception e) {
            log.error("Persistence error for {}:{}: {}", from, nonce, e.getMessage());
        }
    }
}
