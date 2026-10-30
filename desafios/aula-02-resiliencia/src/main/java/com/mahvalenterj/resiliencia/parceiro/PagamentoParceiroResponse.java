package com.mahvalenterj.resiliencia.parceiro;

/** Resposta do parceiro: o id da transação e o status (ex.: APROVADO). */
public record PagamentoParceiroResponse(String id, String status) {
}
