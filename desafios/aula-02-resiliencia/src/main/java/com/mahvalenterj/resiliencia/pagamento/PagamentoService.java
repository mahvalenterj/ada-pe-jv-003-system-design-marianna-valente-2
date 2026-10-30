package com.mahvalenterj.resiliencia.pagamento;

import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mahvalenterj.resiliencia.contingencia.FilaContingencia;
import com.mahvalenterj.resiliencia.contingencia.PagamentoPendente;
import com.mahvalenterj.resiliencia.idempotencia.IdempotenciaStore;
import com.mahvalenterj.resiliencia.parceiro.PagamentoParceiroRequest;
import com.mahvalenterj.resiliencia.parceiro.PagamentoParceiroResponse;
import com.mahvalenterj.resiliencia.parceiro.PagamentoRecusadoException;
import com.mahvalenterj.resiliencia.parceiro.ParceiroPagamentosClient;
import com.mahvalenterj.resiliencia.parceiro.ParceiroSemRespostaException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Fluxo do pagamento: idempotência primeiro, depois a chamada protegida pelo Circuit Breaker.
 *
 * Decisões sobre a Idempotency-Key em cada desfecho:
 * - APROVADO / RECUSADO: resposta final, fica salva para repetições.
 * - Sem resposta a tempo: a cobrança PODE ter acontecido. Vai para a fila de contingência e
 *   respondemos 202 PENDENTE. Essa resposta fica salva: reenviar a mesma chave não cobra de novo.
 * - Circuito aberto, falha de conexão, 429 ou 5xx: a chave é liberada para o cliente tentar
 *   depois. Mesmo que o 5xx tenha cobrado algo, o parceiro recebe a mesma Idempotency-Key no
 *   header e não cobra duas vezes.
 */
@Service
public class PagamentoService {

    public static final String CIRCUITO = "parceiroPagamentos";

    private static final Logger log = LoggerFactory.getLogger(PagamentoService.class);

    private final ParceiroPagamentosClient parceiro;
    private final IdempotenciaStore idempotencia;
    private final FilaContingencia contingencia;
    private final CircuitBreaker circuitBreaker;

    public PagamentoService(ParceiroPagamentosClient parceiro,
                            IdempotenciaStore idempotencia,
                            FilaContingencia contingencia,
                            CircuitBreakerRegistry registry) {
        this.parceiro = parceiro;
        this.idempotencia = idempotencia;
        this.contingencia = contingencia;
        this.circuitBreaker = registry.circuitBreaker(CIRCUITO);
    }

    public ResultadoPagamento pagar(String chave, PagamentoRequest pedido) {
        Optional<PagamentoResponse> jaProcessado = idempotencia.reservar(chave, pedido.impressaoDigital());
        if (jaProcessado.isPresent()) {
            log.info("Repetição da Idempotency-Key {}: devolvendo resposta salva, sem chamar o parceiro", chave);
            return new ResultadoPagamento(jaProcessado.get(), true);
        }

        try {
            PagamentoParceiroResponse resposta = circuitBreaker.executeSupplier(
                    () -> parceiro.cobrar(new PagamentoParceiroRequest(pedido.id(), pedido.valor(), chave)));
            return concluir(chave, new PagamentoResponse(pedido.id(), traduzir(resposta.status())));

        } catch (PagamentoRecusadoException e) {
            return concluir(chave, new PagamentoResponse(pedido.id(), StatusPagamento.RECUSADO));

        } catch (ParceiroSemRespostaException e) {
            return contingenciar(chave, pedido);

        } catch (RuntimeException e) {
            // circuito aberto, falha de conexão, 429, 5xx ou erro inesperado
            idempotencia.liberar(chave);
            throw e;
        }
    }

    private ResultadoPagamento contingenciar(String chave, PagamentoRequest pedido) {
        try {
            contingencia.enfileirar(new PagamentoPendente(pedido.id(), pedido.valor(), chave, Instant.now()));
        } catch (RuntimeException falhaNaContingencia) {
            // nem a contingência funcionou: libera a chave para o cliente tentar de novo com ela
            idempotencia.liberar(chave);
            throw falhaNaContingencia;
        }
        log.warn("Parceiro sem resposta para {}: pagamento enviado para reconciliação", pedido.id());
        return concluir(chave, new PagamentoResponse(pedido.id(), StatusPagamento.PENDENTE));
    }

    private ResultadoPagamento concluir(String chave, PagamentoResponse resposta) {
        idempotencia.concluir(chave, resposta);
        return new ResultadoPagamento(resposta, false);
    }

    private static StatusPagamento traduzir(String statusParceiro) {
        return "APROVADO".equalsIgnoreCase(statusParceiro) ? StatusPagamento.APROVADO : StatusPagamento.RECUSADO;
    }
}
