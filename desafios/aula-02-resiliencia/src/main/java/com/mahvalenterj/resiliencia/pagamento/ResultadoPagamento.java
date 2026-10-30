package com.mahvalenterj.resiliencia.pagamento;

/** Resposta do pagamento + se ela veio do registro de idempotência (repetição). */
public record ResultadoPagamento(PagamentoResponse resposta, boolean repeticao) {
}
