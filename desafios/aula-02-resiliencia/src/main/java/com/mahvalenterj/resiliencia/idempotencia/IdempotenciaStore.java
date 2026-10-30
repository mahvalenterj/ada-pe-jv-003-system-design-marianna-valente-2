package com.mahvalenterj.resiliencia.idempotencia;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

import com.mahvalenterj.resiliencia.pagamento.PagamentoResponse;

/**
 * Guarda as Idempotency-Keys já vistas.
 *
 * Aqui é um mapa em memória, para manter o desafio simples. Em produção seria Redis ou banco,
 * compartilhado entre as instâncias e com TTL (ex.: 24 h). A regra é a mesma:
 * - chave nova: reserva atomicamente (putIfAbsent) e segue para a cobrança;
 * - chave em processamento: 409, sem nova cobrança;
 * - chave concluída: devolve a mesma resposta salva;
 * - mesma chave com outro conteúdo: 422.
 */
@Component
public class IdempotenciaStore {

    private record Registro(String impressaoDigital, PagamentoResponse resposta) {
        boolean emProcessamento() {
            return resposta == null;
        }
    }

    private final ConcurrentMap<String, Registro> registros = new ConcurrentHashMap<>();

    /**
     * Tenta reservar a chave. Vazio = chave nova, pode cobrar.
     * Preenchido = repetição de um pagamento já concluído, devolva essa resposta.
     */
    public Optional<PagamentoResponse> reservar(String chave, String impressaoDigital) {
        Registro existente = registros.putIfAbsent(chave, new Registro(impressaoDigital, null));
        if (existente == null) {
            return Optional.empty();
        }
        if (!existente.impressaoDigital().equals(impressaoDigital)) {
            throw new ChaveReutilizadaException(chave);
        }
        if (existente.emProcessamento()) {
            throw new PagamentoEmProcessamentoException(chave);
        }
        return Optional.of(existente.resposta());
    }

    public void concluir(String chave, PagamentoResponse resposta) {
        registros.computeIfPresent(chave, (k, atual) -> new Registro(atual.impressaoDigital(), resposta));
    }

    /** Libera a chave quando temos certeza de que nada foi cobrado, para o cliente poder tentar de novo. */
    public void liberar(String chave) {
        registros.remove(chave);
    }

    public void limpar() {
        registros.clear();
    }
}
