package com.mahvalenterj.resiliencia.api;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.mahvalenterj.resiliencia.contingencia.ContingenciaIndisponivelException;
import com.mahvalenterj.resiliencia.idempotencia.ChaveReutilizadaException;
import com.mahvalenterj.resiliencia.idempotencia.PagamentoEmProcessamentoException;
import com.mahvalenterj.resiliencia.pagamento.PagamentoService;
import com.mahvalenterj.resiliencia.parceiro.ParceiroErroException;
import com.mahvalenterj.resiliencia.parceiro.ParceiroInacessivelException;
import com.mahvalenterj.resiliencia.parceiro.ParceiroSobrecarregadoException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Converte toda falha em Problem Details (RFC 7807), sem expor stack trace nem detalhes internos.
 * Estender ResponseEntityExceptionHandler faz os erros do próprio Spring (validação, header
 * ausente) saírem no mesmo formato.
 */
@RestControllerAdvice
public class TratamentoGlobalDeErros extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TratamentoGlobalDeErros.class);
    private static final String BASE_TIPO = "https://mahvalenterj.github.io/erros/";
    private static final long RETRY_AFTER_PADRAO_SEGUNDOS = 5;

    private final CircuitBreakerRegistry registry;

    public TratamentoGlobalDeErros(CircuitBreakerRegistry registry) {
        this.registry = registry;
    }

    @ExceptionHandler(CallNotPermittedException.class)
    ResponseEntity<ProblemDetail> circuitoAberto(CallNotPermittedException e) {
        long espera = Math.max(1, registry.circuitBreaker(PagamentoService.CIRCUITO)
                .getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1) / 1000);
        return indisponivel("circuito-aberto", "Parceiro de pagamentos temporariamente indisponível",
                "O parceiro está falhando e as chamadas foram suspensas para protegê-lo. Nenhuma cobrança foi feita.", espera);
    }

    @ExceptionHandler(ParceiroInacessivelException.class)
    ResponseEntity<ProblemDetail> parceiroInacessivel(ParceiroInacessivelException e) {
        log.warn("Falha de conexão com o parceiro: {}", e.getMessage());
        return indisponivel("parceiro-inacessivel", "Não foi possível conectar ao parceiro de pagamentos",
                "A conexão falhou antes de enviar o pedido. Nenhuma cobrança foi feita.", RETRY_AFTER_PADRAO_SEGUNDOS);
    }

    @ExceptionHandler(ParceiroSobrecarregadoException.class)
    ResponseEntity<ProblemDetail> parceiroSobrecarregado(ParceiroSobrecarregadoException e) {
        long espera = e.getRetryAfterSegundos() != null ? e.getRetryAfterSegundos() : RETRY_AFTER_PADRAO_SEGUNDOS;
        return indisponivel("parceiro-sobrecarregado", "Parceiro de pagamentos sobrecarregado",
                "O parceiro limitou as requisições. Nenhuma cobrança foi feita.", espera);
    }

    @ExceptionHandler(ContingenciaIndisponivelException.class)
    ResponseEntity<ProblemDetail> contingenciaIndisponivel(ContingenciaIndisponivelException e) {
        log.error("Parceiro sem resposta E contingência indisponível: {}", e.getMessage());
        return indisponivel("contingencia-indisponivel", "Pagamento não pôde ser registrado",
                "O parceiro não respondeu e a fila de contingência está indisponível. "
                        + "Tente de novo com a mesma Idempotency-Key: ela impede cobrança duplicada.",
                RETRY_AFTER_PADRAO_SEGUNDOS);
    }

    @ExceptionHandler(ParceiroErroException.class)
    ResponseEntity<ProblemDetail> parceiroComErro(ParceiroErroException e) {
        log.warn("Erro do parceiro: {}", e.getMessage());
        return resposta(HttpStatus.BAD_GATEWAY, "parceiro-erro", "O parceiro de pagamentos respondeu com erro",
                "Tente de novo com a mesma Idempotency-Key.", null);
    }

    @ExceptionHandler(PagamentoEmProcessamentoException.class)
    ResponseEntity<ProblemDetail> emProcessamento(PagamentoEmProcessamentoException e) {
        return resposta(HttpStatus.CONFLICT, "pagamento-em-processamento", "Pagamento em processamento", e.getMessage(), null);
    }

    @ExceptionHandler(ChaveReutilizadaException.class)
    ResponseEntity<ProblemDetail> chaveReutilizada(ChaveReutilizadaException e) {
        return resposta(HttpStatus.UNPROCESSABLE_ENTITY, "chave-reutilizada", "Idempotency-Key reutilizada", e.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> inesperado(Exception e) {
        log.error("Erro inesperado", e);   // o stack trace fica só no log, nunca na resposta
        return resposta(HttpStatus.INTERNAL_SERVER_ERROR, "erro-interno", "Erro interno",
                "Ocorreu um erro inesperado.", null);
    }

    private ResponseEntity<ProblemDetail> indisponivel(String tipo, String titulo, String detalhe, long retryAfterSegundos) {
        return resposta(HttpStatus.SERVICE_UNAVAILABLE, tipo, titulo, detalhe, retryAfterSegundos);
    }

    private ResponseEntity<ProblemDetail> resposta(HttpStatus status, String tipo, String titulo, String detalhe,
                                                   Long retryAfterSegundos) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        problema.setType(URI.create(BASE_TIPO + tipo));
        problema.setTitle(titulo);

        HttpHeaders headers = new HttpHeaders();
        if (retryAfterSegundos != null) {
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSegundos));
        }
        return ResponseEntity.status(status).headers(headers).body(problema);
    }
}
