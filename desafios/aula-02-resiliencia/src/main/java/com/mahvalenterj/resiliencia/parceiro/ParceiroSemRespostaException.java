package com.mahvalenterj.resiliencia.parceiro;

/** A requisição saiu, mas a resposta não chegou a tempo: a cobrança pode ter acontecido. */
public class ParceiroSemRespostaException extends RuntimeException {

    public ParceiroSemRespostaException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }

    public ParceiroSemRespostaException(String mensagem) {
        super(mensagem);
    }
}
