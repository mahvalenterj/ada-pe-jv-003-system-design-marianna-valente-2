# Template de System Design: Aula 04 - Microsserviços & Padrão Saga

> **Contexto do Cenário: O Pedido Pago Sem Produto na Black Friday**
>
> Sua equipe está arquitetando a transação distribuída de compra na Black Friday para o marketplace:
>
> 1. **O Incidente:** Um cliente comprou uma Smart TV de R$ 3.000 via Pix. O gateway de pagamentos debitou o valor imediatamente e confirmou a transação. No milissegundo seguinte, o serviço de Estoque esgotou as unidades e rejeitou a reserva. O dinheiro sumiu da conta do cliente, o produto não existe para entrega e o pedido travou em um estado inconsistente.
> 2. **O Desafio Distribuído:** Não existe transação única (`@Transactional`) entre bancos de dados autônomos de Checkout, Pagamento e Estoque.
> 3. **A Carga:** 5.000 TPS de checkout simultâneo e milhões de clientes consultando o status da compra em "Meus Pedidos".
>
> **Instruções:** Responda às decisões arquiteturais abaixo fundamentando suas escolhas estritamente com base nos requisitos e restrições deste cenário. Documente suas decisões no repositório contínuo do seu projeto através de commits atômicos e bem descritivos.

---

## 1. Transações Distribuídas: Coordenação e Trade-offs

### 1.1 Volumetria e Pragmatismo: 2PC vs Saga

- **Análise Crítica:** Em quais cenários arquiteturais uma transação ACID local ou Two-Phase Commit (2PC) resolveria o problema com menor custo operacional? Por que, para o cenário de 5.000 TPS na Black Friday com serviços e bancos autônomos, o 2PC é inviável e a consistência eventual se torna necessária?
  > **Quando ACID local ou 2PC bastam:** se pedido, pagamento e estoque estivessem no mesmo banco (um monólito modular), uma transação local resolveria tudo, sendo a opção mais simples e barata. O 2PC faz sentido com poucos participantes, todos sob nosso controle, que suportam XA (ex.: dois bancos relacionais), em baixo volume e com operações curtas, como rotinas internas de backoffice.
  >
  > **Por que é inviável aqui:**
  > - Os locks ficam presos em todos os participantes durante as idas e voltas de rede da fase de preparação. A 5.000 TPS, isso vira contenção e fila.
  > - O coordenador é ponto único de falha: se ele cai entre o "prepare" e o "commit", os participantes ficam travados esperando.
  > - A disponibilidade do todo vira o produto das disponibilidades de cada participante: basta um lento para todos ficarem lentos.
  > - O gateway de Pix é externo e não participa de transação XA, assim como brokers de mensagem e a maioria dos NoSQL.
  >
  > Por isso usamos **Saga**: cada serviço faz sua transação local e, se um passo falha, ações compensatórias desfazem os anteriores. Aceitamos consistência eventual em troca de disponibilidade e escala.

### 1.2 O Modelo de Coordenação da Saga

- **A Escolha:** ( ) Coreografia (Orientada a Eventos)  (X) Orquestração (Process Manager / Máquina de Estados)
- **Justificativa e Mitigação das Desvantagens do seu Modelo:**
  > O incidente envolve dinheiro: precisamos saber, a qualquer momento, em que passo cada pedido está e quem dispara o estorno. A orquestração deixa o fluxo e a compensação explícitos num único lugar, fáceis de auditar e testar.
  >
  > - **Quem orquestra:** um componente de Saga dentro do domínio de Checkout (Order Saga), com a máquina de estados persistida no banco do próprio Checkout. Cada transição é gravada, então a Saga sobrevive a reinícios.
  > - **Como se comunica:** de forma **assíncrona**, com comandos e respostas por um broker (Kafka ou RabbitMQ), publicados via Transactional Outbox. Assim o orquestrador não segura threads esperando os outros serviços e aguenta picos e indisponibilidades temporárias.
  > - **Como evito o "serviço Deus":** o orquestrador só sabe **a ordem dos passos e o que fazer quando algo falha**. A regra de negócio fica em cada participante: o Pagamento decide como cobrar e estornar; o Estoque decide como reservar e liberar. Os participantes expõem comandos idempotentes com contratos versionados e não conhecem o orquestrador além da fila de resposta.
  >
  > **Decisão de desenho que reduz o incidente:** reservamos o estoque **antes** de cobrar (reserva temporária com prazo). Assim, o caso "cobrou e não tinha produto" passa a ser exceção, não regra. A compensação continua obrigatória para quando a reserva expira ou é perdida durante uma confirmação lenta de Pix.

---

## 2. Máquina de Estados, Compensação e Confiabilidade

### 2.1 Máquina de Estados e Fluxos Ponta a Ponta

- **Estados do Pedido:**
  > - **Inicial:** `CRIADO`
  > - **Intermediários:** `ESTOQUE_RESERVADO` → `PAGAMENTO_PENDENTE` → `PAGAMENTO_APROVADO`
  > - **Compensação:** `LIBERANDO_ESTOQUE` (pagamento recusado), `ESTORNO_PENDENTE` → `ESTORNADO` (pago sem estoque)
  > - **Finais:** `CONFIRMADO` (sucesso), `CANCELADO` (sem estoque, pagamento recusado ou estorno concluído), `REVISAO_MANUAL` (último recurso, só se o estorno esgotar todas as tentativas automáticas)
  >
  > Transições inválidas (ex.: `CANCELADO` → `CONFIRMADO`) são rejeitadas pela própria máquina de estados.
- **Detalhamento dos Fluxos:**
  > **Caminho feliz:**
  > 1. O cliente finaliza a compra; o Checkout grava o pedido como `CRIADO` e, na mesma transação, o comando `ReservarEstoque` no outbox.
  > 2. O Estoque reserva a unidade com prazo e responde `EstoqueReservado` → `ESTOQUE_RESERVADO`.
  > 3. O orquestrador envia `CobrarPagamento` com Idempotency-Key do pedido → `PAGAMENTO_PENDENTE`.
  > 4. O Pagamento recebe a confirmação do Pix (webhook) e publica `PagamentoAprovado` → `PAGAMENTO_APROVADO`.
  > 5. O orquestrador envia `ConfirmarReserva`; o Estoque baixa definitivamente → `CONFIRMADO`, e o evento `PedidoConfirmado` segue para notificação e logística.
  >
  > **Caminho de falha e compensação (estoque rejeita depois do pagamento aprovado):**
  > 1. Pedido em `PAGAMENTO_APROVADO`; o Estoque responde `ReservaRejeitada` (reserva expirou e as unidades acabaram).
  > 2. O orquestrador grava `ESTORNO_PENDENTE` e, na mesma transação, o comando `EstornarPagamento` no outbox, com chave idempotente `estorno-{pedidoId}`.
  > 3. O Pagamento executa a devolução do Pix no gateway e publica `EstornoConcluido` → `ESTORNADO` → `CANCELADO`.
  > 4. O cliente é notificado do cancelamento e da devolução, sem nenhuma intervenção humana. Se o estorno falhar, entra a política da seção 2.2.

### 2.2 Resiliência da Compensação e Consistência

- **E se a compensação falhar ou oscilar?**
  > - O estorno é **idempotente**: a chave `estorno-{pedidoId}` garante que reprocessar nunca devolve o dinheiro duas vezes.
  > - O estado `ESTORNO_PENDENTE` fica **gravado no banco**, não em memória. Um agendador reenvia o comando com backoff exponencial e jitter até receber confirmação, inclusive se o orquestrador reiniciar.
  > - Compensação é tratada como algo que **deve terminar**: erro transitório gera nova tentativa, não desistência.
  > - Esgotadas as tentativas, a mensagem vai para uma DLQ, dispara alerta e o pedido vai para `REVISAO_MANUAL`, sem nunca sumir.
  > - Um job de **reconciliação** compara periodicamente o que o gateway cobrou com o estado dos pedidos e reabre qualquer divergência ("pago" sem pedido confirmado).
- **Atomicidade entre Estado e Mensageria (Dual Write):**
  > **Transactional Outbox.** A mudança de estado e a mensagem a publicar são gravadas **na mesma transação local** (a mensagem numa tabela `outbox`). Um relay (polling ou CDC com Debezium) lê o outbox e publica no broker. Se a rede ou o broker cair, a mensagem continua no outbox e é publicada depois: nada se perde silenciosamente.
  >
  > Isso garante entrega "pelo menos uma vez", então todo consumidor é **idempotente**: guarda o ID das mensagens já processadas (tabela inbox) e ignora duplicadas.

---

## 3. Desacoplamento com CQRS e Estratégia de Testes

### 3.1 Consultas em Alta Vazão com CQRS

- **Desenho da Solução:** Como você separa a responsabilidade de escrita (Command) da consulta de status (Query)? Como o modelo de leitura é alimentado e como sua arquitetura lida com a consistência eventual?
  > - **Escrita (Command):** criação de pedido e transições da Saga vão para os bancos transacionais dos serviços. Nenhuma consulta de "Meus Pedidos" toca nesses bancos.
  > - **Leitura (Query):** uma API de consulta lê um **modelo de leitura desnormalizado**, por cliente (ex.: Redis ou tabela/réplica dedicada), já com o que a tela precisa: status, itens, valor, data.
  > - **Alimentação:** cada mudança de estado publica um evento (via outbox); um projetor consome e atualiza o modelo de leitura. Cada evento carrega um número de versão, e o projetor ignora eventos mais antigos que chegarem fora de ordem, para o status nunca "andar para trás".
  > - **Consistência eventual na prática:** ao finalizar a compra, a própria resposta do comando já devolve o status inicial, então o cliente vê o pedido na hora. Na tela, status intermediários aparecem como "Processando pagamento". Em vez de o cliente apertar F5 sem parar, usamos atualização por push (SSE/WebSocket) ou polling com intervalo crescente. O atraso da projeção é monitorado como métrica e gera alerta se passar do aceitável (ex.: alguns segundos).

### 3.2 Estratégia de Testes da Transação Distribuída

- **Plano de Testes:**
  > 1. **Testes unitários da máquina de estados:** todas as transições válidas e a rejeição das inválidas, sem infraestrutura.
  > 2. **Teste de integração do caminho de compensação**, com Testcontainers (PostgreSQL e broker reais) e WireMock no lugar do gateway:
  >    - criar o pedido e deixar o pagamento ser aprovado;
  >    - configurar o Estoque (ou seu stub) para responder `ReservaRejeitada` na confirmação;
  >    - com Awaitility, esperar até o pedido chegar a `CANCELADO`;
  >    - verificar que `EstornarPagamento` foi chamado **exatamente uma vez**, com a chave `estorno-{pedidoId}`, e que o outbox ficou vazio.
  > 3. **Testes de falha:** mensagens duplicadas, broker reiniciando no meio e gateway com timeout no primeiro estorno. O resultado esperado continua sendo um único estorno e o pedido cancelado.
  > 4. **Testes de contrato** (Pact ou Spring Cloud Contract) entre orquestrador e participantes, para que uma mudança num serviço não quebre a Saga.
  > 5. Tudo roda na pipeline a cada PR; uma falha bloqueia o merge.
