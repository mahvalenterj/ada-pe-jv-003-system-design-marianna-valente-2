# Desafio Aula 02: Integração resiliente com parceiro de pagamentos

Desafio prático opcional da disciplina **PE-JV-003**. É a implementação, em código, das decisões do meu documento de System Design da Aula 02 (APIs Resilientes): uma API de pagamentos que continua de pé quando o parceiro externo fica lento, falha ou cai, sem nunca cobrar o cliente duas vezes.

**Stack:** Java 21 · Spring Boot 3.5 (Spring MVC + Virtual Threads) · RestClient · Resilience4j · SpringDoc/OpenAPI · WireMock · JUnit 5

## Como rodar

```bash
./mvnw test        # ou: mvn test   → roda os 13 testes de caos com WireMock (não precisa de Docker)
mvn spring-boot:run
```

Com a aplicação no ar:

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- Estado do Circuit Breaker: `http://localhost:8080/actuator/circuitbreakers`

```bash
curl -i -X POST http://localhost:8080/pagamentos \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 7f9c1a2e-0000-4000-8000-000000000001" \
  -d '{"id":"pedido-123","valor":199.90}'
```

## Decisões de design

| Decisão | Escolha | Por quê |
| :--- | :--- | :--- |
| Modelo de concorrência | Spring MVC + Virtual Threads | A carga é dominada por espera de I/O; Virtual Threads escalam como o reativo mantendo o código imperativo. |
| Cliente HTTP | `RestClient` sobre o `HttpClient` do JDK | Moderno, fluente, sem dependência extra, e expõe connect timeout e read timeout separados. |
| Proteção contra lentidão | Timeouts **nativos** do cliente (connect 1 s, read 2 s) | O timeout nativo encerra a requisição e libera a conexão de verdade. Um `TimeLimiter` em código bloqueante só desiste de esperar, enquanto a chamada continua ocupando recursos. |
| Proteção contra falhas | Circuit Breaker do Resilience4j | Abre com 50% de falha numa janela de 10 chamadas; aberto, falha na hora com `503` + `Retry-After`. Recusa de negócio (4xx) **não** conta como falha do parceiro. |
| Idempotência | Header `Idempotency-Key` obrigatório | A chave é campo do record `PagamentoParceiroRequest`, então não existe cobrança sem ela, e o cliente HTTP sempre a envia no header ao parceiro. |
| Fallback | Fila de contingência + `202 PENDENTE` | Nunca "aprovamos" sem confirmação. Se o resultado é incerto, o pagamento vai para reconciliação. |
| Erros | `@RestControllerAdvice` + Problem Details (RFC 7807) | Resposta padronizada (`application/problem+json`), sem stack trace. |

### Connect timeout x read timeout: a distinção que protege o dinheiro

- **Falha ao conectar:** a requisição nunca chegou ao parceiro, então **com certeza nada foi cobrado**. Resposta `503`, e a Idempotency-Key é liberada para o cliente tentar de novo.
- **Estouro de leitura (ou conexão caindo no meio da resposta):** a requisição chegou e **a cobrança pode ter acontecido**. Resposta `202 PENDENTE`, o pagamento vai para a fila de reconciliação e a chave fica registrada: reenviar a mesma chave devolve o mesmo `202`, sem nova chamada.

## Contrato da API (`POST /pagamentos`)

| Status | Quando |
| :--- | :--- |
| `200` | Resposta final do parceiro: `APROVADO` ou `RECUSADO` |
| `202` | Parceiro sem resposta a tempo: `PENDENTE`, enviado para reconciliação |
| `400` | Corpo inválido ou sem `Idempotency-Key` |
| `409` | Já existe um pagamento em processamento com essa chave |
| `422` | A chave já foi usada com outro conteúdo de pagamento |
| `502` | O parceiro respondeu 5xx |
| `503` + `Retry-After` | Circuito aberto, falha de conexão, 429 do parceiro ou contingência indisponível |

Toda resposta traz o header `Idempotent-Replayed: true|false`, indicando se veio do registro de idempotência.

## Testes de caos (WireMock no papel do parceiro)

No perfil de teste: read timeout de 1 s, circuito avaliado em janelas de 4 chamadas, aberto por 1 s e com 2 chamadas de teste em HALF_OPEN.

| Cenário | Resultado verificado |
| :--- | :--- |
| Caminho feliz | `200 APROVADO`, e o parceiro recebeu a `Idempotency-Key` no header |
| Parceiro demora 3 s | API corta em ~1 s e responde `202 PENDENTE` |
| Sequência de erros 500 | Circuit Breaker vai para `OPEN` |
| Circuito `OPEN` | `503` + `Retry-After` em menos de 300 ms, **sem** chamar o parceiro |
| Erro 503 do parceiro | `502` em Problem Details, sem propagar o status do parceiro |
| HTTP 429 do parceiro | `503` com o mesmo `Retry-After` pedido pelo parceiro |
| Conexão derrubada na resposta | `202 PENDENTE` (resultado incerto) |
| Conexão recusada | `503` parceiro-inacessível; a mesma chave pode ser reenviada |
| `OPEN` → `HALF_OPEN` → `CLOSED` | Após a espera, 2 chamadas bem-sucedidas fecham o circuito |
| Reenvio após timeout | Mesmo `202` com `Idempotent-Replayed: true`; o parceiro foi chamado **uma vez** |
| Mesma chave, outro valor | `422`, sem chamar o parceiro |
| Timeout + fila de contingência cheia | `503` contingência-indisponível |
| Sem `Idempotency-Key` | `400` em Problem Details |

## O que mudaria em produção

O escopo é o do desafio: a engenharia da resiliência, com modelo de dados mínimo. Em produção, eu trocaria:

- o registro de Idempotency-Keys em memória por **Redis ou banco**, compartilhado entre instâncias e com TTL (ex.: 24 h);
- a fila em memória por uma **fila durável** (Kafka/RabbitMQ ou outbox) com um job de **reconciliação** consultando o parceiro pela chave;
- e adicionaria **Bulkhead** (limite de chamadas simultâneas, essencial com Virtual Threads), **RateLimiter** com a cota do contrato e **Retry** com backoff e jitter apenas para falhas de conexão, todos descritos no documento de System Design da Aula 02.
