// workflow-beats-do-everything-agent - pt
// Translated from the English body; keep the structure identical to it
// (10 h2, 6 tables, 5 fenced formula blocks, 21 source links). The formulas are
// fenced on purpose: an inline formula over ~45 chars overflows the page on a phone.
const content = `## O número que eliminei

Uma versão anterior deste artigo afirmava que um workflow com escopo definido custa "cerca de dez vezes menos" do que um agente que faz tudo. Esse número não tinha derivação, não tinha premissas e não tinha fonte por trás dele, portanto foi removido.

Não existe nenhuma fonte publicada para substituí-lo. Nenhum benchmark de fornecedor, artigo ou trace mede o mesmo trabalho implementado uma vez como pipeline com escopo e uma vez como agente autônomo, com custo e taxa de sucesso instrumentados dos dois lados. A peça canônica nesta categoria, [Building Effective Agents](https://www.anthropic.com/engineering/building-effective-agents) da Anthropic, não contém nenhum valor de custo; o tratamento que dá ao assunto se resume a duas frases: "Sistemas agênticos frequentemente trocam latência e custo por melhor desempenho na tarefa, e você deve considerar quando essa troca faz sentido" e "A natureza autônoma dos agentes implica custos mais altos e o potencial de erros que se acumulam". A segunda frase afirma a tese deste artigo sem nenhum número associado.

Os multiplicadores que circulam não são intercambiáveis. "3-10x" circula como afirmação sobre a quantidade de chamadas ao LLM e "5-30x" como afirmação sobre tokens por tarefa, nenhuma delas com uma fonte primária rastreável. O único valor com premissas visíveis, [12.4x de um post no dev.to](https://dev.to/awxglobal/why-your-llm-agent-costs-10x-more-than-your-estimate-4o78), é construído a partir de um system prompt de 800 tokens reenviado a cada chamada, 4 turnos por requisição e 3.5 chamadas de ferramenta com 250 tokens de overhead cada, contra uma baseline que conta apenas o prompt do próprio usuário e a resposta do modelo. Seu 12.4x é, portanto, uma afirmação sobre a razão de overhead de prompt e ferramentas com contagem de turnos fixa, não sobre um trabalho inteiro; mude a contagem de turnos e o múltiplo muda. Essa é a única derivação do gênero que você consegue auditar, e auditá-la mostra que ela não mede o que as outras duas faixas medem. Os posts de framework que efetivamente comparam formatos ([Sashido](https://www.sashido.io/en/blog/agentic-workflows-roi-without-expensive-agents), [LindleyLabs](https://lindleylabs.com/blog/agent-or-pipeline-a-decision-framework-for-ai-engineers), [Retool](https://retool.com/resources/ai-workflows-vs-agents)) publicam fórmulas e árvores de decisão sem nenhuma variável preenchida.

Existe um 10x genuíno e com fonte neste espaço, e ele não é a afirmação que eliminei: o [multiplicador de leitura de cache da Anthropic é exatamente 0.1x o input base](https://platform.claude.com/docs/en/about-claude/pricing), então um token de input em cache é precisamente dez vezes mais barato do que um sem cache. Isso se aplica ao componente de prefixo em cache dos tokens de input, nada além disso.

Regra para o restante deste texto: toda razão aqui impressa é derivada na própria página a partir de premissas declaradas. Nenhuma é citada.

## Para onde vai o dinheiro: duas funções de custo, uma quadrática

O mecanismo primeiro, para que todo número posterior seja falseável por inspeção.

A Messages API é stateless. A chamada \`i\` de um agente carrega, portanto, \`In_i = B + (i-1)g\`, onde \`B = S + T + P0\` é o system prompt, as definições de ferramentas e o payload inicial, e \`g = a + r\` é o crescimento por turno (output do assistente mais o resultado da ferramenta anexado de volta). Somando sobre N chamadas:

\`\`\`
I(N) = N*B + g*N*(N-1)/2
\`\`\`

O primeiro termo é o imposto de prefixo, linear em N. O segundo é o imposto de acumulação, quadrático em N. Um token produzido no turno \`i\` é relido como input mais \`(N - i)\` vezes.

O custo do workflow com escopo definido é:

\`\`\`
C_wf = sum over k of [ p_in^k*(s_k + t_k + d_k) + p_out^k*o_k ]
\`\`\`

Linear em K, porque o passo k recebe seus inputs declarados \`d_k\` e nunca o transcript dos passos 1 até k-1. Note o \`^k\` no preço: um workflow pode rotear cada passo para um modelo diferente sem penalidade. Um agente de loop único paga uma reescrita completa de cache do seu prefixo acumulado toda vez que troca de modelo no meio da conversa, então na prática ele fixa um modelo para o loop inteiro. Roteamento por chamada dentro de uma arquitetura de agente exige uma fronteira de subagente, que é em si uma decisão de escopo e custa um prefixo novo por subagente.

O limite da decomposição é exato, não retórico. Dividir um loop de N chamadas em K segmentos com escopo divide o termo de acumulação por exatamente K, já que \`K * g*(N/K)^2/2 = g*N^2/(2K)\`. Uma divisão em três passos limita a economia de acumulação a 3x. Qualquer 10x alegado a partir de um workflow de três passos está vindo do imposto de prefixo, da largura do payload ou do roteamento de modelos, não de quebrar a quadrática.

As definições de ferramentas são um componente real de B. A Anthropic relata que uma configuração com cinco servidores MCP (GitHub, Slack, Sentry, Grafana, Splunk) [consome aproximadamente 55,000 tokens de definições](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool) antes de o modelo fazer qualquer trabalho. Ao preço de tabela, isso são $0.275 por chamada sem cache no Opus 4.8, e esse número mantém a contagem de tokens constante através da fronteira de tokenizador descrita no fim deste texto, então trate-o como um piso e não como uma estimativa.

## O exemplo trabalhado: triagem de suporte, com todas as premissas impressas

O trabalho: classificar um ticket, consultar a conta, buscar na base de conhecimento, redigir uma resposta, revisá-la.

| Parâmetro | Símbolo | Valor do agente | Valor do workflow | De onde vem |
|---|---|---|---|---|
| System prompt | S | 1,500 tok | 250-600 tok por passo | Premissa: um prompt que faz tudo vs quatro prompts estreitos |
| Definições de ferramentas | T | 6 ferramentas, 900 tok | 30 tok por passo, 120 tok no total | Premissa; nenhum fornecedor publica um valor por ferramenta |
| Payload inicial | P0 | 600 tok | 600 tok (texto do ticket) | O mesmo ticket dos dois lados |
| Prefixo | B = S+T+P0 | 3,000 tok | n/a (por passo) | Soma dos itens acima |
| Crescimento por turno | g = a + r | 1,000 tok | 0 (nenhum transcript carregado) | a=300 de output, r=700 de resultado de ferramenta |
| Chamadas / passos | N / K | 8 chamadas | 4 passos com LLM + 2 determinísticos | Julgamento sobre o que o trabalho exige |
| Preço | p_in / p_out | $3.00 / $15.00 por MTok | igual | [Preço de tabela do Sonnet 4.6](https://platform.claude.com/docs/en/about-claude/pricing), verificado em 2026-07-22 |

Cada uma dessas contagens de tokens é uma premissa declarada, não uma medição. Nenhuma veio de um endpoint de contagem de tokens.

**Contabilidade do agente, N=8.** O input cresce em exatamente g = 1,000 por chamada, então a tabela é uma progressão aritmética de 3,000 a 10,000; apenas os extremos e a segunda linha são informativos.

| Chamada | Tokens de input | Input acumulado | Tokens de output | Custo acumulado |
|---|---|---|---|---|
| 1 | 3,000 | 3,000 | 300 | $0.0135 |
| 2 | 4,000 | 7,000 | 300 | $0.0300 |
| ... | +1,000 cada | | 300 | |
| 8 | 10,000 | 52,000 | 300 | $0.1920 |

O total de 52,000 confere com a forma fechada: \`8*3,000 + 1,000*(8*7/2) = 24,000 + 28,000\`. Custo: 52,000 x $3/MTok = $0.156 de input, mais 2,400 x $15/MTok = $0.036 de output. **$0.192 por ticket.**

**Contabilidade do workflow, mesmo trabalho.** As colunas de prefixo e de payload aparecem separadas, porque é dessa separação que vêm as duas alavancas abaixo.

| Passo | LLM? | Modelo | System | Defs. de ferramentas | Dados declarados | Input | Output | Custo do passo |
|---|---|---|---|---|---|---|---|---|
| Classificar | sim | Sonnet 4.6 | 250 | 30 | 600 | 880 | 60 | $0.00354 |
| Consulta de conta | não | (determinístico) | 0 | 0 | 0 | 0 | 0 | $0 |
| Recuperação na base | não | (determinístico) | 0 | 0 | 0 | 0 | 0 | $0 |
| Montagem da consulta | sim | Sonnet 4.6 | 250 | 30 | 70 | 350 | 25 | $0.00143 |
| Redação | sim | Sonnet 4.6 | 600 | 30 | 1,810 | 2,440 | 400 | $0.01332 |
| Revisão | sim | Sonnet 4.6 | 600 | 30 | 450 | 1,080 | 80 | $0.00444 |
| **Total** | | | **1,700** | **120** | **2,930** | **4,750** | **565** | **$0.02273** |

Os dados declarados do passo de redação são: ticket 600 + rótulo 60 + fatos da conta 250 + os 3 melhores trechos da base 900 = 1,810.

Essa contabilidade precifica somente tokens de modelo. No produto hospedado, um nó terminal de workflow também custa 1 crédito fixo ($0.001), o que adiciona $0.006 para estes 6 nós e leva o workflow a $0.0287. Toda razão abaixo é a comparação apenas por tokens; a versão com custo hospedado do número de destaque é declarada onde ela passa a importar pela primeira vez.

**Destaque para esta configuração: 8.4x** ($0.192 / $0.02273), ou 6.7x quando se inclui a taxa hospedada por nó. Só a razão de tokens de input é 10.9x (52,000 / 4,750). A razão de tokens de output é apenas 4.2x (2,400 / 565), e é isso que puxa o valor combinado para baixo de 10x: os dois formatos precisam de fato escrever a mesma resposta de aproximadamente 400 tokens.

Duas alavancas sobrevivem ao caching, porém encolhidas por ele. O **imposto de prefixo**: sem cache, o agente reenvia oito vezes seu prefixo de 3,000 tokens que faz tudo (24,000 tokens) contra os 1,820 tokens de system prompts e definições de ferramentas do workflow no total, 13.2x. Com caching incremental, o componente de prefixo do agente cai para \`1.25B + 0.1(N-1)B\` = 5,850 tokens efetivos, e a alavanca cai para 3.2x. A **largura do payload**, em base instantânea: na sua chamada final, o input do agente contém 7,600 tokens de payload acumulado e transcript de ferramentas (600 iniciais mais 7 x 1,000 de crescimento) contra o passo isolado mais largo do workflow, cujo input declarado é de 1,810 tokens, 4.2x. Essa comparação muda de base contábil assim que o caching é ativado, porque os deltas mais antigos do agente são relidos a 0.1x: em base cumulativa de tokens efetivos, o crescimento de payload do agente custa \`1.25*1,000*7 + 0.1*1,000*21\` = 10,850 tokens contra os 2,930 tokens de dados declarados do workflow, 3.7x. O mecanismo por trás dos dois é que um input declarado é uma projeção sobre a observação bruta.

## A razão é uma função de N, e N é todo o argumento

Sob as premissas do exemplo, o custo do agente é \`0.0015N^2 + 0.012N\` dólares. Confira em N=8: $0.096 + $0.096 = $0.192.

| N (chamadas do agente) | Custo do agente | Custo do workflow | Razão | O que este N representa |
|---|---|---|---|---|
| 2 | $0.030 | $0.0227 | 1.3x | Curto-circuito: "spam, escalar" |
| 4 | $0.072 | $0.0227 | 3.2x | Uso mínimo de ferramentas, sem retentativas |
| 6 | $0.126 | $0.0227 | 5.5x | Uma consulta repetida |
| 8 | $0.192 | $0.0227 | 8.4x | O exemplo trabalhado |
| 12 | $0.360 | $0.0227 | 15.8x | O agente explora a base de conhecimento |
| 20 | $0.840 | $0.0227 | 37.0x | Divagação, ou um ticket genuinamente difícil |

Resolvendo \`0.0015N^2 + 0.012N = 0.022725R\`: a razão é igual a 10 em N = 8.94 chamadas e igual a 3 em N = 3.84 chamadas. Citar uma razão de custo sem citar N não significa nada.

Uma restrição de justiça sobre essa tabela: as linhas de N alto só são legítimas se o trabalho realmente precisar de tantas chamadas. Um agente que gasta 20 chamadas para fazer o que um workflow faz em 4 está divagando, o que é uma constatação de competência e precisa ser argumentado como tal, não contrabandeado para dentro de uma tabela de custos.

## Coloque o agente em cache direito, depois compare

As razões publicadas entre workflow e agente geralmente comparam contra um agente sem cache. O caching é para onde vai a maior parte da diferença, então precifique-o antes de comparar.

Os [multiplicadores de caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching) da Anthropic são exatos: escrita de cache de 5 minutos = 1.25x o input base, escrita de 1 hora = 2x, leitura de cache = 0.1x. O ponto de equilíbrio também é publicado: o cache de 5 minutos se paga após uma única leitura (1.25 + 0.1 = 1.35x contra 2x sem cache); o cache de 1 hora precisa de duas leituras (2 + 0.2 = 2.2x contra 3x).

Com caching incremental multi-turno, o input efetivo do agente se torna:

\`\`\`
1.25B + 0.1(N-1)B + 0.1g(N-2)(N-1)/2 + 1.25g(N-1)
\`\`\`

O coeficiente quadrático cai de \`p_in*g/2\` para \`0.1*p_in*g/2\`, um desconto de exatamente 10x justamente sobre o termo que o workflow estava vencendo.

Em N=8, o input efetivo por chamada fica em 3,750 / 1,550 / 1,650 / 1,750 / 1,850 / 1,950 / 2,050 / 2,150 = 16,700 tokens contra 52,000 sem cache. Isso são $0.0501 de input mais $0.036 de output = **$0.0861**. O caching corta o custo do agente em 55% e derruba o destaque de 8.4x para **3.8x**.

Note o que domina uma vez que o caching está ativo: a escrita a 1.25x do delta de cada turno, \`1.25 * 1,000 * 7 = 8,750\` dos 16,700 tokens efetivos, 52%. A vantagem que sobra para o workflow é o imposto de prefixo e a largura do payload, não a quadrática de reenvio.

O caching achata a diferença sem fechá-la. Em N=20, o agente com cache custa $0.241 contra $0.840 sem cache, ainda 10.6x o workflow, porque 19 escritas de cache a 1.25x mais 20 turnos de conteúdo gerado são irredutíveis.

O workflow captura quase nada desse mesmo benefício aqui. O prefixo mínimo passível de cache no Sonnet 4.6 é de 1,024 tokens (verificado contra a documentação de caching em 2026-07-22; são 512 no Fable 5 e no Mythos 5, 2,048 no Opus 4.7, e 4,096 no Opus 4.6, Opus 4.5 e Haiku 4.5). O prefixo estável de cada passo do workflow aqui é seu system prompt mais as definições de ferramentas, de 280 a 630 tokens, abaixo do limiar em todos esses modelos. Prefixos abaixo do mínimo falham silenciosamente: nenhum erro é retornado e tanto \`cache_creation_input_tokens\` quanto \`cache_read_input_tokens\` marcam 0. Note que rotear um passo para o Haiku 4.5 eleva seu limiar para 4,096, então a configuração roteada abaixo fica mais distante de ser passível de cache, não mais próxima.

A correção acionável tem um ponto de equilíbrio publicado. Consolide o prefixo de um passo de alto volume acima do mínimo passível de cache para o modelo em que ele roda e coloque o breakpoint depois dele, de modo que toda execução daquele passo leia a 0.1x. No TTL de 5 minutos isso se paga a partir da segunda requisição, e as [leituras de cache renovam o TTL gratuitamente](https://platform.claude.com/docs/en/build-with-claude/prompt-caching), então um passo acionado pelo menos a cada cinco minutos permanece aquecido indefinidamente ao preço de escrita.

Uma coisa que o caching não faz: tokens em cache [continuam ocupando a janela de contexto](https://platform.claude.com/docs/en/build-with-claude/context-windows). Ele muda o que você paga por esses tokens, não se eles contam. Não salva ninguém da exaustão de contexto nem da degradação de contexto.

## A grade de quatro células, e onde um 10x real de fato existe

Rotear classificação, montagem da consulta e revisão para o Haiku 4.5 ($1/$5) e apenas a redação para o Sonnet 4.6 derruba o workflow para $0.0165 por ticket (classificação $0.00118 + consulta $0.000475 + redação $0.01332 + revisão $0.00148).

| | Workflow, mesmo modelo ($0.0227) | Workflow, roteado ($0.0165) |
|---|---|---|
| **Agente, sem cache ($0.192)** | 8.4x | 11.7x |
| **Agente, com cache ($0.0861)** | 3.8x | 5.2x |

Meu padrão é a célula superior direita invertida: coloque o agente em cache, roteie o workflow e espere 5.2x. Abaixo de aproximadamente N=4 eu nem construiria o workflow, porque a razão fica abaixo de 3x e o custo de construção não se paga (veja a seção final); acima de aproximadamente N=12 a quadrática toma a decisão por você.

Um agente de loop único precisa rodar seu único modelo fixado em todas as chamadas. Um agente em Opus 4.8 ($5/$25) não é uma troca equivalente com as mesmas contagens de tokens, porque o Opus 4.7 em diante usa um tokenizador mais novo que produz aproximadamente 30% mais tokens para o mesmo texto. Aplicando esse acréscimo: cerca de 67,600 de input e 3,120 de output, o que dá $0.338 + $0.078 = $0.416, contra os $0.0165 do workflow roteado, ou 25.3x. Isso é um argumento de roteamento, não um argumento de janela de contexto.

A afirmação geral apenas a partir da disciplina de contexto, derivada: com o agente em cache e os dois formatos no mesmo modelo, a razão fica em 2.8x em N=6, 3.8x em N=8 e 5.8x em N=12. Ou seja, aproximadamente 3x a 6x dentro de uma faixa plausível de N, e qualquer coisa acima disso é uma decisão de caching ou de roteamento que precisa ser declarada como tal.

A estrutura de preços dos fornecedores torna o roteamento previsível. Todo modelo atual da Anthropic precifica o output em exatamente 5x o input (Opus 4.8 $5/$25, Sonnet 4.6 $3/$15, Haiku 4.5 $1/$5). Todo modelo atual da OpenAI precifica o output em 6x o input (gpt-5.6-sol $5.00/$30.00, gpt-5.4 $2.50/$15.00, gpt-5.4-mini $0.75/$4.50), com o gpt-5.4-nano como única exceção a 6.25x ($0.20/$1.25). A [DeepSeek](https://api-docs.deepseek.com/quick_start/pricing) precifica o output em exatamente 2x o input com cache-miss (deepseek-v4-flash $0.14/$0.28, deepseek-v4-pro $0.435/$0.87). Dentro de um mesmo fornecedor, a mistura input:output determina o perfil de custo mais do que a escolha do modelo. E o tier de batch é uma quinta alavanca, exclusiva de workflows, para passos não sensíveis a latência: um desconto fixo de 50% tanto no input quanto no output na Anthropic e na OpenAI, metade da tarifa padrão no Gemini, acumulando com os multiplicadores de caching na Anthropic.

## Onde este modelo quebra

| Condição | Efeito sobre a razão | Magnitude aqui | Por que acontece |
|---|---|---|---|
| Trabalhos curtos (N<4) | Colapsa, pode inverter | 1.3x em N=2 | O agente entra em curto-circuito; o workflow sempre percorre seu caminho fixo |
| Trabalho dominado por output | Tende a 1 | 2.2x para um relatório de 5,000 palavras em N=8 | Os dois formatos escrevem o mesmo entregável |
| Grande contexto compartilhado | Pode inverter | 5D vs 1.95D em um documento de 50k | O workflow reenvia por passo, a menos que coloque o documento em cache antes |
| Pesquisa em largura paralelizável | Favorece multiagente | +90.2% em uma avaliação de fornecedor | A autonomia compra uma cobertura que o pipeline não consegue enumerar |
| Busca de ferramentas (carregamento adiado) | Encolhe a vantagem de prefixo | o fornecedor alega corte de definições >85% | O agente captura a economia de prefixo sem ser rearquitetado |
| Conjunto > 30-50 ferramentas | Favorece o workflow, por correção | não precificado | A acurácia de seleção de ferramentas se degrada |
| Sobretaxa de ferramentas dependente do modelo | Desloca B | 290 a 804 tok entre modelos | Custo fixo de system prompt antes de qualquer schema |
| Cobranças de ferramentas do lado do servidor | Fora do modelo de tokens | $10 por 1,000 buscas na web | Cobrança por chamada, não por token |
| Mudança de tokenizador / reversão de preço | Invalida valores datados | ~30% mais tokens; $2/$10 para $3/$15 | Novo tokenizador a partir do Opus 4.7; o preço promocional do Sonnet 5 termina em 31 ago 2026 |

Quatro delas merecem detalhamento.

**Trabalhos dominados por output.** Um relatório de 5,000 palavras são aproximadamente 6,700 tokens de output, $0.1005 a $15/MTok dos dois lados. Mantendo os inputs do exemplo (agente $0.156, workflow $0.01425), a razão é $0.2565 / $0.1145 = 2.2x, e ela continua caindo conforme o entregável cresce.

**Grande contexto compartilhado** é o caso de inversão. Se todo passo precisa do mesmo documento de 50k tokens, um workflow de cinco passos o reenvia cinco vezes (5D) enquanto um agente de oito chamadas com cache paga 1.25D + 7 x 0.1D = 1.95D. O workflow só vence se colocar o documento primeiro, antes da instrução específica do passo, e o mantiver em cache (1.25D + 4 x 0.1D = 1.65D).

**Pesquisa paralelizável é o caso publicado mais forte contra toda esta tese.** A Anthropic relata que um sistema multiagente, um Claude Opus 4 líder orquestrando subagentes Claude Sonnet 4, [superou o Claude Opus 4 de agente único em 90.2%](https://www.anthropic.com/engineering/multi-agent-research-system) na avaliação interna de pesquisa deles, e, no mesmo post, que agentes usam aproximadamente 4x os tokens de uma interação de chat enquanto sistemas multiagente usam aproximadamente 15x. Isso é a autonomia comprando um grande ganho de qualidade a um grande múltiplo de custo, e é também uma arquitetura de agente fazendo roteamento de modelo por passo através de uma fronteira de subagente. A própria pré-condição da Anthropic é o enquadramento honesto: só compensa quando o valor da tarefa cobre o multiplicador, e é uma escolha ruim quando todos os agentes precisam do mesmo contexto ou o trabalho tem muitas dependências.

**A busca de ferramentas** é o contra-argumento mais forte especificamente contra o caso do imposto de prefixo. A Anthropic afirma que o carregamento adiado de ferramentas [tipicamente corta o contexto de definições de ferramentas em mais de 85%](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool), carregando apenas as 3-5 ferramentas necessárias, o que permite a um agente que faz tudo capturar boa parte da economia de prefixo sem ser rearquitetado. Essa é uma alegação de fornecedor sem metodologia divulgada, e deve ser tratada como tal. O gatilho da própria Anthropic: use busca de ferramentas a partir de 10 ferramentas, ou quando as definições excederem 10k tokens. A mesma página afirma que a acurácia de seleção de ferramentas se degrada quando se passa de 30-50 ferramentas disponíveis, o que dá ao argumento de escopo uma perna de confiabilidade que não depende em nada da matemática de tokens.

## Custo por execução bem-sucedida, e a condição que inverte o argumento

A comparação que de fato importa é \`C/q\`: custo dividido pela taxa de sucesso de cada formato. Uma taxa de reexecução de 20% do workflow multiplica seu custo por 1.2 e reduz o destaque de 8.4x para 7.0x. Um agente que se recupera dentro do contexto, em vez disso, paga alguns turnos extras, precificados quadraticamente.

A consistência entre execuções repetidas colapsa mais rápido que a acurácia de destaque. No artigo original do [tau-bench retail](https://arxiv.org/abs/2406.12045) de 2024, os melhores agentes de function calling da época eram inconsistentes o bastante para que o pass^8 caísse abaixo de 25%. Produção significa rodar o mesmo trabalho muitas vezes, então pass^k é a métrica correta, não pass@1, e é esse ponto estrutural que sustenta o argumento aqui, mais do que qualquer valor absoluto de 2024.

O sucesso também decai com a duração da tarefa de um modo que torna a redução de escopo superlinear em confiabilidade. O [modelo de meia-vida de Toby Ord](https://www.tobyord.com/writing/half-life) prevê que o horizonte de 80% de sucesso é cerca de 1/3 do horizonte de 50%, o de 90% cerca de 1/7 e o de 99% cerca de 1/70; o autor é explícito quanto ao fato de o modelo ser ajustado a um único conjunto de tarefas e de sua generalidade ser desconhecida. As [medições da METR](https://arxiv.org/abs/2503.14499) mostram horizontes de tempo de 80% aproximadamente 5x mais curtos que os de 50%, o que é mais íngreme que o 3x do modelo de meia-vida, então os dois delimitam o efeito em vez de se confirmarem mutuamente. E a falha é estrutural, não apenas uma nota mais baixa: o [estudo HORIZON](https://arxiv.org/html/2604.11978v1), com mais de 3,100 trajetórias, atribui 72.5% das falhas a causas de nível de processo (erro de ambiente, erro de instrução, erro de planejamento, histórico acumulado) e relata uma transição abrupta de robustez parcial para falha quase sistemática. Esse mesmo estudo argumenta que a decomposição sozinha não é a solução: ele pede planejamento hierárquico e verificação em tempo de execução, não apenas dividir a tarefa.

O modelo oposto mais forte é o da [Zartis](https://www.zartis.com/ai-agent-cost-optimisation-why-token-cost-is-the-wrong-number-to-optimise/):

\`\`\`
total_cost_per_task = (token_cost + infrastructure_cost) / reliability_rate
                      + failure_rate * human_remediation_cost
\`\`\`

O exemplo trabalhado deles faz uma arquitetura 5x mais cara por chamada ($0.05 contra $0.01) ser 5.7x mais barata no total (aproximadamente $8,835/dia contra aproximadamente $50,100/dia) assim que a confiabilidade sobe de 70% para 95%. As contagens de tokens, as tarifas horárias e os minutos de remediação deles são premissas declaradas daquele artigo, não medições, e as duas arquiteturas deles diferem em largura de contexto, não em autonomia. A estrutura do argumento continua válida.

Resolva com estes números. Workflow $0.0227, agente com cache $0.0861, delta de $0.0634 por ticket. Se uma falha custa \`H\` em remediação humana e a taxa de sucesso do agente supera a do workflow em \`dq\`, o agente vence quando \`dq * H > 0.0634\`. Com um analista a $100/hora e 10 minutos por remediação, H = $16.67, então uma vantagem de taxa de sucesso de 0.38 pontos percentuais já basta. Com 5 minutos e $80/hora, H = $6.67 e o limiar é de 0.95 pontos. Diga isso com clareza: **a qualquer taxa não trivial de remediação humana, a razão de tokens deixa de ser o termo decisivo.** O 3.8x sobre o qual esse ponto de equilíbrio foi construído é um erro de arredondamento diante de uma diferença de um ponto na taxa de sucesso, e mesmo o 8.4x sem cache precisa de apenas 1.02 pontos de vantagem para virar (delta de $0.1693 contra H = $16.67). Isso corta nos dois sentidos, e é a razão pela qual o argumento de confiabilidade a favor do escopo (horizontes mais curtos, menos ferramentas, contratos verificados entre passos) importa mais do que o argumento de custo que este artigo inteiro acabou de derivar.

Gastar mais também não compra acurácia. No [GAIA](https://hal.cs.princeton.edu/gaia), um agente usando o3 Medium custou $2,828.54 para 28.48% de acurácia, enquanto o Gemini 2.0 Flash custou $7.80 para 32.73%. No mesmo [programa de avaliação](https://arxiv.org/abs/2510.11977), um esforço de raciocínio maior reduziu a acurácia na maioria das 36 combinações de modelo e benchmark testadas.

## N é um resultado, não um input

Tudo acima trata N como um parâmetro. Em produção, ele é emergente, e é por isso que a razão tem uma cauda longa.

A cauda não é uma subida gradual, é uma função degrau, e é isso que a torna difícil de proteger. Um incidente de produção tem essa forma registrada: uma execução seguiu tranquila por várias iterações a aproximadamente 70k tokens de prompt e 700 de completion cada, barata o bastante para que uma projeção baseada em média continuasse aprovando a próxima, e então uma única iteração explodiu para cerca de 2M tokens. Uma média móvel dilui exatamente isso.

O limite que captura esse caso não faz média nenhuma:

\`\`\`
projectedNext       = max(growth projection, last delta x 2, worstCaseSingleIter)
worstCaseSingleIter = cost(full context window, full max output)
\`\`\`

Esse segundo termo é invariante ao padrão de crescimento, o que é a razão de ser dele. Precificado em uma linha de classe Opus com 200k de contexto e 64k de output máximo a $15/$75 por MTok, uma iteração de pior caso custa 200,000 x $15/MTok + 64,000 x $75/MTok = $3.00 + $4.80 = $7.80. Uma única iteração desse modelo pode plausivelmente custar mais que um saldo pequeno de conta, então a proteção dispara já na primeira iteração tranquila em vez de apostar na média.

A estimativa de custo falha para o lado caro em vez do barato pelo mesmo motivo: um modelo sem linha de preço recai para $15/$75 por MTok, o tier mais alto do snapshot, porque um fallback anterior próximo de zero contornava silenciosamente a proteção de orçamento por completo.

O encerramento de uma execução precisa ser classificado antes que o custo por sucesso possa sequer ser calculado. Uma taxonomia de produção enumera exatamente 10 razões de parada em 3 categorias terminais: sucesso (COMPLETED); parcial (MAX_ITERATIONS, TIMEOUT, BUDGET_EXHAUSTED, LOOP_DETECTED, STOPPED_BY_USER), definida como "terminou de forma limpa mas não completou a tarefa como planejado, o output é utilizável mas truncado ou prematuro"; e falha (CANCELLED, NO_TOOLS, ERROR, INACTIVITY_TIMEOUT). TIMEOUT e INACTIVITY_TIMEOUT caem em categorias diferentes deliberadamente: passar de um orçamento de tempo de relógio é parcial, não produzir nenhum token, raciocínio, chamada de ferramenta ou resultado de ferramenta dentro da janela do watchdog é falha.

A âncora do passo determinístico torna a comparação concreta. Um nó terminal de workflow, concluído ou falho, custa 1 crédito fixo ($0.001) no produto hospedado; apenas nós pulados são gratuitos, e a versão self-hosted registra a mesma linha de 1 crédito no ledger para observabilidade, mas nunca a deduz, porque o saldo é ilimitado. Ao preço de tabela de $3/MTok do Sonnet 4.6, um crédito equivale a 333 tokens de input a preço de tabela; no produto hospedado, a margem de 1.8x do LLM em nuvem torna isso cerca de 185 tokens, então qualquer prompt acima de aproximadamente 186 tokens custa mais que um passo determinístico inteiro. Apenas 4 dos aproximadamente 60 tipos de nó da paleta (Agent, Classify, Guardrail, Browser Agent) chegam a invocar um LLM.

## Como rodar isto com os seus próprios números

1. **Meça os tokens.** Toda contagem no exemplo acima é uma premissa. Substitua-as pelo endpoint de contagem de tokens do provedor, aplicado a texto real de tickets, schemas reais de ferramentas e um trecho real da base de conhecimento.
2. **Meça N a partir de traces existentes, não estime.** A razão é aproximadamente quadrática em N, então um N errado é um erro ao quadrado no número de destaque.
3. **Classifique um mês de execuções encerradas por razão de parada e categoria terminal** antes de citar qualquer valor de custo por sucesso. Encerramentos parciais e de falha têm custos de remediação diferentes, e apenas uma das três categorias conta como execução bem-sucedida.

Duas coisas que este modelo não contém, e nenhuma delas deve ser inferida a partir dele. Ele nada diz sobre qualidade de output: ele precifica tokens, e não há nenhuma medição de taxa de sucesso por trás de qualquer valor nele. E ele ignora o custo de engenharia, que é o termo que decide a maioria dessas escolhas na prática. Minha própria estimativa, declarada como premissa assim como tudo o mais aqui: um workflow de cinco passos com contratos declarados entre passos custa aproximadamente três dias de engenheiro para construir e meio dia por mês para manter, contra meio dia para conectar um agente a seis ferramentas. Ao mesmo valor de $100/hora usado para remediação acima, isso são cerca de $2,000 a mais no início e cerca de $400/mês a mais de forma recorrente. Contra o delta de $0.0634 por ticket do agente com cache, só a diferença inicial precisa de aproximadamente 31,500 tickets para se pagar, e a diferença de manutenção precisa de aproximadamente 6,300 tickets por mês além disso. Abaixo desse volume, a linha da tabela de equilíbrio em que você está é a que diz para construir o agente.
`;

export default content;
