package com.mahvalenterj.resiliencia.parceiro;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Cliente HTTP do parceiro, com RestClient sobre o HttpClient do JDK.
 *
 * Proteção contra lentidão: timeouts NATIVOS do cliente HTTP (connect e read), e não TimeLimiter.
 * Motivo: o timeout nativo encerra a requisição e libera a conexão de verdade. Um TimeLimiter
 * em código bloqueante só desiste de esperar uma CompletableFuture, e a chamada continua
 * ocupando recursos por trás.
 *
 * Separar os dois timeouts importa para o negócio:
 * - falha ao CONECTAR: a requisição nunca chegou, então nada foi cobrado;
 * - estouro de LEITURA: a requisição chegou e a cobrança pode ter acontecido.
 */
@Component
public class ParceiroPagamentosClient {

    public static final String HEADER_IDEMPOTENCIA = "Idempotency-Key";

    private final RestClient restClient;

    public ParceiroPagamentosClient(RestClient.Builder builder, ParceiroProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout())
                .build();
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(httpClient);
        fabrica.setReadTimeout(props.readTimeout());

        this.restClient = builder
                .baseUrl(props.baseUrl())
                .requestFactory(fabrica)
                .build();
    }

    public PagamentoParceiroResponse cobrar(PagamentoParceiroRequest pedido) {
        try {
            return restClient.post()
                    .uri("/pagamentos")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HEADER_IDEMPOTENCIA, pedido.idempotencyKey())
                    .body(pedido)
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.TOO_MANY_REQUESTS.value(), (req, resp) -> {
                        throw new ParceiroSobrecarregadoException(lerRetryAfter(resp.getHeaders().getFirst("Retry-After")));
                    })
                    .onStatus(status -> status.is5xxServerError(), (req, resp) -> {
                        throw new ParceiroErroException("Parceiro respondeu " + resp.getStatusCode().value());
                    })
                    .onStatus(status -> status.is4xxClientError(), (req, resp) -> {
                        throw new PagamentoRecusadoException("Parceiro recusou o pagamento: " + resp.getStatusCode().value());
                    })
                    .body(PagamentoParceiroResponse.class);
        } catch (ResourceAccessException e) {
            throw traduzirFalhaDeRede(e);
        }
    }

    private RuntimeException traduzirFalhaDeRede(ResourceAccessException e) {
        for (Throwable causa = e; causa != null; causa = causa.getCause()) {
            // HttpConnectTimeoutException é subclasse de HttpTimeoutException, por isso é testada antes
            if (causa instanceof ConnectException || causa instanceof HttpConnectTimeoutException) {
                return new ParceiroInacessivelException("Não foi possível conectar ao parceiro de pagamentos", e);
            }
            if (causa instanceof HttpTimeoutException) {
                return new ParceiroSemRespostaException("Parceiro não respondeu dentro do tempo limite", e);
            }
        }
        // a conexão caiu no meio da resposta: a requisição pode ter sido processada
        return new ParceiroSemRespostaException("Conexão com o parceiro interrompida durante a resposta", e);
    }

    private static Long lerRetryAfter(String valor) {
        if (valor == null) {
            return null;
        }
        try {
            return Long.parseLong(valor.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
