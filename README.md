# 工業級以太坊交易發送器 (Tx Relayer) - 企業面試優化版

本專案是一個基於 Spring Boot 的高效能、高可靠以太坊交易轉發系統。專為處理大規模、高併發的鏈上交易設計，具備自動化 Nonce 管理、RBF 加速與 Slot-based 狀態追踪等工業級特性。

---

## 🚀 核心架構亮點 (Industrial Highlights)

*   **Slot-based 交易追踪**：以 `(Address, Nonce)` 為唯一的槽位，精準追蹤 RBF 後的交易狀態。
*   **分佈式 Nonce 管理**：利用 Redis 原子操作與排他鎖，徹底杜絕多實例下的 Nonce 衝突與跳號。
*   **多帳號負載均衡**：支援 Account Pool 輪詢廣播，突破單一錢包的吞吐限制。
*   **自動化 RBF 加速**：內建監控器自動偵測超時，採取 1.1x Gas 補發策略。

---

## ⚡ 快速開始 (演示模式)

專案內置了「全隔離演示模式」，**無需外部依賴**即可運行所有核心邏輯。

### 1. 啟動服務
```bash
mvn clean spring-boot:run -Dspring-boot.run.arguments="--demo.enabled=true"
```

### 2. 演示路徑
啟動後觀察日誌，您將看到：`Nonce Sync -> Sent -> Mined -> Finalized (6 Confirmations)`。

---

## 🔬 技術深度解析 (Technical Deep Dive)

### 1. 高併發 Nonce 管理 (Redis Atomic Management)
在分佈式場景下，若多個線程同時領取代發 Nonce，極易導致重複。
*   **方案**：使用 Redis `INCR` 代替數據庫查詢，確保 Nonce 獲取是原子性的。
*   **安全性**：配合 Redis 分佈式鎖 (`SETNX`) 在廣播期間鎖定位址，保證本地與鏈上狀態的嚴格連續。

### 2. Slot-based 交易狀態機
傳統以 TxHash 為主鍵的系統，一旦發生 RBF（代補發加速），舊 Hash 會失效，導致追蹤斷裂。
*   **方案**：我們引入 **Slot (槽位)** 概念。一個 Slot 代表一個確定的 `(From, Nonce)`。
*   **優勢**：無論加速了幾次（產生了幾個不同的 Hash），系統始終追蹤同一個 Slot 的最終歸宿，直到受法律保護的區塊高度（Finality）。

### 3. 可靠性設計：Gas Buffer 與 RBF
區塊鏈網路瞬息萬變， Gas 估量不足或網路擁堵是常態。
*   **Gas Buffer**：每筆交易在估算值上額外疊加 10% 緩衝，確保「基本盤」穩定。
*   **RBF 自動代補發**：當監控器偵測到 Slot 處於 `PENDING` 過久，會自動讀取當前鏈上最優 Gas Price 並重新簽名廣播。

### 4. 工程化實踐：隔離測試架構
為了展示專業的工程能力，專案實作了 Mock 控制層：
*   **物理隔離**：Mock 了 `RedisConnectionFactory`，徹底杜絕物理連線嘗試。
*   **行為模擬**：完全模擬了 Web3j 的 JSON-RPC 互動 lifecycle，包括區塊增長與狀態變更。

---

## 🛠️ API 測試範例

*   **發送交易 (POST /api/relayer/send)**：傳入 `to`, `value`, `data` 進行轉發。
*   **查詢詳情 (GET /api/admin/status/{txHash})**：查看 Slot 完整歷史。
*   **可用帳號 (GET /api/admin/accounts)**：查看當前管理中的帳號池。
