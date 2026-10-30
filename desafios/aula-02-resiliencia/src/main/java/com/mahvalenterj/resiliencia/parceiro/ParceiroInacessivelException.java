package com.mahvalenterj.resiliencia.parceiro;

/** Não conseguimos nem conectar ao parceiro: com certeza nada foi cobrado. */
public class ParceiroInacessivelException extends RuntimeException {

    public ParceiroInacessivelException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }

    public ParceiroInacessivelException(String mensagem) {
        super(mensagem);
    }
}
