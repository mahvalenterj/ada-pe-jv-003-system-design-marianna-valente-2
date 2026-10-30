package com.mahvalenterj.resiliencia;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mahvalenterj.resiliencia.contingencia.FilaContingencia;
import com.mahvalenterj.resiliencia.idempotencia.IdempotenciaStore;
import com.mahvalenterj.resiliencia.pagamento.PagamentoService;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Engenharia do caos local: o WireMock faz o papel do parceiro instável.
 * Configuração de teste (application-test.yml): read timeout 1 s, circuito abre com 50% de
 * falha em 4 chamadas, fica aberto 1 s e testa 2 chamadas em HALF_OPEN.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PagamentoResilienciaTest {

    @RegisterExtension
    static WireMockExtension parceiro = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void apontarParaOWireMock(DynamicPropertyRegistry registry) {
        registry.add("parceiro.base-url", parceiro::baseUrl);
    }

    @Autowired MockMvc mockMvc;
    @Autowired CircuitBreakerRegistry circuitBreakers;
    @Autowired IdempotenciaStore idempotencia;
    @Autowired FilaContingencia contingencia;

    CircuitBreaker circuito;

    @BeforeEach
    void estadoLimpo() {
        circuito = circuitBreakers.circuitBreaker(PagamentoService.CIRCUITO);
        circuito.reset();
        idempotencia.limpar();
        contingencia.limpar();
    }

    // ---------- cenários pedidos no desafio

    @Test
    @DisplayName("Caminho feliz: parceiro aprova, API devolve 200 e envia a Idempotency-Key no header")
    void caminhoFeliz() throws Exception {
        parceiroAprova();
        String chave = novaChave();

        pagar(chave, "pedido-1", "100.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APROVADO"));

        parceiro.verify(1, postRequestedFor(urlEqualTo("/pagamentos"))
                .withHeader("Idempotency-Key", equalTo(chave)));
    }

    @Test
    @DisplayName("Lentidão: parceiro demora 3 s, API corta em ~1 s e responde 202 PENDENTE (contingência)")
    void lentidao() throws Exception {
        parceiro.stubFor(post("/pagamentos").willReturn(okJson(aprovado()).withFixedDelay(3000)));

        long inicio = System.currentTimeMillis();
        pagar(novaChave(), "pedido-lento", "50.00")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDENTE"));
        long duracao = System.currentTimeMillis() - inicio;

        assertThat(duracao).as("não pode esperar os 3 s do parceiro").isLessThan(2500);
        assertThat(contingencia.pendentes()).hasSize(1);
    }

    @Test
    @DisplayName("Abertura de circuito: uma sequência de erros 500 muda o Circuit Breaker para OPEN")
    void aberturaDeCircuito() throws Exception {
        parceiro.stubFor(post("/pagamentos").willReturn(aResponse().withStatus(500)));

        abrirCircuito();

        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("Circuito OPEN: responde 503 + Retry-After na hora, sem chamar o parceiro")
    void circuitoAberto() throws Exception {
        parceiro.stubFor(post("/pagamentos").willReturn(aResponse().withStatus(500)));
        abrirCircuito();
        parceiro.resetRequests();

        long inicio = System.currentTimeMillis();
        pagar(novaChave(), "pedido-x", "10.00")
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.title").value("Parceiro de pagamentos temporariamente indisponível"))
                .andExpect(jsonPath("$.trace").doesNotExist());
        long duracao = System.currentTimeMillis() - inicio;

        assertThat(duracao).as("falha rápida, sem esperar timeout").isLessThan(300);
        parceiro.verify(0, postRequestedFor(urlEqualTo("/pagamentos")));
    }

    // ---------- "vá além do básico"

    @Nested
    @DisplayName("Vá além do básico")
    class AlemDoBasico {

        @Test
        @DisplayName("Erro 5xx do parceiro vira 502 em Problem Details, sem propagar o 500")
        void erro5xx() throws Exception {
            parceiro.stubFor(post("/pagamentos").willReturn(aResponse().withStatus(503)));

            pagar(novaChave(), "pedido-5xx", "10.00")
                    .andExpect(status().isBadGateway())
                    .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                    .andExpect(jsonPath("$.type").value("https://mahvalenterj.github.io/erros/parceiro-erro"))
                    .andExpect(jsonPath("$.status").value(502));
        }

        @Test
        @DisplayName("HTTP 429 do parceiro vira 503 com o Retry-After que o parceiro pediu")
        void limiteDoParceiro() throws Exception {
            parceiro.stubFor(post("/pagamentos").willReturn(aResponse().withStatus(429).withHeader("Retry-After", "30")));

            pagar(novaChave(), "pedido-429", "10.00")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "30"))
                    .andExpect(jsonPath("$.type").value("https://mahvalenterj.github.io/erros/parceiro-sobrecarregado"));
        }

        @Test
        @DisplayName("Conexão derrubada no meio da resposta conta como resultado incerto: 202 PENDENTE")
        void conexaoDerrubada() throws Exception {
            parceiro.stubFor(post("/pagamentos").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            pagar(novaChave(), "pedido-reset", "10.00")
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("PENDENTE"));
        }

        @Test
        @DisplayName("Recuperação OPEN -> HALF_OPEN -> CLOSED: após a espera, chamadas de teste passam e o circuito fecha")
        void recuperacaoDoCircuito() throws Exception {
            parceiro.stubFor(post("/pagamentos").willReturn(aResponse().withStatus(500)));
            abrirCircuito();
            assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            Thread.sleep(1200); // espera o wait-duration-in-open-state (1 s no perfil de teste)
            parceiroAprova();

            pagar(novaChave(), "teste-1", "1.00").andExpect(status().isOk());
            assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

            pagar(novaChave(), "teste-2", "1.00").andExpect(status().isOk());
            assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("Idempotência pós-timeout: reenviar a mesma chave devolve o mesmo 202, sem nova chamada ao parceiro")
        void idempotenciaPosTimeout() throws Exception {
            parceiro.stubFor(post("/pagamentos").willReturn(okJson(aprovado()).withFixedDelay(3000)));
            String chave = novaChave();

            pagar(chave, "pedido-tv", "3000.00")
                    .andExpect(status().isAccepted())
                    .andExpect(header().string("Idempotent-Replayed", "false"));

            pagar(chave, "pedido-tv", "3000.00")
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("PENDENTE"))
                    .andExpect(header().string("Idempotent-Replayed", "true"));

            parceiro.verify(1, postRequestedFor(urlEqualTo("/pagamentos")));
            assertThat(contingencia.pendentes()).hasSize(1);
        }

        @Test
        @DisplayName("Mesma chave com outro valor: 422, sem chamar o parceiro")
        void chaveReutilizada() throws Exception {
            parceiroAprova();
            String chave = novaChave();
            pagar(chave, "pedido-1", "100.00").andExpect(status().isOk());

            pagar(chave, "pedido-1", "999.00")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type").value("https://mahvalenterj.github.io/erros/chave-reutilizada"));

            parceiro.verify(1, postRequestedFor(urlEqualTo("/pagamentos")));
        }

        @Test
        @DisplayName("Falha no fallback: parceiro sem resposta e fila de contingência cheia resulta em 503")
        void falhaNoFallback() throws Exception {
            parceiro.stubFor(post("/pagamentos").willReturn(okJson(aprovado()).withFixedDelay(3000)));

            // a fila de teste comporta 2 pendentes
            pagar(novaChave(), "p1", "1.00").andExpect(status().isAccepted());
            pagar(novaChave(), "p2", "1.00").andExpect(status().isAccepted());

            pagar(novaChave(), "p3", "1.00")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().exists("Retry-After"))
                    .andExpect(jsonPath("$.type").value("https://mahvalenterj.github.io/erros/contingencia-indisponivel"));
        }

        @Test
        @DisplayName("Sem Idempotency-Key: 400 em Problem Details")
        void semChave() throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.post("/pagamentos")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\":\"p\",\"valor\":10}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE));
        }
    }

    // ---------- apoio

    private void abrirCircuito() throws Exception {
        for (int i = 0; i < 4; i++) { // janela de 4 chamadas no perfil de teste
            pagar(novaChave(), "falha-" + i, "1.00");
        }
    }

    private ResultActions pagar(String chave, String id, String valor) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/pagamentos")
                .header("Idempotency-Key", chave)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"%s\",\"valor\":%s}".formatted(id, valor)));
    }

    private void parceiroAprova() {
        parceiro.stubFor(post("/pagamentos").willReturn(okJson(aprovado())));
    }

    private static String aprovado() {
        return "{\"id\":\"tx-1\",\"status\":\"APROVADO\"}";
    }

    private static String novaChave() {
        return UUID.randomUUID().toString();
    }
}
