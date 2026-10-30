package com.mahvalenterj.resiliencia.parceiro;

/** O parceiro recusou o pagamento (4xx de negócio). Não conta como falha do circuito. */
public class PagamentoRecusadoException extends RuntimeException {

    public PagamentoRecusadoException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }

    public PagamentoRecusadoException(String mensagem) {
        super(mensagem);
    }
}
