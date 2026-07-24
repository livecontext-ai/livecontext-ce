// cap-ai-agent-cost-budgets - pt
// Translated from the English body; the structure must stay identical to it.
// Formulas and code samples are fenced on purpose: an inline code span over
// ~45 chars overflows the page on a phone. The hedges are load-bearing here.
const content = `## Um alerta não é um teto

Um monitor é assíncrono e a posteriori: diz-lhe o que já gastou, pelo que não pode ser a camada de imposição. Um teto é síncrono e pré-execução: recusa a chamada seguinte. A reconciliação e a telemetria de motivo de paragem continuam a importar, mas para dimensionar o limite e detetar um que esteja demasiado apertado, não para parar trabalho.

Eis o teste a fazer antes de continuar a ler, e não precisa de nenhum limiar: extraia os registos de recusa do limite que configurou ao longo da última janela de observação. Alguma vez recusou alguma coisa? Um número que nunca recusou nada não é um controlo, é um comentário.

Os limites ao nível do fornecedor são redes de segurança, não imposição de primeira linha:

- O limite de despesa por projeto e por organização da OpenAI é um **orçamento flexível por omissão**: notifica e os pedidos continuam a passar. Existe um teto rígido, mas como opção separada de ativação explícita, que passa então a devolver HTTP 429 até o limite ser aumentado ou reposto ([guia de limites de despesa](https://developers.openai.com/api/docs/guides/spend-limits)).
- A [Spend Limits API](https://platform.claude.com/docs/en/manage-claude/spend-limits-api) da Anthropic é exclusiva do Claude Enterprise, explicitamente indisponível para organizações Claude Platform (Console), e suporta \`monthly\` como único período (reposição às 00 UTC no primeiro dia do mês civil). Limita o uso por assento humano, não a despesa de API dos agentes.
- A documentação da Anthropic também desqualifica a despesa do fornecedor como barreira: \`period_to_date_spend\` "pode ler '0' se a leitura de despesa estiver temporariamente indisponível; trate-a como informativa, não transacional."
- A Anthropic impõe, isso sim, um limite mensal por escalão de utilização (Start $500, Build $1,000, Scale $200,000, Custom sem limite) que suspende o uso da API até ao mês seguinte ([limites de taxa](https://platform.claude.com/docs/en/api/rate-limits)). Um teto real, mas ao nível da organização e mensal: uma única execução descontrolada pode consumi-lo e converter um bug de custo numa indisponibilidade de toda a organização.

O custo por passo cresce de forma superlinear porque o contexto se acumula, e é por isso que contar passos não delimita dólares. Essa derivação vive no artigo companheiro sobre o modelo de custo. Apenas como dados de dimensionamento, a Anthropic reporta que os agentes usam cerca de 4x os tokens do chat e os sistemas multiagente cerca de 15x ([multi-agent research system](https://www.anthropic.com/engineering/multi-agent-research-system)).

**Divulgação.** Os detalhes de implementação, constantes e mensagens de recusa abaixo vêm do \`agent-service\` da LiveContext, a plataforma a que este blogue pertence. Leia-os como as escolhas de um sistema, verificáveis no código-fonte da sua edição comunitária, não como prática de campo levantada por inquérito.

## As cinco partes de um objeto de orçamento

Um orçamento não é um número. É um objeto com cinco partes, e um orçamento a que falte qualquer uma delas falha de uma forma específica e diagnosticável.

**1. Âmbito.** O nível ao qual o registo é mantido. Existem quatro neste sistema em produção: saldo de inquilino/conta (macro), agente/passo (micro), \`parent_reservation\` (um ascendente na cadeia de chamadas recusa financiar a criação de um filho) e por execução/por época. Uma recusa que não nomeie o âmbito que disparou é impossível de depurar.

**2. Unidade.** Dólares, tokens ou meras contagens (turnos, supersteps, chamadas de ferramenta). As contagens flutuam em termos monetários. Só tokens ou dinheiro constituem um orçamento.

**3. Ponto de imposição.** Reconciliação a posteriori, projeção pré-iteração, reserva pré-criação ou limite de admissão sobre as entradas. Cada um tem um limite de ultrapassagem diferente (Tabela 1).

**4. Política de reserva.** Se o orçamento é decrementado depois do facto ou retido antes de o trabalho começar. Esta é a única parte que torna seguro o fan-out paralelo.

**5. Resposta terminal.** O que o chamador recebe no instante em que o teto é atingido. Existem cinco comportamentos distintos no terreno e não são intermutáveis.

**Tabela 1: Pontos de imposição e o respetivo limite de ultrapassagem**

| Ponto de imposição | Quando corre | O que pode recusar | Ultrapassagem no pior caso | Seguro para fan-out paralelo? |
|---|---|---|---|---|
| Reconciliação / alertas a posteriori | Depois de a chamada liquidar | Nada | Ilimitada | Não (deteção, não imposição) |
| Projeção pré-iteração | Antes da chamada seguinte ao modelo | A iteração seguinte | Uma iteração (até 40x a primeira iteração num passo de browser) | Não |
| Reserva pré-criação | Antes de um filho arrancar | O filho inteiro | Zero para o filho | Sim |
| Limite de admissão sobre as entradas | Antes de o prompt ser montado | Contexto / saída sobredimensionados | Delimita a própria iteração | Sim (compõe-se com os restantes) |

Duas decisões de desenho que as pessoas tratam como configuração mas que pertencem ao objeto:

**A ordem das guardas é desenho de âmbito.** Esta implementação corre exatamente duas guardas, \`TenantBudgetGuard\` e depois \`AgentBudgetGuard\`, primeira-recusa-ganha com curto-circuito, por duas razões documentadas: o esgotamento do inquilino torna o orçamento do agente irrelevante, e a guarda de inquilino é colocada primeiro como rejeição precoce antes da ida e volta de reserva de crédito a jusante.

**O período é uma decisão de dimensionamento.** Um acumulador cumulativo faz do limite um total vitalício, pelo que um agente de vida longa se aproxima silenciosamente do esgotamento ao longo de meses. Reposições semanais ou mensais fazem do mesmo número uma taxa. As reposições podem ser resolvidas de forma preguiçosa no arranque da execução com uma atualização compare-and-set em vez de por um agendador (modos do \`BudgetResolver\`: cumulative, weekly, monthly; valores desconhecidos tratados como cumulative).

Uma armadilha semântica que vale a pena verificar na sua própria stack: a ajuda de parâmetro de ferramenta desta plataforma, dirigida ao agente, ainda diz "Cada iteração de LLM custa 1 crédito", enquanto a guarda compara uma projeção monetária com esse mesmo campo \`credit_budget\`. Outras duas mensagens de ajuda matizam-no como "pelo menos um crédito" e "mais de 1 crédito na prática", pelo que a documentação também se contradiz a si própria. Uma regra prática na documentação e uma comparação monetária no código são uma classe de bug, não um detalhe de redação.

## Não é possível travar a chamada que já está a fazer

O consumo de tokens só é conhecido depois de a chamada terminar. Nenhum orçamento em execução pode impedir que uma única chamada cara estoire o limite; só pode impedir a seguinte. O pior caso realizado é, portanto, **o orçamento mais uma iteração**, não o orçamento. Diga-o com clareza em vez de dar a entender que há um teto rígido.

A fórmula de bloqueio tal como enunciada no ficheiro de fixtures partilhado entre linguagens:

\`\`\`
projectedNext = max(
    growthProj,
    lastDeltaProj * LAST_DELTA_SAFETY_FACTOR,
    worstCaseSingleIter
)
deny iff (runCostSoFar + projectedNext > balance)
      OR (runCostSoFar >= balance)

LAST_DELTA_SAFETY_FACTOR = 2.0
RATE_DIVISOR             = 1000
ROUND_DECIMALS           = 6 (HALF_UP, per subterm)
\`\`\`

Duas ressalvas antes de a copiar. As duas guardas Java implementam \`>=\` na comparação da projeção, não \`>\`; a gémea em JS implementa \`>\`. Em igualdade exata do total projetado discordam, e nenhum caso de fixture assenta nessa fronteira. E a comparação de âmbito de agente não tem dois termos mas quatro:

\`\`\`
totalProjected = consumedBeforeRun
               + creditsReserved
               + runCostSoFar
               + nextProjected
deny iff totalProjected >= totalBudget
\`\`\`

\`creditsReserved\` são os créditos atualmente retidos por subagentes em curso, pelo que o ciclo do próprio pai é estrangulado por aquilo que os seus filhos estão a reter.

Cada ramo de projeção é não redundante:

- \`growthProj\` (tokens médios por iteração concluída) apanha uma subida constante.
- \`lastDeltaProj\` (o delta da última iteração vezes 2) apanha um pico que uma média dilui.
- \`worstCaseSingleIter\` (janela de contexto completa vezes a saída máxima completa às tarifas do modelo) é invariante ao padrão de crescimento e apanha um salto em degrau logo na iteração 1.

O ramo de pior caso é o que faz o trabalho a sério. A preços de classe opus (15 / 75 USD por 1M) com um contexto de 200K e 64K de saída máxima:

\`\`\`
worstCaseSingleIter = 200 * 15 + 64 * 75
                    = 3,000 + 4,800
                    = 7,800 credits      (1 credit = $0.001)
\`\`\`

Qualquer saldo abaixo de 7,800 créditos está protegido contra essa iteração de pico pelo ramo de pior caso e por mais nada.

A segunda condição de recusa, \`runCostSoFar >= balance\`, é logicamente redundante: sempre que a projeção é positiva, a primeira condição já a cobre. Existe puramente para que a recusa nomeie o modo de falha real em vez de aparecer como uma ultrapassagem de projeção.

A fórmula de custo, para reprodutibilidade:

\`\`\`
inputCost  = inputRate  * promptTokens     / 1000
outputCost = outputRate * completionTokens / 1000
total      = inputCost + outputCost + fixedCost
\`\`\`

As tarifas são em USD por 1M de tokens; o \`/1000\` converte para uma unidade de crédito em que 1 crédito = $0.001. Arredonde cada subtermo a 6 casas decimais antes de somar, ou duas implementações da mesma fórmula vão divergir.

Três restrições honestas sobre este mecanismo:

**A guarda por agente precisa de duas iterações concluídas.** Com uma única amostra, \`lastDelta == runCost == growth\`, logo \`lastDelta * 2 = 2 * runCost\`, e qualquer primeira iteração que consuma mais do que \`budget/3\` auto-recusaria a iteração 2 mesmo quando a chamada seguinte é legitimamente pequena. A guarda de inquilino não tem essa condição: projeta a partir da iteração 1, onde growth e lastDelta são ambos zero, pelo que só o ramo de pior caso vincula aí. Isso é intencional, e é por isso que o teto da iteração 1 pertence ao ramo de pior caso.

**A desatualização agrava a lacuna.** Um saldo re-obtido a cada 5 iterações (descendo para cada iteração quando as tarifas de custo não são fiáveis) acrescenta uma janela de desatualização por cima da lacuna de projeção de uma iteração. Uma variante adaptativa atualiza a cada iteração assim que a taxa de queima excede 70% do saldo.

**O fallback para modelo desconhecido é uma decisão real com um historial de bugs.** Falhar de forma pessimista nas tarifas (recorrer ao escalão mais alto, 15 / 75 USD por 1M) mas permissiva no teto (deixar a janela de contexto a null para que \`worstCase\` devolva null e a guarda recue para growth apenas). Um fallback anterior de 0.015 / 0.075 contornava silenciosamente a guarda por completo.

Os próprios comentários da guarda contêm a admissão: uma camada atómica de reserva por turno foi prototipada e revertida, porque uma ultrapassagem de no máximo uma iteração foi julgada aceitável em troca de um caminho de chamada mais simples. E a pré-verificação é explicitamente "um instantâneo, não autoritativo": a reconciliação pós-execução continua a correr, e as duas podem discordar.

## O momento do impacto: o que o chamador recebe de facto

Uma paragem por orçamento é classificada como \`PARTIAL\`, não \`FAILURE\`, no contrato de motivo de paragem desta plataforma: saída utilizável mas truncada. Não levanta exceção, e uma paragem por orçamento que produziu tokens é persistida com estado de execução \`COMPLETED\`, pelo que só a coluna \`stop_reason\` carrega o detalhe. Duas qualificações, porque meias-verdades aqui são a forma como um limite demasiado apertado se mantém invisível: uma paragem por orçamento com zero tokens é persistida como \`FAILED\`, e a agregação diária de métricas conta, sim, todas as execuções paradas por orçamento na sua contagem de falhas. O que é genuinamente invisível é a forma do dano, não o facto de ele existir. Se olhar apenas para as taxas de erro, um limite demasiado apertado aparece meses depois como uma regressão de qualidade.

**Tabela 2: Onde cada motivo de paragem é decidido (6 dos 10 valores do contrato)**

| Motivo de paragem | Categoria terminal | Onde é decidido | O que o chamador tem de fazer |
|---|---|---|---|
| \`MAX_ITERATIONS\` | parcial | A posteriori, depois de o ciclo sair | Tratar a saída como truncada; aumentar n ou o orçamento |
| \`TIMEOUT\` | parcial | A posteriori, depois de o ciclo sair | Estava a trabalhar ativamente, passou do tempo de relógio; retomar ou alargar |
| \`BUDGET_EXHAUSTED\` | parcial | Guarda pré-iteração, antes da chamada | Ler \`budgetScope\` (\`tenant\`, \`agent\`, \`parent_reservation\`, \`browser\`), decidir entre recarregar ou redimensionar |
| \`LOOP_DETECTED\` | parcial | A meio da iteração, depois de as chamadas de ferramenta serem analisadas | Inspecionar a assinatura repetida; a tarefa está malformada |
| \`STOPPED_BY_USER\` | parcial | Canal de cancelamento | Guardar a saída parcial |
| \`INACTIVITY_TIMEOUT\` | falha | Watchdog, não o ciclo; uma passagem posterior reclassifica \`STOPPED_BY_USER\` | Ficou em silêncio, teve de ser morto; investigar o bloqueio |

\`BUDGET_EXHAUSTED\` é o único valor que transporta um array de âmbitos. Uma paragem por orçamento que não lhe diz qual o teto que disparou obriga-o a adivinhar.

A recusa não deveria ser uma exceção. Uma implementação viável sai do ciclo e regista metadados estruturados: o motivo de paragem, mais \`budgetScope\`, mais uma string \`denialReason\` que nomeia o ramo de projeção que disparou:

\`\`\`
tenant balance X would be exceeded
(run=A + next=B [growth=..., lastDelta=..., worstCase=...])
\`\`\`

Use as mesmas chaves nos caminhos síncrono e de streaming para que as métricas não possam divergir.

No conjunto do levantamento feito, existem cinco comportamentos terminais e não são intermutáveis:

1. **Exceção**: \`MaxTurnsExceeded\` (OpenAI Agents SDK), \`GraphRecursionError\` (LangGraph), \`UsageLimitExceeded\` (Pydantic AI), \`ModelCallLimitExceededError\` (LangChain).
2. **Resultado tipado com ramificação**: o \`stop_reason\` do \`TaskResult\` do AutoGen, o subtipo \`error_max_budget_usd\` do Claude Agent SDK, o \`exit_behavior='end'\` do LangChain com uma mensagem de AI injetada.
3. **Truncagem silenciosa com HTTP 200**: o \`max_tokens\` da Anthropic define \`stop_reason: "max_tokens"\` e devolve sucesso ([Messages API](https://platform.claude.com/docs/en/api/messages)).
4. **Rejeição HTTP 429**: o limite rígido opcional da OpenAI. A Anthropic documenta 429 apenas para \`rate_limit_error\` e coloca problemas de faturação em 402, pelo que nenhum código de estado está documentado para o seu limite mensal de despesa por escalão; confirme esse contra os seus próprios logs.
5. **Resposta degradada em melhor esforço**: o \`max_iter\` do CrewAI, em que o agente "deve fornecer a sua melhor resposta" ([agentes CrewAI](https://docs.crewai.com/en/concepts/agents)).

Um conflito de semântica que vale a pena verificar na sua própria stack: os [orçamentos de iteração da LiteLLM](https://docs.litellm.ai/docs/a2a_iteration_budgets) devolvem 429 com tipo de erro \`budget_exceeded\`, e por convenção HTTP 429 significa tentar de novo mais tarde. Para um limite de organização que se repõe com o tempo isso é defensável, já que esperar acaba por tornar o pedido satisfazível. Para um orçamento por execução ou por agente está errado: esperar nunca o satisfaz, e a lógica de retentativa padrão dos SDK vai martelar a parede. A LiteLLM é a única instância confirmada aqui, não uma classe provada. Verifique o que a política de retentativa do seu cliente faz com um 429.

O que deve sobreviver à paragem é a outra metade do contrato. O [Claude Agent SDK](https://code.claude.com/docs/en/agent-sdk/agent-loop) é o que mais se aproxima de um desenho de referência: o campo \`result\` (a resposta final) só está presente no subtipo \`success\`, mas todos os subtipos de erro continuam a transportar \`total_cost_usd\`, \`usage\`, \`num_turns\` e \`session_id\`. Perde a resposta, não a sessão. Note a assimetria: uma \`query()\` de disparo único levanta exceção depois de devolver o resultado de erro, ao passo que uma sessão com entrada em streaming permanece viva.

Porque é que isto importa comercialmente, a partir de um [relatório de incidente](https://github.com/anthropics/claude-code/issues/68430): as únicas opções do operador eram "deixá-lo correr e vê-lo queimar o orçamento da sessão num ciclo recursivo que nunca vai ter sucesso" ou "matá-lo e perder tudo, incluindo trabalho legítimo concluído pelos agentes iniciais." Um limite que descarta trabalho parcial converte um problema de custo num problema de perda total, que é precisamente a razão pela qual os operadores desativam os limites.

Uma recusa do lado do pai deve seguir a mesma regra: não um erro lançado mas um resultado de falha sintetizado que nomeia o ascendente e o âmbito.

\`\`\`
Cannot spawn child 'X': ancestor agent <id> has
insufficient free budget for reservation N
(scope=parent_reservation, BUDGET_EXHAUSTED)
\`\`\`

Por fim, torne o limite introspetável a partir de dentro do agente. A forma de resposta em produção:

\`\`\`
budget.{ unlimited, total, consumed,
         consumed_own, consumed_from_subagents,
         reserved_for_subagents, free,
         reset_mode, last_reset }

free = max(total - consumed - reserved_for_subagents, 0)
\`\`\`

No ramo sem limite, \`total\` e \`free\` são null e \`reserved_for_subagents\` é devolvido como 0. A regra explícita: se \`free\` estiver abaixo do orçamento de um filho, a criação falha com \`scope=parent_reservation\`.

## O que cada stack consegue e não consegue impor

**Tabela 3: O que cada stack consegue efetivamente impor** (limitado às plataformas levantadas; o Google ADK e o LlamaIndex não foram)

| Stack | Unidade imposta | Valor por omissão | Comportamento no teto | Propaga para subagentes? |
|---|---|---|---|---|
| [Claude Agent SDK](https://code.claude.com/docs/en/agent-sdk/python) | USD por execução (\`max_budget_usd\`), mais turnos | Ambos sem limite | Subtipo de resultado tipado \`error_max_budget_usd\` / \`error_max_turns\`, sessão preservada | \`usage\` exclui tokens de subagentes; \`total_cost_usd\` inclui-os |
| Anthropic Messages API | Tokens (\`max_tokens\`) | Sem valor por omissão; tem de o definir | HTTP 200 com \`stop_reason: "max_tokens"\`, truncado | N/A |
| OpenAI (conta) | USD por mês | Flexível por omissão | Notificação, ou 429 se o limite rígido for ativado | N/A |
| [OpenAI Agents SDK](https://openai.github.io/openai-agents-python/running_agents/) | Turnos ([\`DEFAULT_MAX_TURNS = 10\`](https://github.com/openai/openai-agents-python/blob/main/src/agents/run_config.py)) | 10 | Levanta \`MaxTurnsExceeded\` | Não documentado |
| [LangGraph](https://docs.langchain.com/oss/python/langgraph/graph-api) | Supersteps (\`recursion_limit\`) | A documentação diverge: 1000 desde a v1.0.6 no runtime de grafo OSS, 25 no esquema \`Config\` do SDK e em relatos de campo | Levanta \`GraphRecursionError\` | Dois bugs de propagação documentados (abaixo) |
| [LangChain middleware](https://reference.langchain.com/python/langchain/agents/middleware/model_call_limit/ModelCallLimitMiddleware) | Apenas contagens de chamadas, sem orçamento de tokens ou de custo | Ambos os limites a \`None\` | Configurável: \`exit_behavior='end'\` injeta uma mensagem, \`'error'\` levanta exceção | Não aplicável |
| [Pydantic AI](https://pydantic.dev/docs/ai/api/pydantic-ai/usage/) | Tokens, pedidos, chamadas de ferramenta | \`request_limit=50\`, limites de tokens a \`None\` | Levanta \`UsageLimitExceeded\`; verificação pré-voo opcional | Não documentado |
| AutoGen ([conditions](https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.conditions.html), [teams](https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.teams.html)) | Tokens (\`TokenUsageTermination\`) | Por omissão nas equipas: \`termination_condition=None\`, \`max_turns=None\` | \`TaskResult\` tipado com uma string \`stop_reason\` | Âmbito da equipa |
| [CrewAI](https://docs.crewai.com/en/concepts/agents) | Iterações (\`max_iter\`) | A documentação diz 20, o código-fonte diz 25 | O agente "deve fornecer a sua melhor resposta" | Não documentado |

Cinco coisas que esta tabela diz e que a prosa esconderia:

**Quase tudo tem como valor por omissão o ilimitado.** O \`max_turns\` e o \`max_budget_usd\` do Claude Agent SDK são ambos sem limite; as [equipas do AutoGen](https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.teams.html) afirmam claramente que o chat de grupo "correrá indefinidamente"; os limites de despesa por assento Enterprise da Anthropic são ilimitados por omissão quando não existe valor por omissão a nenhum nível (os limites por escalão da API, pelo contrário, aplicam-se sempre).

**O único parâmetro de custo do levantamento sem valor por omissão é o \`max_tokens\` da Anthropic**, que o esquema da Messages API obriga a definir explicitamente. É também aquele cuja violação devolve HTTP 200 com conteúdo truncado. O esquema documenta agora também defini-lo a 0 para aquecer a cache de prompt, pelo que obrigatório não significa teto significativo.

**O único teto em dólares por execução do levantamento é imposto contra uma estimativa.** A página de acompanhamento de custos da Anthropic avisa que \`total_cost_usd\`, o valor exato contra o qual \`max_budget_usd\` é comparado, consiste em "estimativas do lado do cliente, não dados de faturação autoritativos" calculadas a partir de uma tabela de preços incluída em tempo de compilação, e diz "Não faturar utilizadores finais nem desencadear decisões financeiras a partir destes campos." É também avaliado entre turnos, pelo que a despesa pode ultrapassar o limite configurado no valor de um turno. É a mesma garantia de orçamento mais uma iteração, no produto mais bem desenhado do setor.

**O LangChain não tem qualquer orçamento de tokens ou de custo.** O \`ModelCallLimitMiddleware\` e o \`ToolCallLimitMiddleware\` limitam contagens de chamadas, ambos com valor por omissão \`None\`, e um responsável [confirmou a lacuna de orçamento de tokens em julho de 2026](https://forum.langchain.com/t/a-proposal-to-add-token-usage-budgets-to-langchain-agents-via-a-new-middleware-since-the-existing-limiters-only-cap-call-count-not-tokens/4147). Ainda assim, o seu parâmetro \`exit_behavior\` é o modo de falha configurável mais limpo do setor e vale a pena copiá-lo.

**O Pydantic AI é a única stack com uma verificação pré-voo**: \`count_tokens_before_request\` (por omissão \`False\`) chama a API de contagem de tokens do fornecedor para rejeitar um pedido acima do orçamento antes de ser faturado. Traz também uma armadilha: \`request_limit\` fica silenciosamente a 50 por omissão, pelo que definir apenas \`input_tokens_limit\` herda um limite de 50 pedidos a menos que passe \`request_limit=None\`.

**A propagação é a forma número um de um teto se tornar decorativo.** Dois casos documentados: [LangChain deepagents #1698](https://github.com/langchain-ai/deepagents/issues/1698), em que o \`SubAgentMiddleware\` invocava subagentes sem o parâmetro \`config\`, pelo que corriam sempre no limite de recursão por omissão independentemente de um pai definido a 150; e [langgraphjs #1524](https://github.com/langchain-ai/langgraphjs/issues/1524), em que o \`recursionLimit\` de \`withConfig\` é silenciosamente ignorado e a mensagem de erro resultante lhe diz para definir precisamente a chave que está a ser ignorada.

Duas armadilhas de medição que derrotam silenciosamente código de orçamento ingénuo, ambas do [documento de acompanhamento de custos da Anthropic](https://code.claude.com/docs/en/agent-sdk/cost-tracking): o campo \`usage\` conta apenas o ciclo de topo e exclui tokens de subagentes (enquanto \`total_cost_usd\` e \`model_usage\` os incluem), e as chamadas de ferramenta em paralelo emitem várias mensagens de assistente que partilham um mesmo id de mensagem com uso idêntico, pelo que um contador que some o uso por mensagem contabiliza a dobrar e dispara cedo. Elimine duplicados por id.

Limites de taxa não são limites de despesa e podem recompensar o caminho mais caro: os tokens de entrada em cache são faturados a 10% mas não contam para os limites de tokens de entrada por minuto na maioria dos modelos, e \`max_tokens\` não entra de todo nos limites de tokens de saída por minuto ([limites de taxa](https://platform.claude.com/docs/en/api/rate-limits)).

## As guardas de ciclo delimitam n; os orçamentos delimitam o custo dado n

Um detetor de ciclos e um orçamento respondem a perguntas diferentes. O detetor delimita quantas iterações acontecem; o orçamento delimita quanto essas iterações podem custar. Nenhum substitui o outro.

Limiares reais de um detetor em produção, com duas condições de disparo independentes:

| Condição | Chave | Degraus de escalada | Paragem rígida |
|---|---|---|---|
| Chamadas idênticas | nome da ferramenta + argumentos ordenados, com hash | aviso aos 5 | 15 |
| Chamadas consecutivas | total de chamadas de ferramenta, qualquer assinatura | 15, 25, 35 | 40 |

O teto de consecutivas é deliberadamente alto para que operações legítimas em lote não sejam mortas. Ambas as paragens rígidas são configuráveis por agente, e os degraus intermédios são **derivados** (aviso de idênticas = \`ceil(stop/3)\` mínimo 2; degraus de consecutivas = \`ceil(stop * 3/8)\`, \`5/8\`, \`7/8\`) para que a escada de severidade se mantenha monótona em qualquer valor personalizado, com paragens mínimas impostas.

A escada não é apenas registo: cada degrau injeta uma mensagem de volta no contexto do agente antes da paragem, escalando de uma nota informativa até "resta 1 iteração, PARA as ferramentas, RESPONDE AGORA" e daí para a terminação. A intenção de desenho declarada é que os padrões repetitivos devem ser automatizados como workflows em vez de percorridos em ciclo.

A lacuna de cobertura que merece ser nomeada: esse detetor só conta quatro nomes de ferramenta. Todas as outras chamadas de ferramenta são invisíveis a ambos os contadores, pelo que um ciclo sobre uma ferramenta não monitorizada nunca produz \`LOOP_DETECTED\`. Verifique a cobertura equivalente na sua própria stack antes de confiar numa guarda de ciclo.

Não conte com o modelo para dar por si próprio pelo seu desperdício. O RedundancyBench anotou 200 trajetórias (filtradas das execuções bem-sucedidas recolhidas) com mais de 8,000 passos anotados, e a melhor deteção automática ao nível do passo de passos redundantes obteve 24.88% (72.50% ao nível da trajetória) ([arXiv 2605.29893](https://arxiv.org/abs/2605.29893)). O limite tem de ser mecânico.

Outros valores por omissão de delimitação de execução da mesma implementação, como ponto de referência: máximo de iterações 100, tempo limite de execução 3600 s, máximo de tokens 16,000 por turno, e um watchdog de inatividade de 5 minutos cuja substituição por agente aceita apenas 0 (desativado) ou 10 a 7200 segundos, para que um valor perdido não possa armar um watchdog à escala dos segundos.

O tempo de relógio merece uma linha como limite de último recurso. Um incidente documentado consumiu 4 milhões de tokens em menos de 5 minutos ([claude-code #68619](https://github.com/anthropics/claude-code/issues/68619)), mais depressa do que qualquer amostragem por turno ou de atualização de saldo conseguiria reagir. Isso é inferência a partir de um único incidente, não uma boa prática com fonte, mas a aritmética é difícil de contestar.

## O teste para um teto a sério

Seis pontos, cada um respondível a partir dos seus próprios logs:

1. Uma recusa nomeia o âmbito que disparou?
2. A verificação é síncrona e anterior à chamada seguinte?
3. A resposta terminal é tipada, não passível de retentativa, e transporta o registo de custos mais um identificador de retoma?
4. O limite propaga-se para os subagentes, provado por um teste que define um limite no pai e verifica que um filho o herda?
5. O rácio de granularidade \`g\`, o orçamento dividido pela iteração de pior caso delimitada, é pelo menos 3? O artigo companheiro sobre dimensionamento deriva esse mínimo e mostra que a maioria dos limites monetários por passo não o cumpre.
6. O limite alguma vez recusou de facto, na janela observada?

A garantia honesta: um orçamento em execução delimita o custo a **o orçamento mais uma iteração**, não ao orçamento. Uma reserva pré-criação é o único mecanismo com ultrapassagem zero, e cobre apenas o filho.

Se a mesma fórmula existe em dois runtimes, vale a pena investir em paridade. Um ficheiro de fixtures partilhado com casos nomeados, consumido tanto por um teste parametrizado JUnit como por um executor de testes Node, é a forma mais barata de impedir que os dois divirjam, e o arredondamento tem de coincidir subtermo a subtermo. Note o limite: uma fixture só cobre os casos que contém. Uma fixture que pré-carrega tarifas explícitas nunca exercita o caminho de fallback para modelo desconhecido em nenhum dos lados, que é exatamente onde as duas implementações aqui descritas divergiram numa ordem de grandeza, e uma fixture que instancia apenas a guarda de inquilino nunca repara que as duas guardas de agente usam operadores de comparação diferentes.

Declare o que não se sabe. Não existe nenhuma taxa base publicada sobre a frequência com que os agentes em produção descarrilam. O catálogo mais robusto rejeita explicitamente afirmar prevalência e reclama apenas existência e recorrência em projetos desenvolvidos de forma independente. Raciocine a partir do mecanismo e da magnitude em vez de inventar uma frequência.

E seja realista quanto à magnitude. Segundo as linhas de incidentes do mesmo catálogo de 2026 ([arXiv 2606.04056](https://arxiv.org/abs/2606.04056)), as ultrapassagens documentadas concentram-se nas centenas a poucos milhares de dólares: cerca de $2,150 de despesa não intencional num caso, $235 em quatro dias por um único utilizador, uma ultrapassagem de 70% acima de um orçamento de otimizador. Compare isso com a anedota de descontrolo mais republicada do setor, ["We spent $47,000 running AI agents"](https://todatabeyond.substack.com/p/we-spent-47000-running-ai-agents), que não nomeia nenhuma empresa, não apresenta fatura, repositório, configuração ou logs, e foi depois amplificada sob uma segunda assinatura e através de uma dúzia de artigos de SEO a citarem-se uns aos outros. Os seus próprios números semanais são $127, $891, $6,240 e $18,400, que somam $25,658, não $47,000, e uma subida de custos ao longo de quatro semanas contradiz o "ciclo de 11 dias" do mesmo texto. O perfil de risco real é silencioso, recorrente e na casa dos quatro algarismos em dólares, na faixa intermédia.
`;

export default content;
