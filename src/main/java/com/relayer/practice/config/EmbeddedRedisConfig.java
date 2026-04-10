package com.relayer.practice.config;

import org.springframework.context.annotation.Configuration;
import redis.embedded.RedisServer;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Configuration
public class EmbeddedRedisConfig {

    private RedisServer redisServer;

    @PostConstruct
    public void startRedis() {
        try {
            redisServer = new RedisServer(6379);
            redisServer.start();
            System.out.println("Embedded Redis started on port 6379");
        } catch (Exception e) {
            System.err.println("Failed to start Embedded Redis: " + e.getMessage());
        }
    }

    @PreDestroy
    public void stopRedis() {
        if (redisServer != null) {
            redisServer.stop();
            System.out.println("Embedded Redis stopped");
        }
    }
}
