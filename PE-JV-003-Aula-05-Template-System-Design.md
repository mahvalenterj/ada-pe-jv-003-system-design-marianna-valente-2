# Template de System Design: Aula 05 - Containers, CI/CD & Deploy sem Downtime

> **Contexto do Cenário: O Deploy que Quebrou o Checkout e o Rollback Impossível**
>
> Sua equipe está reestruturando a engenharia de release do serviço de **Checkout** após um incidente severo na Black Friday:
>
> 1. **O Incidente:** Para subir uma otimização urgente na Saga, o pipeline de produção recompilou a branch `main`, sobrescreveu a tag `checkout:latest` e iniciou a atualização. No boot da primeira instância nova, o **Flyway** renomeou a coluna `status_pagamento` para `estado_saga`.
> 2. **O Efeito em Cadeia:** Os 80% de servidores que ainda rodavam a versão anterior passaram a falhar imediatamente (`column "status_pagamento" does not exist`). Ao tentar acionar o rollback puxando `:latest`, a equipe descobriu que a imagem anterior havia sido sobrescrita e, mesmo restaurando um backup do binário antigo, ele continuou falhando porque o banco já estava alterado.
> 3. **A Carga:** 5.000 TPS ativos com múltiplas réplicas de aplicação conectadas ao mesmo banco PostgreSQL.
>
> **Instruções:** Responda às decisões arquiteturais abaixo fundamentando suas escolhas estritamente com base nos requisitos e restrições deste cenário. Documente suas decisões no repositório contínuo do seu projeto através de commits atômicos e bem descritivos.

---

## 1. Empacotamento, Superfície de Ataque e Promoção Imutável

### 1.1 Anatomia da Imagem e Redução de Superfície de Ataque

- **Construção vs Execução:**
  > **Dockerfile multi-stage:**
  > - **Etapa de build:** imagem com JDK e Maven/Gradle; compila, roda os testes e gera o JAR em camadas (layered jar, para aproveitar cache).
  > - **Imagem final:** só o JRE e o JAR. **Não entram:** JDK, Maven/Gradle, código-fonte, `.git`, dependências de teste, segredos ou arquivos `.env`, ferramentas como `curl` e gerenciador de pacotes.
  > - **Usuário:** o processo roda como **não-root** (ex.: UID 65532 da imagem `nonroot`), com sistema de arquivos somente leitura e sem capabilities extras.
  > - **Imagem base:** Distroless Java (ex.: `gcr.io/distroless/java21-debian12:nonroot`), fixada por **digest**, não por tag.
  >
  > **Trade-off:** a Distroless não tem shell nem gerenciador de pacotes. Isso reduz muito a superfície de ataque e os CVEs para corrigir (um invasor não tem `sh` para usar), mas também tira o `sh` do time no diagnóstico em produção. Compensamos com observabilidade (logs, métricas e traces, Aula 06), endpoints do Actuator, JFR e containers efêmeros de debug (`kubectl debug`) quando for realmente necessário entrar no ambiente.

### 1.2 Rastreabilidade, Identidade do Artefato e Parametrização

- **Da Homologação à Produção:**
  > - **Build uma vez, promove sempre:** a imagem é construída **uma única vez** por commit. Homologação e produção recebem **o mesmo digest** (`sha256:...`); promover é só apontar o ambiente seguinte para esse digest, nunca recompilar a `main`.
  > - **Identidade:** cada imagem recebe tag com o **SHA do commit** (e a versão semântica). O registry tem **tags imutáveis** ativadas, então nenhuma tag pode ser sobrescrita. `:latest` é proibida em produção, e os manifests de deploy referenciam o digest.
  > - **Configuração por ambiente:** a mesma imagem lê a configuração de fora, por variáveis de ambiente/ConfigMaps (Spring profiles) e segredos de um cofre (Vault ou o secret manager da nuvem). Mudar de dev para prod muda a configuração, não o binário.
  > - **Rastreabilidade:** a esteira gera SBOM e **assina** a imagem (cosign), registrando de qual commit e pipeline ela veio.
  > - **Rollback:** é reimplantar o digest anterior, que continua no registry intacto. É exatamente o que faltou no incidente.

---

## 2. Estratégia de Rollout e Migração de Banco sem Downtime

### 2.1 O Modelo de Rollout em Produção

- **A Escolha:** (X) Canary Release (Liberação Gradual por Percentual)  ( ) Blue-Green Deployment (Alternância de Ambientes)
- **Justificativa e Mitigação das Desvantagens do seu Modelo:**
  > Com 5.000 TPS, virar 100% do tráfego de uma vez (Blue-Green) arrisca todos os clientes ao mesmo tempo e dobra a infraestrutura justamente no pico. Além disso, as duas cores compartilham o mesmo banco, então o Blue-Green não protegeria contra a migração destrutiva do incidente.
  >
  > - **Evolução do tráfego:** 1% → 5% → 25% → 50% → 100%, com tempo de observação em cada degrau (ex.: 10 min). A 5.000 TPS, 1% já são cerca de 50 requisições por segundo, volume suficiente para estatística confiável.
  > - **Sinais de avanço ou rollback automático** (Argo Rollouts ou Flagger, comparando canary com a versão estável): taxa de erro, latência p99, taxa de sucesso da Saga/conversão do checkout, erros de banco e reinícios de pod. Se qualquer limite for ultrapassado, o tráfego volta sozinho para a versão estável.
  > - **Duas versões convivendo:** toda mudança é **retrocompatível**. APIs e eventos só ganham campos novos (leitores tolerantes ignoram o que não conhecem), o schema do banco segue expand/contract (2.2) e comportamentos novos ficam atrás de feature flags. A aplicação não guarda estado em memória, então qualquer réplica pode atender qualquer requisição.

### 2.2 Evolução de Schema Sem Downtime e Plano de Rollback

- **Passo a Passo da Migração:**
  > Padrão **expand/contract**: nunca renomear coluna em uso; adicionar a nova, migrar, e remover a antiga só muito depois.
  >
  > 1. **Expand (migração isolada, antes do deploy):** `ALTER TABLE ... ADD COLUMN estado_saga` nula. É uma operação rápida, que não reescreve a tabela; a v1 continua funcionando porque ignora a coluna nova. Migrações rodam como **job separado** na esteira, não no boot de cada instância, com `lock_timeout` curto.
  > 2. **Sincronização:** um trigger copia `status_pagamento` → `estado_saga` sempre que a v1 grava, para a coluna nova nunca ficar desatualizada enquanto a v1 existir.
  > 3. **Deploy da v2 (canary):** a v2 **grava nas duas colunas** e continua lendo de `status_pagamento`. Assim v1 e v2 enxergam os mesmos dados.
  > 4. **Backfill em lotes:** preencher `estado_saga` nas linhas antigas em lotes pequenos, com pausa entre eles, evitando locks longos e pico de WAL.
  > 5. **Troca de leitura:** com a v2 em 100%, estável e o backfill conferido, uma nova versão passa a ler de `estado_saga`. A obrigatoriedade entra como `CHECK (estado_saga IS NOT NULL) NOT VALID` seguido de `VALIDATE CONSTRAINT`, sem bloquear a tabela.
  > 6. **Contract (releases depois):** passada a janela de rollback e sem nenhuma versão lendo a coluna antiga, para-se de gravar nela, remove-se o trigger e, num release separado, `DROP COLUMN status_pagamento`.
- **Garantia de Rollback Imediato:**
  > **Procedimento:** abortar o canary (comando do Argo Rollouts ou disparo automático pela análise). O tráfego dos 20% volta para a v1, que **nunca deixou de rodar** nos outros 80%; as réplicas v1 são escaladas para 100% e as v2 são desligadas. Não há rebuild nem restauração de banco: a imagem da v1 é o mesmo digest que já estava em produção.
  >
  > **Por que funciona:** até o passo 5, o schema só **ganhou** coisas, nada foi removido ou renomeado, então a v1 encontra tudo que espera. E como a v2 grava também em `status_pagamento`, usando apenas valores que a v1 entende, todo pedido criado ou atualizado pela v2 continua legível pela v1: nenhum dado se perde. Rollback de banco não é necessário.

---

## 3. Guardrails de Segurança, Governança e Validação da Release

### 3.1 Gates de Segurança e Guardrails no Processo de Entrega

- **Guardrails do Commit ao Deploy:**
  > - **Pull Request:** branch protegida, revisão obrigatória, build com testes unitários e de integração, cobertura mínima, SAST (CodeQL/Semgrep/Sonar), varredura de segredos (gitleaks), análise de dependências vulneráveis (OWASP Dependency-Check/Dependabot) e **lint de migrações** (ex.: squawk) que bloqueia `RENAME`/`DROP` de coluna em uso ou DDL que trava tabela.
  > - **Build da imagem:** lint do Dockerfile (hadolint), varredura da imagem (Trivy/Grype) falhando em CVE crítica, geração de SBOM e assinatura com cosign.
  > - **Promoção e deploy:** o cluster só aceita imagens **assinadas**, vindas do registry oficial e referenciadas por digest (política de admissão com Kyverno/OPA); smoke tests e testes ponta a ponta em homologação; em produção, canary com análise automática.
- **Governança e Controle em Produção:**
  > - **GitOps (Argo CD):** o estado de produção é o que está no Git. Mudança manual no cluster é detectada como desvio (drift) e revertida automaticamente; toda mudança passa por PR, com autor, revisor e histórico.
  > - **Sem acesso de escrita humano em produção** no dia a dia (RBAC de menor privilégio).
  > - **Break-glass para incidentes:** acesso emergencial temporário, vinculado a um chamado de incidente, com tudo auditado, expirando sozinho e seguido de post-mortem, levando a correção de volta para o Git.
  > - **Agilidade sem atalho:** um caminho de hotfix acelerado com os **mesmos gates** e revisão prioritária, além de feature flags como "botão de desligar", que permitem conter um problema em segundos sem deploy.

### 3.2 Estratégia de Testes da Imagem e da Migração

- **Plano de Testes na Pipeline:**
  > 1. **A imagem sobe de verdade:** a esteira roda a imagem recém-construída (Testcontainers ou `docker run`) com um PostgreSQL efêmero e verifica o health check e a prontidão do Actuator, faz smoke tests nos endpoints do checkout e confere com container-structure-test que o processo roda como não-root e que não há shell nem ferramentas proibidas.
  > 2. **A migração é retrocompatível:** num banco com schema e volume parecidos com produção (snapshot anonimizado), aplicar a migração nova e rodar contra ele **a suíte de testes da versão anterior (v1, pelo digest em produção)** e a da versão nova (v2). Se a v1 quebrar, a migração não é retrocompatível e o deploy é bloqueado.
  > 3. **Rollback testado:** a v2 grava pedidos, a v1 volta e lê esses pedidos sem erro.
  > 4. **Custo da migração:** medir duração e locks adquiridos no banco de teste; acima do limite, a esteira bloqueia.
  > 5. Só com tudo verde a imagem é promovida e o canary começa.
