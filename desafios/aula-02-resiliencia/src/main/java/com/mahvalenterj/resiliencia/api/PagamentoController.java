package com.mahvalenterj.resiliencia.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mahvalenterj.resiliencia.pagamento.PagamentoRequest;
import com.mahvalenterj.resiliencia.pagamento.PagamentoResponse;
import com.mahvalenterj.resiliencia.pagamento.PagamentoService;
import com.mahvalenterj.resiliencia.pagamento.ResultadoPagamento;
import com.mahvalenterj.resiliencia.pagamento.StatusPagamento;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/pagamentos")
@Tag(name = "Pagamentos", description = "Cobrança via parceiro externo, protegida por Circuit Breaker, timeouts e idempotência")
public class PagamentoController {

    static final String HEADER_REPETICAO = "Idempotent-Replayed";

    private final PagamentoService service;

    public PagamentoController(PagamentoService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Cobra um pagamento",
            description = "Reenviar a mesma Idempotency-Key nunca gera nova cobrança: devolve a resposta já registrada.")
    @ApiResponse(responseCode = "200", description = "Resposta final do parceiro: APROVADO ou RECUSADO",
            content = @Content(schema = @Schema(implementation = PagamentoResponse.class)))
    @ApiResponse(responseCode = "202", description = "Contingência: o parceiro não respondeu a tempo. "
            + "Status PENDENTE; o pagamento foi para a fila de reconciliação e será confirmado depois.",
            content = @Content(schema = @Schema(implementation = PagamentoResponse.class)))
    @ApiResponse(responseCode = "400", description = "Requisição inválida ou sem Idempotency-Key",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Já existe um pagamento em processamento com esta Idempotency-Key",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Idempotency-Key já usada com outro conteúdo de pagamento",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "O parceiro respondeu com erro. Nenhuma cobrança confirmada; pode tentar de novo com a mesma chave.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Parceiro indisponível (circuito aberto, falha de conexão ou limite 429) "
            + "ou contingência indisponível. Tente de novo após o Retry-After.",
            headers = @Header(name = "Retry-After", description = "Segundos até uma nova tentativa fazer sentido"),
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PagamentoResponse> pagar(
            @Parameter(in = ParameterIn.HEADER, required = true,
                    description = "UUID gerado pelo cliente por intenção de compra e reenviado em toda nova tentativa")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PagamentoRequest pedido) {

        ResultadoPagamento resultado = service.pagar(idempotencyKey, pedido);
        HttpStatus status = resultado.resposta().status() == StatusPagamento.PENDENTE ? HttpStatus.ACCEPTED : HttpStatus.OK;

        return ResponseEntity.status(status)
                .header(HEADER_REPETICAO, String.valueOf(resultado.repeticao()))
                .body(resultado.resposta());
    }
}
