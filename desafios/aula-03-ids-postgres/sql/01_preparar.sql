-- =====================================================================
-- Desafio Aula 03 · Passo 1: preparar as três tabelas
-- Mesmas colunas, mesmo volume; só muda a estratégia de chave primária.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgstattuple;   -- mede densidade e fragmentação dos índices

DROP TABLE IF EXISTS pedidos_seq, pedidos_uuidv4, pedidos_uuidv7_simples, pedidos_uuidv7, resultados_carga, custo_geracao;
DROP SEQUENCE IF EXISTS seq_medicao;

-- UUIDv7 = 48 bits de timestamp em milissegundos + versão + bits aleatórios.
--
-- uuid_v7_simples(): só o milissegundo é ordenado; o resto é aleatório. Com centenas de
-- inserções no mesmo milissegundo, os ids desse milissegundo chegam fora de ordem.
CREATE OR REPLACE FUNCTION uuid_v7_simples() RETURNS uuid AS $$
  SELECT encode(
           set_bit(set_bit(
             overlay(uuid_send(gen_random_uuid())
                     PLACING substring(int8send(floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint) FROM 3)
                     FROM 1 FOR 6),
           52, 1), 53, 1),
         'hex')::uuid;
$$ LANGUAGE sql VOLATILE;

-- uuid_v7(): usa os 12 bits seguintes para a fração de milissegundo (precisão de ~0,25 µs),
-- como faz a função nativa uuidv7() do PostgreSQL 18. Assim os ids continuam em ordem
-- mesmo com muitas inserções por milissegundo.
CREATE OR REPLACE FUNCTION uuid_v7() RETURNS uuid AS $$
  SELECT encode(
           overlay(uuid_send(gen_random_uuid())
                   PLACING int8send((floor(ms)::bigint << 16)              -- 48 bits: milissegundos
                                    | 28672                                -- 4 bits: versão 7 (0x7000)
                                    | floor((ms - floor(ms)) * 4096)::bigint) -- 12 bits: fração do ms
                   FROM 1 FOR 8),
         'hex')::uuid
    FROM (SELECT date_part('epoch', clock_timestamp()) * 1000 AS ms) t;
$$ LANGUAGE sql VOLATILE;

-- 1) Sequence (IDENTITY): 8 bytes, sempre crescente, mas depende do banco para gerar
CREATE TABLE pedidos_seq (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cliente_id  bigint         NOT NULL,
    valor       numeric(12,2)  NOT NULL,
    criado_em   timestamptz    NOT NULL DEFAULT clock_timestamp()
);

-- 2) UUIDv4: 16 bytes, totalmente aleatório
CREATE TABLE pedidos_uuidv4 (
    id          uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    cliente_id  bigint         NOT NULL,
    valor       numeric(12,2)  NOT NULL,
    criado_em   timestamptz    NOT NULL DEFAULT clock_timestamp()
);

-- 3) UUIDv7 "simples": 16 bytes, ordenado só até o milissegundo
CREATE TABLE pedidos_uuidv7_simples (
    id          uuid           PRIMARY KEY DEFAULT uuid_v7_simples(),
    cliente_id  bigint         NOT NULL,
    valor       numeric(12,2)  NOT NULL,
    criado_em   timestamptz    NOT NULL DEFAULT clock_timestamp()
);

-- 4) UUIDv7 com fração de milissegundo: 16 bytes, ordenado pelo tempo
CREATE TABLE pedidos_uuidv7 (
    id          uuid           PRIMARY KEY DEFAULT uuid_v7(),
    cliente_id  bigint         NOT NULL,
    valor       numeric(12,2)  NOT NULL,
    criado_em   timestamptz    NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE resultados_carga (
    tabela       text,
    linhas       bigint,
    duracao      interval,
    wal_gerado   numeric
);

-- Carga em lotes com COMMIT a cada lote, simulando muitas transações pequenas (como num checkout),
-- em vez de um único INSERT gigante.
CREATE OR REPLACE PROCEDURE carregar(tabela text, total int, lote int)
LANGUAGE plpgsql AS $$
DECLARE
    inicio   timestamptz := clock_timestamp();
    wal_ini  pg_lsn      := pg_current_wal_lsn();
    feitas   int         := 0;
BEGIN
    WHILE feitas < total LOOP
        EXECUTE format(
            'INSERT INTO %I (cliente_id, valor)
             SELECT (random() * 100000)::bigint, round((random() * 1000)::numeric, 2)
               FROM generate_series(1, %s)', tabela, lote);
        COMMIT;
        feitas := feitas + lote;
    END LOOP;

    INSERT INTO resultados_carga
    VALUES (tabela, total, clock_timestamp() - inicio, pg_wal_lsn_diff(pg_current_wal_lsn(), wal_ini));
    COMMIT;
END $$;

-- Quanto custa só GERAR os ids, sem inserir nada. As funções UUIDv7 em SQL são bem mais
-- lentas que uma implementação nativa (Java na aplicação ou uuidv7() do PostgreSQL 18),
-- então descontamos esse custo para comparar o trabalho de verdade no índice.
CREATE SEQUENCE seq_medicao;

CREATE TABLE custo_geracao (
    tabela    text,
    duracao   interval
);

CREATE OR REPLACE PROCEDURE medir_geracao(tabela text, expressao text, total int)
LANGUAGE plpgsql AS $$
DECLARE
    inicio timestamptz := clock_timestamp();
BEGIN
    EXECUTE format('SELECT count(%s) FROM generate_series(1, %s)', expressao, total);
    INSERT INTO custo_geracao VALUES (tabela, clock_timestamp() - inicio);
    COMMIT;
END $$;
