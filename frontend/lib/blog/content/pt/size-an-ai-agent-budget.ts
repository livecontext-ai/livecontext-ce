// size-an-ai-agent-budget - pt
// Translated from the English body; the structure must stay identical to it.
// Formulas and code samples are fenced on purpose: an inline code span over
// ~45 chars overflows the page on a phone. The hedges are load-bearing here.
const content = `Um artigo companheiro defende que a maioria dos orçamentos de agentes são números que nunca recusaram uma única chamada, e percorre a maquinaria de aplicação: de que é feito um objeto de orçamento, porque é que um limite dentro da execução só consegue travar a chamada a seguir à cara, e o que cada stack consegue realmente aplicar. Este responde à pergunta que vem a seguir. Está convencido de que o teto deve ser real. Que número põe na caixa?

A resposta curta é que não o pode escolher por intuição, porque a quantidade que está a limitar é assimétrica à direita, superlinear no número de iterações, e abrange três ordens de grandeza entre tipos de passo. A resposta longa é o resto deste artigo: um modelo gerador que pode reproduzir, um fator de segurança derivado, um piso abaixo do qual um limite monetário não pode sequer ser aplicado, e a dimensão de amostra de que precisa antes de lhe ser permitido citar um quantil de cauda.

**Divulgação.** As constantes de implementação e o mecanismo de reserva descritos abaixo vêm do \`agent-service\` da LiveContext, a plataforma a que este blogue pertence. Leia-os como as escolhas de um sistema, verificáveis no seu código-fonte da edição comunitária, não como prática de campo levantada por inquérito. Os preços são instantâneos ilustrativos de um catálogo; o método é independente dos preços.

## Dimensionar um orçamento por passo que consiga calcular

Um passo que corre \`n = k+1\` iterações do modelo com \`k\` chamadas de ferramenta tem um custo esperado determinado pelo prompt fixo \`P0\`, a carga de entrada \`I\`, os tokens \`r\` devolvidos por resultado de ferramenta, a saída \`O\` por turno, e um termo de acumulação proporcional a \`n(n-1)/2\`. O modelo gerador para cada linha abaixo:

\`\`\`
prompt_i = (P0 + I) + (i-1) * (O_turn + r)      i = 1..n
\`\`\`

**Tabela 4a: Parâmetros dos arquétipos por passo.** Estes são **conjuntos de parâmetros construídos, não traços de produção medidos**, publicados para que cada coluna derivada possa ser reproduzida.

| Arquétipo de passo | P0 + I | r por resultado de ferramenta | O por turno | n |
|---|---|---|---|---|
| Classificação | 1,000 | n/a | 30 | 1 |
| Rascunho aumentado por recuperação | 2,000 | 6,000 | 60 no turno de ferramenta, 500 no final | 2 |
| Investigação multiferramenta | 2,500 | 3,000 | 80 no turno de ferramenta, 800 no final | 7 |
| Resumo de documento longo | 120,300 | n/a | 1,500 | 1 |
| Passo de navegador | 1,800 | 8,000 | 120 no turno de ação, 250 no final | 13 |

**Tabela 4b: Dimensionamento por passo.** Os preços vêm de um instantâneo do catálogo do repositório e são ilustrativos, não preços em vigor do fornecedor. O método é independente dos preços. A última coluna usa um S=3 fixo como fator ilustrativo; a secção seguinte substitui-o por um derivado.

| Arquétipo de passo | Classe de modelo, tarifa de tabela ($/1M entrada, saída) | Tokens entrada / saída | Iterações n | Custo esperado | Maior iteração isolada (x a primeira) | Orçamento a S=3 (fixo) |
|---|---|---|---|---|---|---|
| Classificação | classe flash-lite, 0.25 / 1.50 | 1,000 / 30 | 1 | $0.00030 | 1.0x | $0.0009 |
| Rascunho aumentado por recuperação | classe haiku, 1.00 / 5.00 | 10,060 / 560 | 2 | $0.01286 | 4.6x | $0.0386 |
| Investigação multiferramenta | classe sonnet, 3.00 / 15.00 | 82,180 / 1,280 | 7 (6 chamadas de ferramenta) | $0.2657 | 8.6x | $0.797 |
| Resumo de documento longo | classe flash, 0.30 / 2.50 | 120,300 / 1,500 | 1 | $0.0398 | 1.0x | $0.1195 |
| Passo de navegador | classe gpt-5.4, 2.50 / 15.00 | 656,760 / 1,690 | 13 (12 ações, instantâneos de 8,000 tokens) | $1.667 | 40x | $5.00 |

O rácio de 40x entre a primeira e a última iteração no passo de navegador determina o desenho: uma projeção por média móvel subprojeta a iteração fatal em mais de uma ordem de grandeza. É por isso que uma projeção precisa de um ramo de pior caso que ignore por completo o padrão de crescimento, como o artigo companheiro deriva.

### O fator de segurança é derivado, não adivinhado

\`\`\`
S = (n_q / n_p50) ^ alpha        onde alpha = dlogC / dlogn

alpha em [1, ~2.3]: aproxima-se de 1 nos passos de
disparo único, aproxima-se de 2 nos passos dominados
pela acumulação, e excede 2 quando a primeira iteração
é barata face ao contexto acumulado.

alpha:  classificação ~1.0 | doc longo ~1.0 | rascunho RAG 1.77
        investigação multiferramenta 1.81 | navegador 2.03
\`\`\`

Um passo cujo p99 usa o dobro das chamadas de ferramenta do seu p50 precisa de um S à volta de 2.0 se for de disparo único, mas de 3.4 a 4.1 se for intensivo em ferramentas. Adivinhar "2x" subdimensiona sistematicamente exatamente os passos que precisam de margem. Estes alfas são tangentes no ponto de operação; um leitor que meça a secante ao longo de um intervalo de n observado obterá um número ligeiramente maior. Verificação da secante contra o modelo exato: investigação de n 7 para 14 custa 3.66x (a tangente prevê 2^1.81 = 3.51); navegador de n 13 para 26 custa 4.06x (a tangente prevê 2^2.03 = 4.08).

Corolário: **duplicar as iterações permitidas quadruplica aproximadamente o teto monetário.** "Vamos só aumentar um pouco o máximo de iterações" é uma decisão de orçamento de 4x.

Isso também faz de um limite de iterações um mau limite monetário. Com um valor por omissão de plataforma de 100 iterações máximas, o teto do arquétipo de navegador é de 40,374,000 tokens de prompt = $101.11 para um passo (contra $1.667 esperados), e o do arquétipo de investigação é de 15,496,000 tokens = $46.62 (contra $0.266). Como pontos de dados calculados e não como intervalo: 7.7x o n esperado deixa 61x de folga monetária no passo de navegador; 14.3x deixa 175x no passo de investigação.

### O piso de aplicabilidade

Como a projeção por agente precisa de duas amostras, e se auto-recusa quando uma iteração excede \`budget/3\`, o rácio de granularidade tem de satisfazer:

\`\`\`
g = B_step / cost_of_one_iteration  >=  3
\`\`\`

pelo que o piso para um orçamento por passo é 3x a iteração de pior caso. Contra a iteração de pior caso não limitada do modelo, nenhum dos cinco orçamentos o ultrapassa: classificação $0.0009 contra um piso de $1.04 (g = 0.003), rascunho RAG $0.0386 contra $1.56 (g = 0.074), investigação $0.797 contra $4.68 (g = 0.51), documento longo $0.1195 contra $1.39 (g = 0.26), navegador $5.00 contra $8.76 (g = 1.71).

**Tabela 5: O piso de aplicabilidade** (preços de catálogo e janelas de contexto ilustrativos; substitua pelos seus)

| Classe de modelo | Iteração de pior caso, contexto não limitado | Orçamento mínimo aplicável (3x) | Iteração de pior caso com limites de admissão de 30K/2K | Orçamento mínimo aplicável com limites |
|---|---|---|---|---|
| flash-lite | $0.348 | $1.04 | $0.0105 | $0.032 |
| haiku | $0.520 | $1.56 | $0.040 | $0.120 |
| flash | $0.464 | $1.39 | $0.014 | $0.042 |
| sonnet | $1.560 | $4.68 | $0.120 | $0.360 |
| gpt-5.4 | $2.920 | $8.76 | $0.105 | $0.315 |

Qualquer limite monetário por passo abaixo do piso da coluna não limitada é contabilidade, não aplicação. A correção são **limites de admissão nas entradas**, não um orçamento maior: limitar o prompt admitido a 30K tokens e \`max_tokens\` a 2K faz o piso colapsar 13 a 33x.

Mas os limites de admissão alteram o próprio perfil de custo do passo, pelo que \`B_step\` tem de ser rederivado sob eles, e os limites têm de ser compatíveis com o passo à partida:

- **Passo de investigação**: compatível tal como está. O prompt da sua maior iteração é de cerca de 21K, abaixo do limite de 30K, pelo que o seu orçamento sobrevive inalterado e g sobe de 0.51 para 6.6.
- **Passo de navegador**: ultrapassa os 30K aproximadamente na iteração 4 (cada instantâneo acrescenta 8,120 tokens). Cortar para os últimos três instantâneos faz a maior iteração cair para cerca de 26K, o custo esperado para $0.754, o orçamento a S=3 para $2.26, e g para 21.5.
- **Passo de documento longo**: um limite de prompt de 30K recusa-o de imediato, já que a sua única iteração tem 120K tokens. Limitar o prompt admitido ao seu próprio tamanho de entrada ainda deixa g = 2.8, abaixo do piso. O seu n está fixo em 1, pelo que o controlo aí é o próprio tamanho da entrada, não um limite monetário.

A regra dos dois regimes: os passos abaixo do ponto de cruzamento estão limitados por construção (n fixo em 1 ou 2, \`I\` pequeno) e devem ser controlados limitando as entradas; os passos acima dele são os únicos em que um limite monetário faz trabalho real. **Limite as entradas nos passos baratos, limite o dinheiro nos caros.**

Um esclarecimento sobre o que "desligado por omissão" significa nesta implementação, porque é fácil percebê-lo ao contrário. O teto de pior caso está **sempre ligado** para qualquer modelo cuja linha de catálogo inclua uma janela de contexto e um máximo de tokens de saída: ambas as guardas tomam \`max(growth, lastDelta*2, worstCase)\` incondicionalmente. O que vem desligado por omissão é o comportamento separado de falhar em modo **fechado** para modelos *sem* esses metadados (\`requireCtxWindow\`). A razão documentada é uma janela de migração: instantâneos de preços antigos sem essas colunas negariam de outro modo cada turno de conversa.

### Quantis, amostras e falsos cortes compostos

Escolher o quantil é escolher a taxa de falsos cortes. Se \`B_step\` for o quantil q do custo legítimo observado, a taxa de falsos cortes por passo é exatamente \`1-q\` por construção. Isso é uma decisão de produto, não uma decisão de estatística.

Reconcilie isso com o fator de segurança antes de usar os dois: a fórmula S acima é o estimador que usa quando a cauda do *custo* é imensurável mas a cauda de *n* é conhecida. O q que escolhe para o quantil e o \`n_q\` que alimenta ao S têm de ser o mesmo quantil. Para um passo de n fixo, \`n_q / n_p50 = 1\` e S degenera em 1, pelo que o quantil tem de vir, em vez disso, da variância do tamanho da entrada.

Dimensão de amostra antes de poder citar um quantil de cauda: \`1/sqrt(n(1-q))\` é o erro-padrão relativo da *contagem de excedências* na cauda, pelo que uma estimativa dessa contagem com mais ou menos 30% precisa de \`n ~ 11/(1-q)\`. p90 precisa de cerca de 111 execuções, p95 de cerca de 220, p99 de cerca de 1,100, p99.5 de cerca de 2,200. Trate-os como limites inferiores: o erro no *valor* em dólares do quantil depende da densidade na cauda, e para uma distribuição de custo assimétrica à direita é materialmente pior. Abaixo de aproximadamente 200 execuções não pode honestamente reivindicar um p99, e deve dimensionar antes a partir do pior caso estrutural.

Os falsos cortes por passo compõem-se. Com k passos limitados cada um no seu próprio p99, e assumindo custos por passo independentes e que cada execução percorre todos os k passos, a fração de execuções que atinge um limite algures é \`1 - q^k\`:

\`\`\`
k = 3   ->  3.0%
k = 10  ->  9.6%
k = 20  -> 18.2%

Um limite por passo a p95 ao longo de 10 passos mata 40.1% das execuções.

Para atingir um objetivo de 1% ao nível da execução:  q_step = (1 - target)^(1/k)
  k=3  -> p99.666 | k=10 -> p99.900 | k=50 -> p99.980
\`\`\`

A correlação positiva entre passos (uma entrada sobredimensionada ao nível da execução a inflacionar vários de uma só vez) baixa a taxa verdadeira, pelo que deve tratar estes valores como o extremo pessimista.

Dimensione a partir da distribuição, não da média: o custo por passo é assimétrico à direita, a média situa-se à volta do p70, e dimensionar a partir dela mata aproximadamente 30% das execuções legítimas de passos, o que corresponde a \`1 - 0.7^k\` das execuções.

Recolha cinco campos por execução de passo: tokens de prompt, tokens de conclusão, contagem de chamadas de ferramenta, id do modelo, razão terminal de paragem. Quatro dos cinco são exatamente aquilo que uma guarda pré-iteração já consome, portanto se consegue aplicar consegue medir.

Para calibrar n, duas âncoras independentes: as próprias regras de escalonamento da Anthropic (averiguação simples de factos com 1 agente e 3 a 10 chamadas de ferramenta; comparações diretas com 2 a 4 subagentes e 10 a 15 chamadas cada; investigação complexa com mais de 10 subagentes), e uma trajetória média de correção de issues do GitHub que atinge um pico de contexto de 48.4K tokens ao fim de 40 passos, com cerca de 1.0M tokens acumulados ao longo da trajetória ([arXiv 2509.23586](https://arxiv.org/html/2509.23586v1)). Face a essas, o amplamente reportado valor por omissão de 25 super-passos (ainda o valor por omissão do esquema do langgraph-sdk, aproximadamente 12 chamadas de ferramenta num ciclo ReAct) mata trabalho real, enquanto um limite de 200 passos não faz nada.

**O procedimento:**

1. Recolha execuções de passos com os cinco campos.
2. Calcule o quantil por passo necessário a partir do seu objetivo ao nível da execução: \`q_step = (1 - target)^(1/k)\`.
3. Verifique se a sua amostra o suporta: precisa de pelo menos \`11/(1-q_step)\` execuções, o que com k=10 e um objetivo de 1% ao nível da execução dá aproximadamente 11,000. **Se não suportar, pare aqui e dimensione antes a partir do pior caso estrutural limitado (Tabela 5).** Use o quantil que *consegue* estimar apenas para detetar que o limite estrutural está demasiado folgado, não para definir o limite. Este é o caso comum, e fingir o contrário é como um limite dimensionado a p95 acaba a matar 40% das execuções.
4. Se a amostra o suportar, meça alpha regredindo \`log(cost)\` sobre \`log(n)\`, e defina \`B_step\` em \`q_step\`.
5. Verifique \`g >= 3\` contra a iteração de pior caso **limitada**. Se falhar, acrescente limites de admissão em vez de aumentar o orçamento, e rederive \`B_step\` sob esses limites.
6. Defina o limite da execução (secção seguinte).
7. Sobrecarregue deliberadamente um passo e confirme que a razão de paragem dispara.

## Limites de execução, fan-out, e porque somar os limites dos passos está errado

O limite correto para a execução é sobre as **execuções** de nós no caminho de pior caso, não sobre os nós:

\`\`\`
B_run = max sobre os caminhos de execução P de  soma sobre os nós v em P de  m_v * B_v

m_v = M dentro de um split de largura M
    = L para um corpo de ciclo
    = 1 caso contrário

Ramos exclusivos contribuem com max, não com soma.
\`\`\`

Somar os limites por passo está errado de três maneiras, e elas apontam em sentidos opostos:

1. **Subconta exatamente por M no subgrafo em leque.** Um pipeline de 3 nós que soma $0.837 tem um pior caso real de $41.78 quando os dois últimos nós ficam dentro de um split de largura 50.
2. **Sobreconta ramos exclusivos** que nunca podem ser executados os dois.
3. **É estatisticamente inatingível.** Para 10 passos lognormais independentes com \`p99/p50 = 3\`, cada um limitado a 3x a sua mediana, a soma dos limites situa-se à volta de 1.88x o verdadeiro p99 do total da execução. Trate esse multiplicador como indicativo: os custos reais dos passos estão parcialmente correlacionados, o que encurta a diferença. Um limite que essencialmente não pode disparar também não pode apanhar uma falha estrutural moderada.

**Agregação.** Um fan-out independente precisa de uma margem relativa *menor*, por \`sqrt(M)\`:

\`\`\`
S_run = 1 + (S_step - 1) / sqrt(M)

S_step = 3:  M=5 -> 1.89 | M=10 -> 1.63 | M=50 -> 1.28 | M=200 -> 1.14
\`\`\`

Exemplo resolvido: 50 ramos do passo de investigação (média $0.2657) dimensionados ingenuamente como \`M * B_step\` = $39.85, mas o limite agregado é $17.00, um limite de execução 2.3x mais apertado com o mesmo risco. Enuncie bem alto a hipótese de independência: se o custo do ramo for determinado por uma propriedade ao nível da execução, como uma entrada sobredimensionada distribuída por todos os ramos, os custos ficam totalmente correlacionados e a poupança desaparece por completo.

Os limites por passo e por execução têm funções diferentes, e é isso que define as suas dimensões. O limite do passo é afinado, espera-se que dispare ocasionalmente, e trunca a saída de um passo. O limite da execução é um disjuntor que deve disparar praticamente nunca, e cada disparo é um incidente a investigar (M explodiu, um ciclo reentrou no fan-out, uma entrada era 100x o normal).

**O fan-out precisa de controlo de admissão, não de interceção.** Um limite de execução aplicado como verificação contínua mata ramos a meio do voo e produz um conjunto de resultados parciais não determinístico: 50 ramos a $0.836 cada sob um limite de execução de $10 completam 11 de 50; sob $20, 23 de 50; e quais deles ganham depende da ordem de arranque. Reservar todo o \`M * b\` antes de gerar transforma isso em "recusou-se a correr", o que é explícito e passível de nova tentativa.

O mecanismo de reserva, tal como implementado em \`BudgetReservationService\`:

- No spawn, o montante pedido pelo filho é reservado atomicamente em **cada antecessor** na cadeia de chamadores dentro de uma única transação. A primeira recusa lança exceção, pelo que a transação reverte a atualização de todos os antecessores anteriores. Não existe compensação manual.
- O invariante que isso compra: a soma de \`consumed\` em todos os descendentes de A mantém-se dentro do orçamento de A a cada profundidade, sem qualquer percurso da árvore em tempo de execução no caminho crítico.
- Cada reserva por antecessor é um único UPDATE SQL condicional sem SELECT-depois-UPDATE, pelo que não há TOCTOU. Incrementa a coluna reservada apenas quando o orçamento livre do antecessor cobre o pedido:

  \`\`\`
  free = credit_budget - credits_consumed - credits_reserved
  UPDATE ... SET credits_reserved = credits_reserved + :req
   WHERE id = :ancestor
     AND (credit_budget IS NULL OR free >= :req)
  \`\`\`

  O sucesso é decidido pela contagem de linhas devolvida ser 1. Um antecessor sem limite corresponde com uma escrita sem efeito e devolve também 1.
- Dimensionamento da reserva: um pedido explícito prevalece (negativo é rejeitado); caso contrário o valor por omissão é o **orçamento livre mínimo entre todos os antecessores**, ou zero se todos os antecessores forem ilimitados.
- A liquidação percorre a mesma cadeia uma vez no termo do filho e, por antecessor, devolve a reserva retida e lança o custo real numa única atualização, escrevendo \`consumed\` e \`consumed_from_subagents\` com o mesmo delta na mesma transação, de modo a que o invariante se mantenha por construção. As colunas de reserva estão marcadas como não atualizáveis ao nível do ORM para que um flush sujo não as possa reescrever em silêncio.
- As reservas em fuga são varridas **no arranque**, não por um timeout: um worker sem estado não pode ser dono de nenhuma reserva anterior a si, pelo que toda a reserva retida não nula presente no arranque está, por definição, órfã e é limpa numa única atualização. A varredura nunca pode fazer falhar o arranque.
- A própria cadeia de chamadores vive numa chave reservada do mapa de credenciais, do mais próximo para o mais distante, ausente em invocações de raiz (pelo que a cascata é uma não-operação na raiz) e prefixada pelo agente que faz o spawn para cada filho.

Evidência independente de que é o bloqueio, e não o número, que torna um limite real: numa experiência controlada relatada num preprint de 2026 que cataloga 63 incidentes ([arXiv 2606.04056](https://arxiv.org/abs/2606.04056)), um contador de orçamento em Python com asyncio e condições de corrida ultrapassou o limite 30 vezes em 30, enquanto um contador Python devidamente trancado e um orçamento em Rust com tipos afins ultrapassaram 0 vezes em 30 cada.

A restrição de dimensionamento do pai que daí decorre: para gerar M filhos que retêm cada um o limite b, o pai precisa de orçamento **livre** de pelo menos \`M * b\` no momento do spawn, não do total esperado. Para o arquétipo de investigação com M=50 e b=$0.797, o pai precisa de $39.85 livres mesmo que a execução venha a custar realmente cerca de $13.3. Dimensione um pai pelo gasto esperado e apenas \`1/S\` dos ramos ficam financiados: a S=3, um orçamento livre de $13.29 dividido por uma reserva de $0.797 financia 16 de 50 spawns e recusa 34.

## Ler os registos: sintoma para a dimensão errada

**Tabela 6: Sintoma para a dimensão do orçamento que está errada**

| O que vê | Que dimensão está errada | Sinal de confirmação | Onde é tratado |
|---|---|---|---|
| Corte invisível: conclusões de aspeto normal, saída sistematicamente mais curta | Resposta terminal / observabilidade | A distribuição das razões de paragem mostra paragens parciais enquanto o estado persistido lê COMPLETED | Artigo companheiro, o momento do impacto |
| Demasiado apertado: o orçamento para na iteração 2 ou 3 | Quantil de dimensionamento / fator de segurança | As contagens de tokens das execuções cortadas ficam perto do p50, as entradas parecem banais | O fator de segurança é derivado |
| Decorativo: zero paragens por orçamento numa janela longa | Magnitude do limite | Sem recusas na janela observada; custo máximo de execução observado muito abaixo do limite | Artigo companheiro, o teste de abertura |
| Ultrapassagem / limite tardio: o custo realizado excede o limite por um montante consistente, à escala do modelo, na cauda | Ponto de aplicação / projeção | Pior exatamente onde o passo é mais caro | Artigo companheiro, sobre a lacuna de uma iteração |
| Ligação de âmbito errada: as recusas são ~100% ao nível grosseiro | Âmbito / ordenação das guardas | Os limites por passo nunca vinculam | Artigo companheiro, as cinco partes de um objeto de orçamento |
| Fome no fan-out: N ramos gerados, menos de N execuções | Dimensionamento do pai / política de reserva | Recusas atribuídas à reserva do pai, não ao orçamento do filho | Limites de execução e fan-out |
| Fuga de reservas: recusas a subir ao longo de dias, mesmas entradas e mesmo M | Ciclo de vida da reserva | Reservas retidas nunca liquidadas | Limites de execução e fan-out |
| Unidade errada: contagens de iterações idênticas, dispersão de custo de uma ordem de grandeza | Unidade | O custo médio por iteração varia ~430x entre arquétipos ($0.00030 na classificação contra $0.128 no navegador) | Dimensionar um orçamento por passo |

Um orçamento configurado não é um orçamento aplicado, e há evidência já entregue. O LiteLLM aceitava \`max_budget\` e \`budget_duration\` em modelos adicionados dinamicamente através da sua API, persistia os valores, e nunca os aplicava, enquanto a configuração idêntica no ficheiro de arranque funcionava ([issue #25799](https://github.com/BerriAI/litellm/issues/25799), fechada por um PR posterior). Um defeito irmão cobria orçamentos que nunca reiniciavam depois de expirada a sua duração ([#25495](https://github.com/BerriAI/litellm/issues/25495)). Verifique a aplicação num teste. Não a presuma a partir da presença de um campo.
`;

export default content;
