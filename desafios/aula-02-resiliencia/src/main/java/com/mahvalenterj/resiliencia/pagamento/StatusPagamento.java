package com.mahvalenterj.resiliencia.pagamento;

/**
 * APROVADO e RECUSADO: o parceiro deu a resposta final.
 * PENDENTE: o parceiro não respondeu a tempo; o pagamento foi para a fila de contingência
 * e será confirmado por reconciliação. Nunca "aprovamos" sem confirmação do parceiro.
 */
public enum StatusPagamento {
    APROVADO,
    RECUSADO,
    PENDENTE
}
