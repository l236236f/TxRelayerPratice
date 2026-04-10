package com.relayer.practice.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 交易 Slot 紀錄模型
 * 用於追踪特定位址與 Nonce 的交易發送詳情。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionRecord {

    // Redis Key 規範
    public static final String SLOT_PREFIX = "relayer:slot:record:";
    public static final String PENDING_ZSET = "relayer:slots:pending";

    /**
     * 交易狀態枚舉
     */
    public enum Status {
        PENDING,    // 交易已廣播，等待入塊
        MINED,      // 交易已入塊，等待足夠的區塊確認
        SUCCESS,    // 交易已確認，達到最終一致性
        FAILED      // 交易失敗 (如 Revert 或 被覆蓋後遺失)
    }

    private String from;            // 發送者
    private String to;              // 接收者
    private BigInteger value;       // 金額 (Wei)
    private String data;            // 數據
    private BigInteger nonce;       // Nonce
    
    private String latestTxHash;    // 當前最新嘗試的交易 Hash (RBF 後會更新)
    private BigInteger latestGasPrice; // 當前最新嘗試使用的 Gas Price
    private int retryCount; // 已執行 RBF 的次數
    
    @Builder.Default
    private List<String> historyTxHashes = new ArrayList<>(); // 該 Slot 所有的 TxHashes (包含被覆蓋的)
    
    private Status status;          // 當前狀態
    private BigInteger minedBlockNumber; // 入塊時的區塊高度
    private long lastUpdatedAt;     // 最後更新時間戳 (用於偵測 RBF 超時)
}
