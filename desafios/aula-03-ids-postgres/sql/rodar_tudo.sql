-- Roda o desafio inteiro:  psql -U postgres -d postgres -f sql/rodar_tudo.sql
-- Máquina mais lenta:      psql -U postgres -d postgres -v linhas=200000 -f sql/rodar_tudo.sql
\set ON_ERROR_STOP on
\pset pager off
\ir 01_preparar.sql
\ir 02_carga.sql
\ir 03_metricas.sql
\ir 04_explain.sql
