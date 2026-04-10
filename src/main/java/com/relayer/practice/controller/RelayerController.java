package com.relayer.practice.controller;

import com.relayer.practice.service.TransactionRelayer;
import lombok.Data;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/relayer")
public class RelayerController {

    private final TransactionRelayer transactionRelayer;

    public RelayerController(TransactionRelayer transactionRelayer) {
        this.transactionRelayer = transactionRelayer;
    }

    @PostMapping("/send")
    public CompletableFuture<String> send(@RequestBody SendRequest request) {
        return transactionRelayer.sendTransaction(
                request.getTo(),
                request.getValue() != null ? request.getValue() : BigInteger.ZERO,
                request.getData() != null ? request.getData() : "0x"
        );
    }

    @Data
    public static class SendRequest {
        private String to;
        private BigInteger value;
        private String data;
    }
}
