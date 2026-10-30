package com.mahvalenterj.resiliencia.parceiro;

/** O parceiro respondeu 429 (limite de requisições). Guarda o Retry-After sugerido por ele, se houver. */
public class ParceiroSobrecarregadoException extends RuntimeException {

    private final Long retryAfterSegundos;

    public ParceiroSobrecarregadoException(Long retryAfterSegundos) {
        super("Parceiro de pagamentos respondeu 429 (limite de requisições)");
        this.retryAfterSegundos = retryAfterSegundos;
    }

    public Long getRetryAfterSegundos() {
        return retryAfterSegundos;
    }
}
