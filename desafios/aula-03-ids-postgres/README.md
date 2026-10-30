# Desafio Aula 03: Sequence × UUIDv4 × UUIDv7 no PostgreSQL

Desafio prático opcional da disciplina **PE-JV-003**. Ele testa com números a escolha de chave primária que fiz no documento de System Design da Aula 03 (UUIDv7) e mostra, com `EXPLAIN (ANALYZE, BUFFERS)`, por que a estratégia de ID muda o custo de escrita e de leitura.

Quatro tabelas idênticas recebem **1 milhão de pedidos cada**, em lotes de 1.000 linhas com `COMMIT` por lote (simulando muitas transações pequenas, como num checkout). Só muda a chave primária:

| Tabela | Chave primária |
| :--- | :--- |
| `pedidos_seq` | `bigint` IDENTITY (sequence) |
| `pedidos_uuidv4` | UUIDv4, totalmente aleatório |
| `pedidos_uuidv7_simples` | UUIDv7 ordenado só até o milissegundo |
| `pedidos_uuidv7` | UUIDv7 com fração de milissegundo (como o `uuidv7()` nativo do PostgreSQL 18) |

## Como rodar (sem Docker)

Precisa só de um PostgreSQL 13+ instalado e do `psql`.

```bash
psql -U postgres -d postgres -f sql/rodar_tudo.sql
# máquina mais lenta: reduza o volume
psql -U postgres -d postgres -v linhas=200000 -f sql/rodar_tudo.sql
```

| Script | O que faz |
| :--- | :--- |
| `sql/01_preparar.sql` | Cria as tabelas, as funções UUIDv7 e os procedimentos de carga e medição |
| `sql/02_carga.sql` | Insere o volume nas quatro tabelas e mede o custo de só gerar os ids |
| `sql/03_metricas.sql` | Tempo, WAL, tamanho, densidade e fragmentação do índice (`pgstattuple`) |
| `sql/04_explain.sql` | Planos de execução: busca por id, "mais recentes" e a consulta lenta do incidente |

## Resultados

Medido em PostgreSQL 16, 1 vCPU, 4 GB de RAM, `shared_buffers` padrão (128 MB). Os números absolutos mudam de máquina para máquina; o que importa é a proporção entre as estratégias. A saída completa está em [`resultados/saida-completa.txt`](resultados/saida-completa.txt).

### Inserção e índice

| Estratégia | Tempo líquido* | WAL gerado | Tamanho do índice | Densidade das folhas | Fragmentação das folhas |
| :--- | ---: | ---: | ---: | ---: | ---: |
| Sequence | **3,50 s** | 150 MB | **21 MB** | 90% | 0% |
| UUIDv7 | **3,95 s** | 164 MB | 30 MB | 90% | 0% |
| UUIDv7 simples | 4,26 s | 169 MB | 39 MB | 69% | 30% |
| UUIDv4 | 6,61 s | 178 MB | 38 MB | 71% | **50%** |

\* Tempo total da carga menos o custo de só gerar os ids. As funções UUIDv7 foram escritas em SQL e são lentas para gerar (6,5 s por milhão); em produção o id vem da aplicação Java ou do `uuidv7()` nativo do PostgreSQL 18, então o que interessa comparar é o trabalho de gravar e indexar.

**Leitura dos números:**

- **UUIDv4 é o pior em escrita:** quase o dobro do tempo da sequence, mais WAL, e metade das páginas folha do índice fora de ordem. Cada id cai num ponto aleatório da B-Tree, forçando page splits por toda a árvore e deixando as páginas 30% vazias.
- **UUIDv7 fica perto da sequence:** os ids novos sempre vão para o fim do índice, como uma sequence. As páginas ficam 90% cheias e sem fragmentação. O índice é maior que o da sequence só porque um UUID ocupa 16 bytes e um `bigint`, 8.
- **O detalhe que pouca gente vê, o UUIDv7 "simples":** se só o milissegundo é ordenado e o resto é aleatório, as centenas de ids gerados no mesmo milissegundo entram fora de ordem. Sob alta vazão, o índice fragmenta (30%) e fica tão grande quanto o do UUIDv4. Por isso a implementação precisa usar a fração de milissegundo (ou um contador), como faz o `uuidv7()` do PostgreSQL 18.
- Neste teste o índice cabe na memória. Em produção, com índices maiores que a RAM, o UUIDv4 fica ainda pior, porque cada inserção aleatória tende a ler uma página do disco.

### Leitura (`EXPLAIN ANALYZE`)

| Consulta | Sequence | UUIDv4 | UUIDv7 |
| :--- | ---: | ---: | ---: |
| A) Buscar um pedido pelo id | 0,03 ms · 4 buffers | 0,03 ms · 4 buffers | 0,01 ms · 4 buffers |
| B) "10.000 pedidos mais recentes" pela PK | **1,9 ms** · 133 buffers | **30,3 ms** · 10.108 buffers | **3,3 ms** · 163 buffers |

- **Busca por id:** todas as estratégias são equivalentes. A diferença não está em achar uma linha, está em escrever e em ler faixas.
- **Pedidos mais recentes:** com sequence e UUIDv7, ordem do id e ordem física das linhas coincidem (correlação 1,0), então a consulta lê poucas páginas vizinhas. No UUIDv4 a correlação é zero: lê ~10 mil páginas espalhadas, 9× mais lento, e **devolve o resultado errado**, porque `ORDER BY id` não significa ordem de tempo. Os 5 "maiores" UUIDv4 foram criados ao longo de 6 segundos de carga; os 5 maiores UUIDv7 foram criados no mesmo instante, os últimos da carga.

### A consulta do incidente (`WHERE cliente_id = ?`)

| | Plano | Tempo | Buffers |
| :--- | :--- | ---: | ---: |
| Sem índice | `Parallel Seq Scan`, removendo ~333 mil linhas por worker | 85 ms | 8.334 |
| Com índice em `cliente_id` | `Bitmap Index Scan` | 0,08 ms | 23 |

É exatamente o que derrubou o p99 e esgotou o HikariCP no cenário da Aula 03: a busca varre a tabela inteira. O índice certo resolve, mas cada índice também encarece toda escrita, por isso a contraproposta do documento é índice composto e parcial, não "indexar tudo".

## Conclusão

Os números confirmam a escolha do documento: **UUIDv7** entrega quase a mesma eficiência de escrita e de leitura por faixa da sequence, sem depender do banco para gerar o id, sem expor o volume de pedidos e funcionando entre serviços e bancos diferentes. O cuidado é usar uma implementação que mantenha a ordem dentro do milissegundo.

## E no JPA?

- `@GeneratedValue(strategy = IDENTITY)` impede o Hibernate de agrupar INSERTs em lote (ele precisa do id do banco a cada linha). Se for usar número, prefira `SEQUENCE` com `allocationSize` maior que 1.
- Para UUIDv7, gere o id na aplicação antes de salvar, por exemplo com a biblioteca `uuid-creator` (`UuidCreator.getTimeOrderedEpoch()`). Assim o id existe antes do INSERT, o que ajuda com Idempotency-Key e com eventos do Outbox.
