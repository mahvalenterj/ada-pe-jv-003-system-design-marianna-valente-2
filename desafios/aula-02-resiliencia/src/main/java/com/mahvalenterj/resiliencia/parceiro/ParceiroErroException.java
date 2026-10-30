package com.mahvalenterj.resiliencia.parceiro;

/** O parceiro respondeu com erro 5xx. */
public class ParceiroErroException extends RuntimeException {

    public ParceiroErroException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }

    public ParceiroErroException(String mensagem) {
        super(mensagem);
    }
}
