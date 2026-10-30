# Template de System Design: Aula 02 - APIs Resilientes



## 1. Escolha de Paradigma da API

- **A Escolha:** (X) Spring MVC com Virtual Threads ou ( ) Spring WebFlux (Reativo)?
- **Justificativa:** Considerando a capacidade de suportar 10k acessos concorrentes, por que você fez essa escolha técnica?
  > A carga do Checkout é dominada por I/O: a maior parte do tempo de cada requisição é espera pelo parceiro de pagamento e pelo banco. Com Virtual Threads, cada requisição ganha uma thread barata, e uma thread bloqueada esperando I/O não ocupa uma thread do sistema operacional. Isso dá a mesma escalabilidade de concorrência que o WebFlux entregaria para 10k usuários, mantendo o código imperativo, fácil de ler, depurar e testar.
  >
  > Também mantemos compatibilidade com o ecossistema bloqueante que já usamos (JDBC/JPA, HikariCP, Resilience4j, MDC para logs). O WebFlux só compensaria se toda a cadeia fosse reativa (R2DBC, clientes reativos), o que aumenta a complexidade sem ganho real aqui, porque o gargalo não é o número de threads e sim o parceiro externo e o pool de conexões.
  >
  > Cuidado que assumimos: Virtual Threads não limitam concorrência sozinhas. Sem controle, 10k threads virtuais podem inundar o parceiro e esgotar o pool do banco, por isso o Bulkhead (seção 2) é obrigatório. Também evitamos blocos `synchronized` longos no caminho de I/O, que podem prender a thread virtual à thread de plataforma (pinning) no Java 21.

## 2. Desenho de Resiliência

Dependemos de uma API terceira instável. Quais recursos do padrão de resiliência você vai utilizar?

- **Proteção contra Falhas e Lentidão:**
  Quais padrões (Circuit Breaker, TimeLimiter, RateLimiter, Bulkhead, Retry) você implementaria na chamada para o Parceiro de Pagamentos? Por que?
  > Usaria os cinco, cada um resolvendo um problema diferente, na ordem padrão do Resilience4j: `Retry ( CircuitBreaker ( RateLimiter ( TimeLimiter ( Bulkhead ( chamada ) ) ) ) )`.
  >
  > - **TimeLimiter + timeouts do cliente HTTP:** limite um pouco acima do p99 normal do parceiro (ex.: 2 s). Nenhuma requisição fica presa indefinidamente. O timeout de leitura/conexão no cliente HTTP é o que de fato libera a conexão.
  > - **Bulkhead (semáforo):** limita quantas chamadas simultâneas vão ao parceiro. Com Virtual Threads é essencial, porque é ele que impede que a lentidão do parceiro consuma todos os recursos da API.
  > - **Circuit Breaker:** abre por taxa de falha e também por taxa de chamadas lentas (slow call rate), numa janela deslizante. Aberto, falha rápido em vez de empilhar requisições; em half-open, deixa passar poucas chamadas de teste.
  > - **RateLimiter:** respeita a cota contratada com o parceiro, para não sermos bloqueados por excesso de chamadas.
  > - **Retry:** poucas tentativas (ex.: 2), com backoff exponencial e jitter, só para erros transitórios (timeout de conexão, 503) e **sempre com a mesma Idempotency-Key**. Nunca repetimos erro de negócio (cartão recusado, 4xx).
  >
  > Fallback: nunca "aprovar" pagamento sem confirmação. Com o circuito aberto, o pedido fica como `PAGAMENTO_PENDENTE`, o cliente recebe uma mensagem clara (ou 503 com `Retry-After`) e a confirmação final vem por reconciliação.

- **Proteção contra Duplicidade:**
  Como a `Idempotency-Key` será utilizada na requisição do Checkout para evitar cobranças duplas? Qual o ciclo de vida dessa chave?
  > O cliente (app/front) gera um UUID por **intenção de compra** e reenvia a mesma chave em qualquer nova tentativa (clique duplo, refresh, retry automático). O servidor guarda a chave junto com o hash do payload, o status e a resposta.
  >
  > Ciclo de vida:
  > 1. **Recebida:** gravação atômica da chave (`INSERT` com restrição única). Se a gravação falha porque a chave já existe, é repetição.
  > 2. **Em processamento:** repetição com a chave em andamento recebe `409`/`202` ("já estamos processando"), sem nova cobrança.
  > 3. **Concluída:** repetição recebe a **mesma resposta salva**, sem chamar o parceiro de novo.
  > 4. **Mesma chave com payload diferente:** `422`, porque é uso indevido da chave.
  > 5. **Expirada:** a chave vale por um TTL (ex.: 24 h), suficiente para cobrir retries do cliente e a janela de reconciliação; depois é removida.
  >
  > A mesma chave (ou uma derivada dela) é repassada ao parceiro de pagamento, para que o retry interno também não gere cobrança dupla do lado dele.

## 3. Os Edge Cases (Engenharia do Caos)

3 perguntas de risco que formulei e defina a arquitetura responde a cada uma delas.

1. **E se...** o parceiro cobrar o cliente, mas a resposta não chegar até nós (timeout depois da cobrança)?
   - **Resposta do Sistema:** O timeout não é tratado como "pagamento falhou". O pedido vai para `PAGAMENTO_INCERTO` e nunca é refeito com uma chave nova. Um job de reconciliação consulta o status no parceiro pela Idempotency-Key (ou recebemos o webhook) e só então confirma ou cancela o pedido.
2. **E se...** o Circuit Breaker abrir no pico da Black Friday?
   - **Resposta do Sistema:** O Checkout falha rápido, sem segurar threads nem conexões. Os pedidos ficam pendentes com mensagem clara ao cliente, sem cobrança. Em half-open, poucas chamadas testam o parceiro antes de liberar o tráfego, e um alerta avisa o time. Havendo contrato, um segundo provedor de pagamento pode assumir.
3. **E se...** o armazenamento das Idempotency-Keys (Redis/banco) cair?
   - **Resposta do Sistema:** O Checkout falha fechado (fail closed): recusa novas cobranças com 503 em vez de cobrar sem proteção, porque cobrança dupla é pior do que um checkout temporariamente indisponível. O estado do Circuit Breaker fica em memória em cada instância, então ele continua funcionando mesmo com o banco fora.

## 4. Testando a Resiliência

- **Estratégia:** Como você vai garantir que o Circuit Breaker abre na hora certa e o Timeout não deixa a Thread presa?
  > - **Testes de integração com WireMock** simulando o parceiro: atraso fixo de 10 s, respostas 500/503 e conexão derrubada. Verificamos que a chamada retorna no tempo do TimeLimiter (não em 10 s) e que, após N falhas na janela, o `CircuitBreakerRegistry` mostra o estado `OPEN`, depois `HALF_OPEN` após o tempo de espera e `CLOSED` quando o parceiro se recupera.
  > - **Thread não presa:** depois dos timeouts, as permissões disponíveis do Bulkhead voltam ao máximo e as conexões ativas do cliente HTTP voltam a zero. Se algo ficasse preso, essas métricas não voltariam.
  > - **Idempotência:** duas requisições paralelas com a mesma chave resultam em exatamente uma chamada ao parceiro (verificado com `verify` do WireMock).
  > - **Caos de rede e carga:** Toxiproxy injetando latência e quedas, combinado a um teste de carga com k6 ou Gatling (10k usuários virtuais), observando as métricas do Resilience4j expostas no Actuator/Prometheus.
