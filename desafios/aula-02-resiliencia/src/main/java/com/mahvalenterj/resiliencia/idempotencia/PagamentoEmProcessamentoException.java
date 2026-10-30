package com.mahvalenterj.resiliencia.idempotencia;

public class PagamentoEmProcessamentoException extends RuntimeException {

    public PagamentoEmProcessamentoException(String chave) {
        super("O pagamento com a Idempotency-Key " + chave + " ainda está em processamento");
    }
}
