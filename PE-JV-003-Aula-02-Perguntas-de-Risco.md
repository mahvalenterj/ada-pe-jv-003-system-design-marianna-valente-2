# Aula 02 - Perguntas de Risco (Engenharia do Caos)

Cenário: API de Checkout com 10.000 usuários simultâneos, dependente de um Serviço de Pagamento externo.

1. **E se** o parceiro cobrar o cliente, mas a resposta não chegar até nós (timeout depois da cobrança)? Cobramos duas vezes no retry?
2. **E se** o Circuit Breaker abrir no pico da Black Friday? O que o cliente vê e o que acontece com os pedidos em andamento?
3. **E se** o armazenamento das Idempotency-Keys (Redis/banco) cair? Continuamos cobrando sem proteção contra duplicidade?
