package com.mahvalenterj.resiliencia.parceiro;

import java.math.BigDecimal;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Pedido de cobrança enviado ao parceiro.
 * A idempotencyKey faz parte do record (não dá para criar uma cobrança sem ela),
 * mas não vai no corpo: o cliente HTTP sempre a envia no header Idempotency-Key.
 */
public record PagamentoParceiroRequest(
        String id,
        BigDecimal valor,
        @JsonIgnore String idempotencyKey) {

    public PagamentoParceiroRequest {
        Objects.requireNonNull(id, "id é obrigatório");
        Objects.requireNonNull(valor, "valor é obrigatório");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey é obrigatória em toda cobrança");
        }
    }
}
