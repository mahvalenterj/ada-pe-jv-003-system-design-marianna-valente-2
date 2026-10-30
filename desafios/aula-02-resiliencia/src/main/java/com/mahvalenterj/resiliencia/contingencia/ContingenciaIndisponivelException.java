package com.mahvalenterj.resiliencia.contingencia;

public class ContingenciaIndisponivelException extends RuntimeException {

    public ContingenciaIndisponivelException() {
        super("A fila de contingência está cheia ou indisponível");
    }
}
