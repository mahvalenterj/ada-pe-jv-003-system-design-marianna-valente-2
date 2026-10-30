package com.mahvalenterj.resiliencia;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Connect timeout x read timeout: aqui ninguém escuta na porta, então a conexão é recusada.
 * Diferente do timeout de leitura (202 PENDENTE), sabemos que nada foi cobrado: 503 com Retry-After,
 * e a Idempotency-Key é liberada para o cliente tentar de novo.
 */
@SpringBootTest(properties = "parceiro.base-url=http://127.0.0.1:1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ParceiroInacessivelTest {

    @Autowired MockMvc mockMvc;

    @Test
    @DisplayName("Conexão recusada: 503 parceiro-inacessível, e a mesma chave pode ser reenviada depois")
    void conexaoRecusada() throws Exception {
        for (int tentativa = 0; tentativa < 2; tentativa++) {
            mockMvc.perform(post("/pagamentos")
                            .header("Idempotency-Key", "chave-fixa")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\":\"pedido-1\",\"valor\":10}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().exists("Retry-After"))
                    .andExpect(jsonPath("$.type").value("https://mahvalenterj.github.io/erros/parceiro-inacessivel"));
        }
    }
}
