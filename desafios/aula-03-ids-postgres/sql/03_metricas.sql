-- =====================================================================
-- Passo 3: comparar tempo de carga, WAL gerado e saúde do índice B-Tree
-- =====================================================================

\echo '--- Tempo de inserção e WAL gerado ---'
\echo '    segundos_liquidos = tempo total menos o custo de só gerar os ids (trabalho real de gravar e indexar)'
SELECT r.tabela,
       r.linhas,
       round(extract(epoch FROM r.duracao)::numeric, 2)                           AS segundos_total,
       round(extract(epoch FROM g.duracao)::numeric, 2)                           AS segundos_gerando_id,
       round(extract(epoch FROM r.duracao - g.duracao)::numeric, 2)               AS segundos_liquidos,
       pg_size_pretty(r.wal_gerado)                                               AS wal_gerado
  FROM resultados_carga r
  JOIN custo_geracao g USING (tabela)
 ORDER BY segundos_liquidos;

\echo '--- Tamanho e fragmentação do índice da chave primária ---'
SELECT t.tabela,
       pg_size_pretty(pg_relation_size(t.tabela::regclass))             AS tamanho_tabela,
       pg_size_pretty(pg_relation_size((t.tabela || '_pkey')::regclass)) AS tamanho_indice,
       i.avg_leaf_density                                                AS densidade_folhas_pct,
       i.leaf_fragmentation                                              AS fragmentacao_folhas_pct
  FROM (VALUES ('pedidos_seq'), ('pedidos_uuidv4'), ('pedidos_uuidv7_simples'), ('pedidos_uuidv7')) AS t(tabela),
       LATERAL pgstatindex(t.tabela || '_pkey') AS i
 ORDER BY t.tabela;

\echo '--- Correlação entre a ordem do id e a ordem física das linhas (1 = mesma ordem) ---'
SELECT tablename AS tabela, round(correlation::numeric, 3) AS correlacao
  FROM pg_stats
 WHERE tablename IN ('pedidos_seq', 'pedidos_uuidv4', 'pedidos_uuidv7_simples', 'pedidos_uuidv7')
   AND attname = 'id'
 ORDER BY tablename;
