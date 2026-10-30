package com.mahvalenterj.resiliencia.contingencia;

import java.math.BigDecimal;
import java.time.Instant;

/** Pagamento cujo resultado é incerto e precisa ser confirmado com o parceiro depois. */
public record PagamentoPendente(String id, BigDecimal valor, String idempotencyKey, Instant recebidoEm) {
}
