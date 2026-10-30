-- =====================================================================
-- Passo 4: planos de execução com EXPLAIN (ANALYZE, BUFFERS)
-- Cole os planos em https://explain.dalibo.com para ver em forma de árvore.
-- =====================================================================

-- ids de exemplo, pegos do meio de cada tabela
SELECT id AS id_seq FROM pedidos_seq    ORDER BY criado_em OFFSET 500 LIMIT 1 \gset
SELECT id AS id_v4  FROM pedidos_uuidv4 ORDER BY criado_em OFFSET 500 LIMIT 1 \gset
SELECT id AS id_v7  FROM pedidos_uuidv7 ORDER BY criado_em OFFSET 500 LIMIT 1 \gset

\echo '=== A) Busca de um pedido pelo id (todas usam o índice) ==='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM pedidos_seq    WHERE id = :'id_seq';
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM pedidos_uuidv4 WHERE id = :'id_v4';
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM pedidos_uuidv7 WHERE id = :'id_v7';

\echo '=== B) "Os 10.000 pedidos mais recentes" usando só a chave primária ==='
\echo '    Sequence e UUIDv7 leem páginas vizinhas; no UUIDv4 a ordem do id é aleatória:'
\echo '    além de ler páginas espalhadas pela tabela inteira, ele NÃO devolve os mais recentes.'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM pedidos_seq    ORDER BY id DESC LIMIT 10000;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM pedidos_uuidv4 ORDER BY id DESC LIMIT 10000;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM pedidos_uuidv7 ORDER BY id DESC LIMIT 10000;

\echo '=== C) Prova de que o UUIDv4 não tem ordem de tempo: data dos 5 "maiores" ids ==='
SELECT 'uuidv4' AS estrategia, min(criado_em) AS mais_antigo, max(criado_em) AS mais_recente
  FROM (SELECT criado_em FROM pedidos_uuidv4 ORDER BY id DESC LIMIT 5) x
UNION ALL
SELECT 'uuidv7', min(criado_em), max(criado_em)
  FROM (SELECT criado_em FROM pedidos_uuidv7 ORDER BY id DESC LIMIT 5) x;

\echo '=== D) A consulta lenta do incidente: WHERE cliente_id = ? sem índice ==='
SELECT cliente_id AS cliente FROM pedidos_uuidv7 LIMIT 1 \gset
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM pedidos_uuidv7 WHERE cliente_id = :cliente;

CREATE INDEX IF NOT EXISTS idx_pedidos_uuidv7_cliente ON pedidos_uuidv7 (cliente_id);
ANALYZE pedidos_uuidv7;

\echo '    ... e depois de criar o índice em cliente_id:'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM pedidos_uuidv7 WHERE cliente_id = :cliente;
