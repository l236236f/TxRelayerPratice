package com.relayer.practice.config;

import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.protocol.core.Request;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 這是為了面試展示開發的「全模擬配置」。
 * 當啟動參數中包含 --demo.enabled=true 時，將使用記憶體 Mock 取代 Redis 與 Web3j。
 * 我們採取「工廠級攔截」，連連線工廠都 Mock 掉，徹底杜絕 localhost 連線嘗試。
 */
@Configuration
@ConditionalOnProperty(name = "demo.enabled", havingValue = "true")
@SuppressWarnings("rawtypes")
public class DemoConfig {

    static {
        // 強制關閉嵌入式 Redis 啟動嘗試與其他自動配置干擾
        System.setProperty("spring.redis.embedded.enabled", "false");
        System.setProperty("spring.autoconfigure.exclude", 
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
    }

    @Bean
    @Primary
    public RedisConnectionFactory mockConnectionFactory() {
        RedisConnectionFactory factory = Mockito.mock(RedisConnectionFactory.class);
        RedisConnection connection = Mockito.mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(connection);
        return factory;
    }

    @Bean
    @Primary
    @SuppressWarnings("unchecked")
    public StringRedisTemplate mockRedisTemplate(RedisConnectionFactory factory) {
        // 直接創建一個不帶真實連線的模版
        StringRedisTemplate template = Mockito.mock(StringRedisTemplate.class);
        
        ConcurrentHashMap<String, String> kvStore = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, java.util.TreeMap<Double, String>> zSetStore = new ConcurrentHashMap<>();

        org.springframework.data.redis.core.ValueOperations<String, String> valueOps = Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);
        
        when(valueOps.increment(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            long val = Long.parseLong(kvStore.getOrDefault(key, "0")) + 1;
            kvStore.put(key, String.valueOf(val));
            return val;
        });
        when(valueOps.get(anyString())).thenAnswer(inv -> kvStore.get(inv.getArgument(0)));
        
        // 修正：set 方法只需將 Key 和 Value 放入 Map
        doAnswer(inv -> {
            kvStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString());

        doAnswer(inv -> {
            kvStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (kvStore.containsKey(key)) return false;
            kvStore.put(key, inv.getArgument(1));
            return true;
        });

        when(template.delete(anyString())).thenAnswer(inv -> {
            kvStore.remove(inv.getArgument(0));
            return true;
        });
        
        when(template.hasKey(anyString())).thenAnswer(inv -> kvStore.containsKey(inv.getArgument(0)));

        org.springframework.data.redis.core.ZSetOperations<String, String> zSetOps = Mockito.mock(org.springframework.data.redis.core.ZSetOperations.class);
        when(template.opsForZSet()).thenReturn(zSetOps);
        
        when(zSetOps.add(anyString(), anyString(), anyDouble())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            String val = inv.getArgument(1);
            Double score = inv.getArgument(2);
            zSetStore.computeIfAbsent(key, k -> new java.util.TreeMap<>()).put(score, val);
            return true;
        });
        when(zSetOps.range(anyString(), anyLong(), anyLong())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            java.util.TreeMap<Double, String> map = zSetStore.get(key);
            return map == null ? java.util.Collections.emptySet() : new java.util.HashSet<>(map.values());
        });
        
        when(zSetOps.remove(anyString(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Object val = inv.getArgument(1);
            if (zSetStore.containsKey(key)) zSetStore.get(key).values().remove(val);
            return 1L;
        });

        return template;
    }

    @Bean
    @Primary
    @SuppressWarnings("unchecked")
    public Web3j mockWeb3j() throws Exception {
        Web3j web3j = Mockito.mock(Web3j.class);
        AtomicLong blockCounter = new AtomicLong(1000);

        EthGasPrice gasPrice = new EthGasPrice();
        gasPrice.setResult(Numeric.encodeQuantity(BigInteger.valueOf(20000000000L)));
        Request gasReq = Mockito.mock(Request.class);
        when(web3j.ethGasPrice()).thenReturn(gasReq);
        when(gasReq.send()).thenReturn(gasPrice);

        EthGetTransactionCount txCount = new EthGetTransactionCount();
        txCount.setResult(Numeric.encodeQuantity(BigInteger.ZERO));
        Request countReq = Mockito.mock(Request.class);
        when(web3j.ethGetTransactionCount(anyString(), any(DefaultBlockParameter.class))).thenReturn(countReq);
        when(countReq.send()).thenReturn(txCount);

        EthEstimateGas estGas = new EthEstimateGas();
        estGas.setResult(Numeric.encodeQuantity(BigInteger.valueOf(21000)));
        Request estReq = Mockito.mock(Request.class);
        when(web3j.ethEstimateGas(any())).thenReturn(estReq);
        when(estReq.send()).thenReturn(estGas);

        EthSendTransaction sendTx = new EthSendTransaction();
        sendTx.setResult("0x" + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Request sendReq = Mockito.mock(Request.class);
        when(web3j.ethSendRawTransaction(anyString())).thenReturn(sendReq);
        when(sendReq.send()).thenReturn(sendTx);

        when(web3j.ethBlockNumber()).thenAnswer(inv -> {
            EthBlockNumber bn = new EthBlockNumber();
            bn.setResult(Numeric.encodeQuantity(BigInteger.valueOf(blockCounter.incrementAndGet())));
            return createRequest(bn);
        });

        when(web3j.ethGetTransactionReceipt(anyString())).thenAnswer(inv -> {
            EthGetTransactionReceipt resp = new EthGetTransactionReceipt();
            TransactionReceipt receipt = new TransactionReceipt();
            receipt.setTransactionHash(inv.getArgument(0));
            receipt.setBlockNumber(Numeric.encodeQuantity(BigInteger.valueOf(blockCounter.get() - 2)));
            resp.setResult(receipt);
            return createRequest(resp);
        });

        return web3j;
    }

    @SuppressWarnings("unchecked")
    private <T extends Response> Request<?, T> createRequest(T result) throws Exception {
        Request req = Mockito.mock(Request.class);
        when(req.send()).thenReturn(result);
        return req;
    }

    @Bean
    public org.springframework.boot.CommandLineRunner demoRunner(com.relayer.practice.service.TransactionRelayer relayer) {
        return args -> {
            System.out.println("\n======= 啟動全模擬演示模式 (Demo Mode) =======");
            Thread.sleep(2000); 
            relayer.sendTransaction("0xDemoRecipientAddress", BigInteger.valueOf(1000000000000000L), "0x")
                .thenAccept(hash -> System.out.println("成功啟動首筆演示交易，Hash: " + hash))
                .exceptionally(ex -> {
                    System.err.println("演示交易啟動失敗: " + ex.getMessage());
                    return null;
                });
        };
    }
}
