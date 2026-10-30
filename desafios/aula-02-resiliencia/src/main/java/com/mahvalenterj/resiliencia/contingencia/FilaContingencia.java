package com.mahvalenterj.resiliencia.contingencia;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.springframework.stereotype.Component;

/**
 * Fila de contingência dos pagamentos com resultado incerto (timeout de leitura).
 *
 * Em memória e com capacidade limitada, para o desafio. Em produção seria uma fila durável
 * (Kafka/RabbitMQ ou tabela outbox) consumida por um job de reconciliação, que consulta o
 * parceiro pela Idempotency-Key e confirma ou cancela o pedido.
 */
@Component
public class FilaContingencia {

    private final BlockingQueue<PagamentoPendente> fila;

    public FilaContingencia(ContingenciaProperties props) {
        this.fila = new ArrayBlockingQueue<>(props.capacidade());
    }

    public void enfileirar(PagamentoPendente pendente) {
        if (!fila.offer(pendente)) {
            throw new ContingenciaIndisponivelException();
        }
    }

    public List<PagamentoPendente> pendentes() {
        return List.copyOf(fila);
    }

    public void limpar() {
        fila.clear();
    }
}
