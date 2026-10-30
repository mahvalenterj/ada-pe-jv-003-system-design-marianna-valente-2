-- =====================================================================
-- Passo 2: inserir o mesmo volume nas três tabelas
-- Ajuste :linhas se a sua máquina for lenta (ex.: psql -v linhas=200000 ...)
-- =====================================================================
\if :{?linhas}
\else
  \set linhas 1000000
\endif
\set lote 1000

\echo 'Carregando' :linhas 'linhas em cada tabela, em lotes de' :lote '...'

CHECKPOINT;
CALL carregar('pedidos_seq',    :linhas, :lote);
CHECKPOINT;
CALL carregar('pedidos_uuidv4', :linhas, :lote);
CHECKPOINT;
CALL carregar('pedidos_uuidv7_simples', :linhas, :lote);
CHECKPOINT;
CALL carregar('pedidos_uuidv7', :linhas, :lote);

VACUUM ANALYZE pedidos_seq;
VACUUM ANALYZE pedidos_uuidv4;
VACUUM ANALYZE pedidos_uuidv7_simples;
VACUUM ANALYZE pedidos_uuidv7;

\echo 'Medindo o custo de só gerar os ids...'
CALL medir_geracao('pedidos_seq',            'nextval(''seq_medicao'')', :linhas);
CALL medir_geracao('pedidos_uuidv4',         'gen_random_uuid()',        :linhas);
CALL medir_geracao('pedidos_uuidv7_simples', 'uuid_v7_simples()',        :linhas);
CALL medir_geracao('pedidos_uuidv7',         'uuid_v7()',                :linhas);
