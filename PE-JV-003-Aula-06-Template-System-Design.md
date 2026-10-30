# Template de System Design: Aula 06 - Observabilidade & Diagnóstico em Produção

> **Contexto do Cenário: O Checkout que Trava sem Dar Erro**
>
> Sua equipe responde pelo serviço de **Checkout** do marketplace, que chama **Pagamento** e **Estoque**. Os três rodam em imagens *Distroless*.
>
> 1. **O Incidente:** Na abertura da Black Friday, com **5.000 TPS**, uma versão nova do Checkout entrou em *Canary*. Minutos depois, os painéis continuavam verdes, com latência média de 120 ms, CPU abaixo de 40% e nenhum pico de HTTP 500.
> 2. **O Impacto:** A conversão despencou. Milhares de reclamações chegaram ao suporte, todas com a mesma cena. O cliente clica em "Finalizar Pedido", a tela fica carregando e o aplicativo desiste por timeout.
> 3. **As Restrições:** A imagem não tem `sh` e não há SSH nos servidores. Cada serviço escreve logs em texto livre, do seu jeito, sem um identificador que ligue a chamada do Checkout à chamada que ele fez ao Pagamento. E dado de cliente que vai parar em log continua sujeito à LGPD.
>
> **Instruções:** Responda às decisões arquiteturais abaixo fundamentando suas escolhas estritamente com base nos requisitos e restrições deste cenário. Documente suas decisões no repositório contínuo do seu projeto através de commits atômicos e bem descritivos.

---

## 1. Logs e Correlação

- **Campos do Log:** Que campos todo log do Checkout precisa ter para você encontrar, no meio de milhares de linhas, as da requisição que travou? Liste os campos e diga para que serve cada um.
  > Logs **estruturados em JSON**, com o mesmo formato nos três serviços:
  >
  > | Campo | Para que serve |
  > | :--- | :--- |
  > | `timestamp` (UTC, milissegundos) | ordenar eventos entre serviços |
  > | `level` | filtrar WARN/ERROR |
  > | `service` | saber quem escreveu (checkout, pagamento, estoque) |
  > | `version` / `commit` | separar **canary de estável**, a chave deste incidente |
  > | `environment` e `pod`/`instance` | isolar ambiente e réplica com problema |
  > | `trace_id` | juntar todas as linhas de uma mesma compra, em todos os serviços |
  > | `span_id` | saber em qual etapa/chamada a linha aconteceu |
  > | `http.route` (template, ex.: `/checkout/{id}`) e `http.method` | agrupar por endpoint sem IDs na rota |
  > | `http.status` e `duration_ms` | ver resultado e tempo de cada chamada |
  > | `peer.service` e `outcome` | qual dependência foi chamada e se respondeu, falhou ou estourou o tempo |
  > | `order_id` | buscar a compra que o suporte reportou |
  > | `saga_state` | em que passo o pedido parou |
  > | `error.type` e `error.message` | causa técnica, sem dados pessoais |
- **O Caminho do Contexto:** Como o mesmo identificador de rastreamento passa do Checkout para o Pagamento e o Estoque numa chamada HTTP?
  > Pelo cabeçalho padrão **W3C Trace Context** (`traceparent`, com `tracestate` e `baggage` quando necessário). Com o agente Java do OpenTelemetry (ou Micrometer Tracing), o cliente HTTP do Checkout **injeta** o `traceparent` automaticamente em cada chamada; o Pagamento e o Estoque **extraem** o cabeçalho e continuam o mesmo trace, criando spans filhos. O `trace_id` e o `span_id` são colocados no MDC, então aparecem em toda linha de log. Nas mensagens assíncronas da Saga, o mesmo contexto viaja nos cabeçalhos da mensagem.
- **LGPD nos Logs:** Quais dados de cliente ficam fora de logs e traces? Para os identificadores que precisam aparecer, qual tratamento você aplica e quem pode ler esses registros?
  > - **Nunca entram:** nome, CPF, e-mail, telefone, endereço, dados de cartão (número, CVV, validade), chaves Pix, tokens, senhas e corpos completos de requisição e resposta.
  > - **Identificadores necessários:** o cliente aparece só como **pseudônimo** (HMAC do ID com chave secreta; um hash simples de CPF pode ser revertido por força bruta). Quando algo precisa ser exibido, vai mascarado (ex.: `***.456.789-**`). O `order_id` é um ID interno, sem dado pessoal.
  > - **Garantia técnica:** filtros de redação no appender de log e no Collector do OpenTelemetry removem padrões de CPF, e-mail e cartão mesmo se alguém logar por engano.
  > - **Acesso:** controle por papel (time de plantão e SRE), com auditoria de quem consultou, e retenção limitada (ex.: 30 dias), com exclusão automática.

---

## 2. Métricas

- **Visão do Cliente (RED):** Quais métricas do Checkout entram no painel? Explique como você mede a duração para que o travamento de uma parte dos clientes não desapareça na média.
  > - **Rate:** requisições por segundo por rota, status e **versão**.
  > - **Errors:** 5xx **e também** timeouts, requisições abandonadas pelo cliente e falhas de negócio (Saga que não concluiu). Neste incidente não havia 500: o erro era o cliente desistir, então medir só 5xx esconde o problema.
  > - **Duration:** **histograma** de latência, com percentis p50, p95, p99 e p99.9, nunca a média. Se 3% dos checkouts travam por 30 s e 97% levam 100 ms, a média fica perto de 1 s, mas o p99 mostra 30 s. Usamos histogramas agregáveis entre instâncias (não summaries), e também a porcentagem de requisições acima do SLO (ex.: acima de 2 s).
  > - **Métrica de negócio:** taxa de conversão do checkout (iniciados × confirmados) e requisições em andamento (in-flight). Foi a conversão que caiu primeiro.
  > - Tudo **comparando canary com estável**, lado a lado.
- **Visão dos Recursos (USE):** Que recursos você monitora? Que sinal acusaria saturação antes do primeiro HTTP 500?
  > Pool do HikariCP (ativas, threads esperando, tempo para obter conexão), permissões do Bulkhead e estado do Circuit Breaker, conexões do cliente HTTP com Pagamento e Estoque, threads, heap e pausas de GC da JVM, CPU e memória, tamanho do outbox e lag dos consumidores do broker.
  >
  > CPU baixa com cliente esperando significa que o tempo está indo em **espera**, não em processamento. Os sinais de saturação antes de qualquer 500 são: **threads aguardando conexão e tempo de aquisição do pool subindo**, Bulkhead sem permissões livres, requisições in-flight crescendo, outbox acumulando e lag aumentando.
- **Labels:** Quais labels você permite nessas métricas e quais proíbe? Diga o efeito de cada escolha no servidor de métricas.
  > - **Permitidos (baixa cardinalidade):** `service`, `version`, `route` (template), `method`, `status`, `dependency`, `outcome`, `instance`. Cada um tem poucos valores possíveis, então o número de séries fica estável e as consultas continuam rápidas.
  > - **Proibidos:** `customer_id`, `order_id`, `trace_id`, URL bruta com IDs, e-mail, SKU. Cada valor novo cria uma **série temporal nova**: a 5.000 TPS, isso explode a cardinalidade, consumindo memória e CPU do Prometheus até deixá-lo lento ou derrubá-lo por falta de memória, além de aumentar o custo e vazar dado pessoal.
  > - Para ir da métrica ao caso concreto, usamos **exemplars**: o histograma guarda alguns `trace_id` de exemplo sem virar label.

---

## 3. Traces e Amostragem

- **A Escolha:** ( ) Head Sampling  ( ) Tail Sampling  (X) Combinação das duas
- **Justificativa:** Guardar 100% dos traces a 5.000 TPS custa caro. Qual é a sua regra de retenção, e como ela evita que justamente as requisições que travaram sejam descartadas?
  > O head sampling decide no **início** da requisição, antes de saber se ela vai travar; usado sozinho a 1%, descartaria 99% dos travamentos. Por isso:
  >
  > - **Head sampling** só para cortar ruído previsível na origem: health checks, métricas e rotas de baixo valor não geram trace.
  > - **Tail sampling** no OpenTelemetry Collector, que decide **depois** que o trace termina. Regras de retenção:
  >   - **100%** dos traces com erro ou timeout;
  >   - **100%** dos traces lentos (acima do SLO, ex.: 2 s);
  >   - **100%** dos traces da **versão canary** durante o rollout;
  >   - **1 a 5%** dos traces normais, como referência.
  >
  > Para funcionar: todos os spans de um trace vão para a mesma instância do Collector (exporter com balanceamento por `trace_id`), e o tempo de espera da decisão é maior que o timeout do cliente. O Checkout também tem **timeout do lado do servidor**, então a requisição travada termina como erro e seu span fecha, em vez de ficar aberto para sempre e nunca ser avaliado.

---

## 4. Estratégia de Testes

- **Qual a nossa estratégia para testar isso e garantir que funciona?** Antes da próxima Black Friday, como você prova que os painéis e os alertas mostrariam este incidente?
  > Reproduzindo o incidente de propósito (**game day**) em homologação:
  >
  > 1. Teste de carga (k6/Gatling) com tráfego proporcional ao da Black Friday e uma versão canary.
  > 2. Injeção de falha só no canary: Toxiproxy ou WireMock fazendo **parte** das chamadas ao Pagamento (ex.: 3%) demorar mais que o timeout do app, sem retornar erro.
  > 3. **O que precisa acontecer, e é verificado automaticamente:**
  >    - o p99 e a porcentagem acima do SLO do canary disparam, mesmo com a média normal;
  >    - o **alerta de SLO** (burn rate) dispara e chega ao plantão;
  >    - a análise do canary aborta o rollout sozinha;
  >    - os traces lentos foram mantidos pelo tail sampling, e é possível ir do exemplar no painel até o trace e dele até os logs dos três serviços pelo mesmo `trace_id`.
  > 4. **Testes automatizados na pipeline:** todo log tem `trace_id` e os campos obrigatórios; um teste com CPF e e-mail falsos garante que eles **não** aparecem nos logs nem nos traces.
  > 5. **Monitoramento sintético:** um checkout de teste roda continuamente em produção, medindo do ponto de vista do cliente, que foi justamente quem percebeu o incidente primeiro.
