package com.mahvalenterj.resiliencia.idempotencia;

public class ChaveReutilizadaException extends RuntimeException {

    public ChaveReutilizadaException(String chave) {
        super("A Idempotency-Key " + chave + " já foi usada com outro conteúdo de pagamento");
    }
}
