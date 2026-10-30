package com.mahvalenterj.resiliencia.pagamento;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Corpo da nossa API. A Idempotency-Key chega no header, não aqui. */
public record PagamentoRequest(
        @Schema(example = "pedido-123") @NotBlank String id,
        @Schema(example = "199.90") @NotNull @DecimalMin(value = "0.01") BigDecimal valor) {

    /** "Impressão digital" do pedido: a mesma chave só pode ser reusada com o mesmo conteúdo. */
    public String impressaoDigital() {
        return id + "|" + valor.stripTrailingZeros().toPlainString();
    }
}
