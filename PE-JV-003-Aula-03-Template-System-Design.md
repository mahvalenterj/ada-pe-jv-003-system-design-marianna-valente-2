# Template de System Design: Aula 03 - Banco de Dados em Larga Escala

> **Contexto do Cenário: Checkout na Black Friday**
>
> Sua equipe está preparando a persistência da API de Checkout para o pico de tráfego da Black Friday:
>
> 1. **Armazenamento:** Transações financeiras de pedidos exigem garantias ACID rigorosas e consistência imediata, enquanto dados de catálogo e sessões de usuários demandam alta vazão de leitura e tolerância a partição.
> 2. **Concorrência:** A atualização de status do pedido tem baixa disputa por registro (apenas o comprador interage), mas a **reserva de estoque** sofre altíssima contenção com centenas de clientes disputando as últimas unidades do mesmo SKU promocional.
> 3. **Incidente em Produção:** Em testes de carga, o tempo de resposta p99 subiu para 4.8s e o pool HikariCP saturou devido a consultas lentas de pedidos (`WHERE customer_id = ?` e `WHERE order_status = 'processing'`).
>
> **Instruções:** Responda às decisões arquiteturais abaixo fundamentando suas escolhas estritamente com base nos requisitos e restrições deste cenário. Adicione este bloco ao repositório contínuo do seu projeto.

---

## 1. Decisão de Armazenamento e Chave Primária (PK)

### 1.1 Motor de Banco de Dados e Paradigma de Persistência

- **A Escolha para o Checkout:** ( ) Relacional Puro (PostgreSQL) (X) Persistência Poliglota / Híbrida
- **Justificativa Arquitetural:** Avalie o Teorema CAP e o modelo de consistência (ACID vs BASE). Por que a sua escolha atende aos requisitos do Checkout sem comprometer a integridade financeira?
  > O núcleo financeiro (pedidos, pagamentos, estoque) fica no **PostgreSQL**, com ACID e consistência imediata. No CAP, diante de uma partição, preferimos consistência a disponibilidade: é melhor recusar um pedido do que gravar dinheiro ou estoque errado.
  >
  > Catálogo e sessões têm outro perfil: muita leitura, dados que toleram ficar alguns segundos desatualizados e necessidade de continuar respondendo mesmo com partição (lado AP, modelo BASE). Colocá-los em armazenamentos próprios tira essa carga de leitura do PostgreSQL, que fica livre para as escritas transacionais do Checkout.
  >
  > A integridade financeira não é comprometida porque **nenhum dado de dinheiro ou saldo de estoque vive no lado BASE**. O que é eventualmente consistente é só o que pode ser.
- **Além do Modelo Documental (Ecossistema NoSQL):** Considerando as necessidades complementares de uma plataforma de e-commerce, cite e comente pelo menos outros dois tipos/famílias de bancos NoSQL que poderiam coexistir na arquitetura, indicando para quais casos de uso específicos cada um seria a ferramenta ideal e por quê.
  > - **Chave-valor em memória (Redis):** carrinhos efêmeros e sessões. Leitura e escrita em submilissegundos, TTL nativo para expirar carrinho abandonado e fácil de escalar horizontalmente. Perder um carrinho é aceitável; perder um pedido, não.
  > - **Motor de busca (Elasticsearch/OpenSearch):** busca textual no catálogo, com índice invertido, relevância, filtros facetados, sinônimos e tolerância a erro de digitação, coisas que um `LIKE` no relacional não faz bem nessa escala. É alimentado de forma assíncrona a partir do catálogo.
  > - **Séries temporais (Prometheus/InfluxDB):** telemetria e métricas. Otimizado para escrita contínua com timestamp, agregação por janela de tempo e retenção com descarte automático.
  > - **Grafo (Neo4j):** análise de fraude. Encontrar conexões entre contas, cartões, dispositivos e endereços (anéis de fraude) são consultas de relacionamento que no relacional viram muitos JOINs recursivos.
  > - **Colunar largo (Cassandra):** auditoria e logs de eventos com altíssimo volume de escrita, consultados por chave e tempo.

### 1.2 Estratégia da Chave Primária

- **A Escolha:** ( ) Sequence / Identity ( ) UUIDv4 (X) UUIDv7 / TSID
- **Impacto no Armazenamento:** Por que essa estratégia foi escolhida considerando inserções concorrentes e o impacto de fragmentação na árvore B-Tree (page splits)?
  > O UUIDv7 começa com o timestamp, então as chaves novas são **quase sempre crescentes**. As inserções vão para o "lado direito" do índice B-Tree, como numa sequence, evitando os page splits espalhados e a fragmentação causados pelo UUIDv4, que é totalmente aleatório e grava em páginas por todo o índice, piorando cache e I/O.
  >
  > Ao mesmo tempo, o UUIDv7 é gerado na aplicação sem coordenação central: não depende de uma sequence no banco, funciona entre serviços e bancos diferentes, permite conhecer o ID antes do INSERT (útil para Idempotency-Key e eventos) e não expõe volume de vendas como um ID sequencial exposto na URL faria. Se o tamanho importar, o TSID (64 bits) tem as mesmas vantagens ocupando metade do espaço.

---

## 2. A Dualidade da Concorrência: Locks e Mitigações

### 2.1 Mudança de Status do Pagamento (Baixa contenção por registro)

- **Abordagem de Lock:** (X) Lock Otimista (`@Version`) ou ( ) Lock Pessimista (`SELECT FOR UPDATE`)?
- **Justificativa:** Por que essa abordagem é a mais adequada para este cenário específico?
  > Só o próprio comprador (e o retorno do gateway) mexe em cada pedido, então conflito é raro. O lock otimista não segura nenhum lock nem conexão esperando: lê, valida e grava com `WHERE version = ?`. No caso raro de conflito (ex.: webhook do gateway chegando junto com outra atualização), uma `OptimisticLockException` avisa, e relemos e reaplicamos a transição. Combinado com a validação da máquina de estados (não deixar "aprovado" voltar para "pendente"), isso também protege contra webhooks duplicados ou fora de ordem.

### 2.2 Controle de Estoque Concorrente (Alta contenção no mesmo registro)

- **Abordagem de Lock:** ( ) Lock Otimista (`@Version`) ou (X) Lock Pessimista (`SELECT FOR UPDATE`)?
- **Justificativa:** Centenas de usuários disputando os últimos itens na Black Friday. Como você garante que o saldo nunca fique negativo sem derrubar o banco?
  > Com alta contenção, o lock otimista viraria uma tempestade de conflitos: centenas de tentativas falhando e refazendo ao mesmo tempo, gerando mais carga do que trabalho útil. Com bloqueio da linha, as reservas são **serializadas**: uma de cada vez, sem retrabalho.
  >
  > Na prática, implementamos o pessimismo da forma mais curta possível, com um **UPDATE condicional atômico**, que trava a linha só pelo tempo da instrução:
  > ```sql
  > UPDATE estoque SET saldo = saldo - :qtd
  >  WHERE sku = :sku AND saldo >= :qtd;
  > ```
  > Se nenhuma linha for afetada, acabou o estoque. Uma `CHECK (saldo >= 0)` na tabela é a última barreira: mesmo com bug na aplicação, o banco recusa saldo negativo. A transação de reserva é curta e **nunca inclui a chamada ao pagamento**, para não segurar o lock enquanto espera rede.

### 2.3 Riscos e Mitigações

- **Análise Crítica:** Quais os riscos operacionais das abordagens de lock que você escolheu? Como sua arquitetura mitiga esses riscos?
  > - **Otimista (status do pagamento):** o risco é excesso de `OptimisticLockException`. Como a contenção é baixa, é raro; quando ocorre, fazemos poucos retries com backoff e, esgotados, devolvemos uma resposta clara em vez de erro genérico.
  > - **Pessimista (estoque):** os riscos são filas de threads esperando lock, saturação do HikariCP, `ConnectionTimeoutException` e deadlocks. Mitigações:
  >   - transação mínima, sem chamada externa dentro dela;
  >   - `SET LOCAL lock_timeout` curto (ex.: 2 s) e `statement_timeout`, para falhar rápido em vez de enfileirar para sempre;
  >   - carrinho com vários SKUs trava as linhas **sempre na mesma ordem** (ordenando por SKU), eliminando o ciclo que causa deadlock;
  >   - Bulkhead na frente da reserva do SKU promocional, limitando quantas requisições disputam a mesma linha, e `connectionTimeout` do Hikari curto;
  >   - reserva temporária com expiração: se o pagamento não confirma no prazo, um job devolve a unidade ao saldo.

---

## 3. Troubleshooting do Incidente e Gestão de Índices

### 3.1 Investigação de Incidente em Produção

- **Plano de Diagnóstico:** Qual é o seu processo técnico passo a passo para investigar a causa raiz dessa lentidão diretamente no banco de dados? Quais comandos, métricas ou planos você analisaria para comprovar a hipótese antes de propor qualquer mudança?
  > 1. **Confirmar o sintoma pelas métricas:** HikariCP (conexões ativas, threads aguardando, tempo para obter conexão) e p99 por endpoint. Isso confirma que as conexões estão presas no banco, e não na aplicação.
  > 2. **Ver o que está rodando agora:** `pg_stat_activity` (consultas ativas, duração, `wait_event`). Se a espera for de lock (`pg_locks`), o problema é concorrência; se for I/O ou CPU, é consulta cara.
  > 3. **Encontrar as consultas mais caras:** `pg_stat_statements`, ordenando por `total_exec_time` e `mean_exec_time`, para confirmar que a consulta por `customer_id` e `order_status` lidera.
  > 4. **Ler o plano real:** `EXPLAIN (ANALYZE, BUFFERS)` com parâmetros reais. A hipótese se comprova se aparecer `Seq Scan` na tabela de pedidos, muitas "Rows Removed by Filter" e muitos buffers lidos.
  > 5. **Verificar índices e estatísticas:** `\d pedidos` para ver índices existentes; `pg_stat_user_tables` (`seq_scan` alto e `idx_scan` baixo, `n_dead_tup`, último `autovacuum`/`analyze`). Estatísticas velhas ou tabela inchada também enganam o planejador.
  > 6. **Reproduzir em homologação** com volume parecido com produção, testar o índice proposto e comparar o `EXPLAIN ANALYZE` antes e depois. Só então abrir a mudança.

### 3.2 Decisão de Engenharia: A Abordagem de Índices

- **Seu Posicionamento:** Como responsável técnico, você aprovaria esse PR? Qual é a sua avaliação crítica sobre essa estratégia e quais impactos colaterais sistêmicos devem ser ponderados antes de aprovar novos índices?
  > **Não aprovaria como está.** Índice para toda coluna filtrada é uma solução no escuro:
  >
  > - **Não resolve bem a consulta real:** para `customer_id = ? AND order_status = 'processing'`, dois índices separados obrigam o PostgreSQL a combinar bitmaps, o que é pior do que um único índice certo.
  > - **Encarece toda escrita:** a 5.000 TPS, cada INSERT/UPDATE precisa atualizar todos os índices. Isso aumenta latência de escrita, volume de WAL, replicação, uso de disco e trabalho do vacuum.
  > - **Quebra os HOT updates:** indexar `order_status`, que muda a cada etapa do pedido, impede atualizações HOT, gerando mais escrita e mais inchaço.
  > - **Disputa memória:** mais índices competem pelo cache (`shared_buffers`) com os dados mais usados.
  > - **Criar índice trava a tabela,** a menos que seja `CREATE INDEX CONCURRENTLY`.
  >
  > Contraproposta: um **índice composto** `(customer_id, order_status)` para a consulta principal, e, para a busca de pedidos em processamento, um **índice parcial** `WHERE order_status = 'processing'`, pequeno porque cobre só a fração ativa. Criados com `CONCURRENTLY` via migração, validados com `EXPLAIN ANALYZE`, e acompanhados por `pg_stat_user_indexes` para remover depois o que não for usado.
