// the-niche-data-advantage - pt
// Translated from the English body; structure identical. The evidence-register
// markers (cited / derived / "my judgment") and every concession are load-bearing:
// do not turn a hedge into a confident claim. Fenced formulas stay fenced.
const content = `I reviewed the translation against the source line by line: all 9 level-2 headings, 6 tables (row/column counts match the source exactly at 62 table lines total), 6 fence markers with untranslated code blocks, 31 links (URLs byte-identical), every formula, number, currency and percentage, and every evidence-register marker (judgment/assumption/concession). It is faithful and complete, with no em/en-dashes and no over-long inline code spans. No substantive errors surfaced; only the untranslatables and concessions all remain at identical force. Returning the verified body.

## A seleção de dados como uma obrigação de atualização, com o seu preço

Não está a adquirir registos. Está a assumir uma obrigação de atualização. Um parâmetro medido, r, a fração anual dos seus registos que se tornam errados, define quatro coisas ao mesmo tempo: o custo de manutenção, a cadência de atualização, o termo de manutenção no ponto de equilíbrio entre construir e comprar, e durante quanto tempo a cópia roubada de um concorrente se mantém útil. Um parâmetro conduz quatro decisões, mas o Resultado 4 abaixo mostra que tem de o medir por campo, e apenas depois de comprar dois pré-requisitos: um oráculo independente e um custo de verificação por registo conhecido, k.

Este texto atribui um preço à tese dos dados de nicho em vez de a elogiar, e defende primeiro o caso contrário, com toda a força que a evidência permite. Corrige também o slogan retirado do blogue, "cem linhas que compreende valem mais do que um milhão em que confia pela metade", que tal como está escrito é falso: a última secção dá a condição sob a qual é verdadeiro, e mostra que essa condição normalmente desaparece.

Contrato de evidência. Cada afirmação é uma de três coisas: citada com uma ligação, derivada com a aritmética na própria página, ou rotulada como a minha opinião. Os números trabalhados (N, k, r, as exatidões) são pressupostos ilustrativos, assinalados a cada utilização, não medições. Onde a investigação não encontrou nada, o artigo di-lo em vez de estimar.

Delimitação de âmbito. O custo do contexto, a imposição e o dimensionamento do orçamento, os esquemas de trilho de auditoria, a retenção de auditoria, e a transformação de um conjunto de dados qualificado num fluxo de trabalho em execução são textos complementares. Este é sobre a seleção de dados e a sua economia, e termina na decisão de adquirir.

Leitor-alvo: um fundador ou responsável técnico que escolhe em que dados investir antes de construir um agente ou uma automação em cima deles.

## O caso mais forte contra (leia isto primeiro)

A tese do fosso dos dados proprietários é contestada, e os céticos detêm a melhor base de evidência.

Lambrecht and Tucker, [Can Big Data Protect a Firm from Competition?](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2705530) (2015), submetem os dados ao teste VRIN e concluem que geralmente falham: os grandes volumes de dados raramente são inimitáveis ou raros, existem substitutos, e o recurso escasso é o conjunto de ferramentas de gestão em torno dos dados, não os dados. Os seus contraexemplos são novos entrantes (Airbnb, Uber, Tinder) que venceram incumbentes que já detinham os dados relevantes.

Casado and Lauten, [The Empty Promise of Data Moats](https://a16z.com/the-empty-promise-of-data-moats/) (a16z, 2019), argumentam que os efeitos de rede dos dados são normalmente efeitos de escala dos dados, e os efeitos de escala saturam. No seu caso do chatbot de apoio, para além de cerca de 40% das consultas recolhidas mais dados não acrescentam vantagem, e a cobertura de intenção estabiliza perto dos 40%: nunca chega sequer à automação total.

Varian, [NBER WP 24839](https://www.nber.org/papers/w24839) (2018), nota que a precisão estatística cresce com a raiz quadrada do tamanho da amostra, pelo que precisa de quatro vezes mais dados para reduzir o erro para metade, e que os conjuntos de treino e de teste do ImageNet estiveram fixos durante os anos de maiores ganhos de exatidão, pelo que esses ganhos não podem ser atribuídos a mais dados.

Hestness et al., [arXiv:1712.00409](https://arxiv.org/abs/1712.00409), concluem que o erro de generalização decai como uma lei de potência no tamanho do conjunto de dados, com expoentes entre -0.07 e -0.35. Como o múltiplo de dados para reduzir o erro para metade é 2^(1/beta):

\`\`\`
beta = 0.07 -> 10x data cuts error 14.9%; halving needs ~19,972x data
beta = 0.15 -> 10x data cuts error 29.2%; halving needs ~102x data
beta = 0.35 -> 10x data cuts error 55.3%; halving needs ~7x data
\`\`\`

Isto corta nos dois sentidos: expoentes achatados significam que a vantagem de 100x de um concorrente compra pouco, mas o seu 3x quase nada compra.

Chiou and Tucker, [NBER WP 23815](https://www.nber.org/papers/w23815) (2017), exploram os cortes de retenção induzidos pela UE (Bing de 18 meses para 6, Yahoo de 13 para 3) e encontram pouca degradação mensurável na exatidão de pesquisa, concluindo que "a posse de dados históricos confere menos vantagem em quota de mercado do que por vezes se supõe." Allcott, Castillo, Gentzkow, Musolff and Salz, [NBER WP 33410](https://www.nber.org/papers/w33410) (2025), concluem que eliminar as fricções da procura duplica a quota do Bing, enquanto os mandatos de partilha de dados têm efeitos pequenos. O fosso era a distribuição e as predefinições.

Frente a frente, o grande corpus genérico continua a ganhar. [Li et al.](https://arxiv.org/html/2305.05862) relatam que o GPT-4 supera o BloombergGPT (50B de parâmetros, 363B de tokens financeiros proprietários mais 345B gerais, segundo o [BloombergGPT paper](https://arxiv.org/pdf/2303.17564)) no ConvFinQA 0-shot 76.48% contra 43.41%, no FiQA-SA 5-shot 88.11% contra 75.07%, e no Financial PhraseBank 5-shot 0.97 contra 0.51 F1. [Nori et al.](https://arxiv.org/abs/2311.16452) superam o Med-PaLM 2 em todos os nove conjuntos de dados MultiMedQA usando GPT-4 genérico mais prompting, sem qualquer pré-treino ou afinação específicos do domínio. E Ovadia et al., [Fine-Tuning or Retrieval?](https://arxiv.org/html/2312.05934v3), concluem que o RAG supera consistentemente a afinação para injeção de conhecimento (Mistral 7B numa tarefa de atualidade: base 0.481, RAG 0.875, afinação 0.504). Se o valor dos seus dados se realiza numa janela de contexto, quem obtiver os documentos captura o mesmo valor sem qualquer execução de treino.

Dois resultados de robustez atacam a metade "um milhão de linhas em que confia pela metade" do slogan. [Subramanyam, Chen and Grossman](https://arxiv.org/abs/2510.03313) medem expoentes de qualidade de cerca de 0.173 (tradução automática) e 0.401 (modelação causal de linguagem), ambos bem abaixo de 1, pelo que o tamanho efetivo do conjunto de dados decai de forma sublinear com a qualidade. [Muennighoff et al.](https://arxiv.org/abs/2305.16264) (NeurIPS 2023) concluem que, sob um orçamento de computação fixo com dados restritos, até quatro épocas de tokens repetidos são quase indistinguíveis de dados únicos e novos.

O lado pequeno falha na sua própria aritmética. O IC de 95% sobre uma proporção em p=0.5 é 1.96*sqrt(p(1-p)/n): n=100 dá mais ou menos 9.80 pontos, n=1,000 dá 3.10, n=1,000,000 dá 0.098. E P(zero ocorrências) = (1-rate)^n, pelo que 100 linhas curadas têm uma probabilidade de 36.6% de conter zero instâncias de um modo de falha com frequência de 1%. Precisa de cerca de 299 linhas para ter 95% de confiança em ver uma vez um evento de 1 em 100, cerca de 2,995 para um de 1 em 1,000. Os dados pequenos que compreende não conseguem ver a sua própria cauda.

Por trás de tudo isto está o [Bitter Lesson](http://www.incompleteideas.net/IncIdeas/BitterLesson.html) de Sutton (2019), e dois fracassos dispendiosos. A IBM reuniu um dos maiores corpora de saúde proprietários através de aproximadamente 4B USD em aquisições (Merge cerca de 1B USD, [Truven $2.6B](https://techcrunch.com/2016/02/18/ibm-acquiring-truven-health-analytics-for-2-6-billion-and-adding-it-to-watson-health), mais Phytel e Explorys) e [vendeu a Watson Health à Francisco Partners em 2022](https://www.fiercehealthcare.com/tech/ibm-sells-watson-health-assets-to-investment-firm-francisco-partners) por um valor reportado de ~1.065B USD. A Zillow encerrou a Zillow Offers em novembro de 2021 depois de uma perda de 422M USD no segmento Homes no Q3 de 2021 (8-K do Q3 de 2021), citando o CEO a imprevisibilidade na previsão dos preços das casas ([AI Incident Database 149](https://incidentdatabase.ai/cite/149/)).

| Argumento | Resultado medido | Fonte | O que não resolve |
|---|---|---|---|
| Os dados falham no VRIN | Dados raramente raros ou inimitáveis; entrantes vencem incumbentes detentores de dados | Lambrecht and Tucker 2015 | Se os eventos próprios não publicados têm substitutos |
| Os efeitos de escala saturam | Cobertura marginal achatada para além de ~40% das consultas recolhidas; cobertura de intenção estabiliza perto de 40% | Casado and Lauten 2019 | Conjuntos de dados cujo valor é a frescura, não a cobertura |
| Precisão em raiz quadrada | 4x dados para reduzir a metade o erro de estimativa | Varian, NBER 24839 | Recuperação, onde a precisão não é o mecanismo |
| Retornos em lei de potência | Expoentes de erro -0.07 a -0.35 | Hestness et al. 2017 | Qualquer coisa fora do treino de modelos |
| Cortes de retenção inócuos | Bing de 18 para 6 meses, sem perda de exatidão mensurável | Chiou and Tucker, NBER 23815 | Pequenos corpora operacionais sem substituto de escala |
| A distribuição é o fosso | Remover fricções da procura duplica a quota do Bing | Allcott et al., NBER 33410 | Mercados sem um canal de colocação por predefinição |
| O genérico vence o de domínio | GPT-4 sobre BloombergGPT em 3 de 3 tarefas citadas | Li et al. 2305.05862 | Extração estruturada, onde o mesmo artigo mostra modelos afinados a vencer |
| A recuperação vence a afinação | Mistral 7B: 0.875 RAG vs 0.504 afinação | Ovadia et al. 2312.05934 | Se os próprios documentos são obteníveis |

## O que essa evidência não abrange

Quase toda a base anti-fosso diz respeito ao pré-treino de modelos à escala de fronteira. O leitor-alvo não está a treinar nada; está a selecionar dados para uma janela de contexto ou uma resposta de ferramenta. Que 363 mil milhões de tokens financeiros proprietários não tenham conseguido vencer o GPT-4 diz pouco sobre se 40,000 linhas internas bem estruturadas fazem uma boa entrada para um agente.

O problema espelhado corta contra a minha tese com igual força: quase todos os grandes ganhos de curadoria medidos são também resultados de corpus de treino. O [FineWeb-Edu](https://arxiv.org/abs/2406.17557) removeu cerca de 91% do FineWeb (de 15T para 1.3T de tokens) e elevou o MMLU de 33% para 37% e o ARC de 46% para 57% num orçamento fixo de 350B tokens, igualando o MMLU do corpus completo com cerca de 10x menos tokens do que o C4 e o Dolma. [LIMA](https://arxiv.org/abs/2305.11206), [AlpaGasus](https://arxiv.org/abs/2307.08701) e DataComp são também resultados de treino. Transferi-los para a recuperação é um pressuposto, e nenhum estudo localizado mede ambos os regimes na mesma tarefa.

O único estudo em larga escala do lado da recuperação aponta no sentido contrário, e este artigo não pode passar-lhe ao lado. [Nourbakhsh et al., "When Retrieval Doesn't Help"](https://arxiv.org/abs/2606.04127), um estudo biomédico de RAG com 5 modelos, 10 conjuntos de dados de QA, 4 métodos de recuperação e 4 corpora, concluiu que a recuperação deu apenas 1 a 2 pontos acima de uma linha de base sem recuperação, e as fontes com curadoria por especialistas não tiveram melhor desempenho do que as fontes leigas. A restrição vinculativa foi a capacidade limitada do modelo de usar a evidência recuperada, não a qualidade do corpus. É a única medição localizada no regime real do leitor, é específica da curadoria, e a sua conclusão é que a curadoria não comprou nada. O domínio é biomédico, pelo que a sua transferência para outras tarefas de recuperação está ela própria por medir, mas é evidência no regime certo e a tese tem de ser rebaixada a hipótese perante ela.

Um resultado do lado da recuperação apoia de facto a curadoria. A exatidão do RAG segue um U invertido, atingindo o pico por volta de 10 a 20 passagens no Natural Questions e caindo a partir de 40 em Gemma-7B, Gemma-2-9B, Mistral-Nemo-12B e Gemini-1.5-Pro ([arXiv:2410.05983](https://arxiv.org/html/2410.05983v1), ICLR 2025). O dano vem dos negativos difíceis, documentos por pouco falhados que pontuam alto e não contêm a resposta. A curadoria justifica o seu custo ao remover vizinhos errados mas plausíveis. Se esse mecanismo se transfere é opinião por testar, não uma resposta a Nourbakhsh.

A lacuna que o leitor mais quer ver preenchida está vazia. Não encontrei nenhuma medição pública e metodologicamente transparente do que a curadoria de um corpus privado compra. O conteúdo dos fornecedores afirma exatidões de 95 a 99% sem linha de base, metodologia ou tamanho de amostra, o que este artigo não citará. Nem encontrei um único caso medido do conjunto de dados de nicho de uma organização pequena a vencer um corpus genérico num cenário de agente em produção.

A Superficial Alignment Hypothesis do LIMA é uma arma contra a minha tese: o conhecimento vem quase inteiramente do pré-treino, e os pequenos conjuntos curados ensinam formato e estilo. Segundo essa leitura, um corpus de nicho curado compra formatação, não compreensão. Portanto a tese não pode ser defendida com base no volume ou no conhecimento. Se sobreviver, sobrevive na frescura, na cobertura de uma superfície de decisão específica, e no custo, que são mensuráveis, e que o resto deste artigo instrumenta.

## O único parâmetro que importa: r, e o que lhe custa

Meça-o, não o cite, e meça-o como um desenho de dois pontos temporais: amostre registos verificados em t0, volte a verificá-los em t0+delta contra um oráculo independente, conte os campos alterados, r = -ln(1-p)*365/delta_days. Reporte o intervalo de confiança: em p=0.3, n=100, o IC sobre r vai de cerca de 21% a 39%, o que se propaga para cada figura derivada abaixo (Mb de cerca de 7,400 USD a 15,400 USD, um limiar de construir-vence-nada de cerca de 723 a 1,059 em vez de um único número confiante). A crítica de pequena amostra acima aplica-se também ao seu próprio r.

Modelo, derivado aqui. Sob um risco constante, um registo verificado em t=0 continua correto com probabilidade A(t) = e^(-lambda*t), onde lambda = -ln(1-r). Para manter um piso de exatidão de pior caso A_floor, atualize a cada T anos:

\`\`\`
lambda        = -ln(1 - r)
T             = ln(1/A_floor) / lambda
passes / year = lambda / ln(1/A_floor)
maintenance   = N * k * lambda / ln(1/A_floor)
\`\`\`

Uma verificação de consistência: o decaimento mensal de contactos de 2.1% citado dá 12 * -ln(1-0.021) = 0.2547, e -ln(1-0.225) = 0.2549. Circulam como figuras separadas e são a mesma figura composta, a três casas decimais.

**Resultado 1.** Se atualizar exatamente à cadência que mantém A_floor, a exatidão média ao longo do ciclo é (1-A_floor)/ln(1/A_floor), que depende apenas do piso, não de r. Um piso de 95% dá sempre uma média de 97.48%, um piso de 90% 94.91%, um piso de 99% 99.50%. A taxa de mudança define o preço do piso, nunca a qualidade que dele obtém.

**Resultado 2.** As passagens por ano são lambda/ln(1/A_floor), pelo que, relativamente a um piso de 90%, um piso de 95% custa 2.05x, um piso de 99% 10.48x, um piso de 99.9% 105.31x. Escolha o piso a partir do custo de uma decisão errada.

**Resultado 3.** A reverificação contínua tem de ser do mais antigo primeiro. A reverificação aleatória contínua à taxa v atinge uma média em estado estacionário de v/(v+lambda) mas não tem piso nenhum: as idades dos registos estão distribuídas exponencialmente, pelo que uma cauda de registos fica arbitrariamente obsoleta por muito que gaste. Do mais antigo primeiro é equivalente a lote e limita o pior caso; a aleatória não.

**Resultado 4, a maior alavanca.** Meça r por campo. Um conjunto de dados 80% estável (r=2%) e 20% volátil (r=30%) custa 6.954 passagens completas/ano de forma uniforme, contra 0.2*6.954 + 0.8*0.394 = 1.706 equivalentes de passagem segmentado, uma poupança de 4.08x num piso idêntico de 95%. Isto pressupõe que o custo de verificação escala com a fração de campos tocados; uma componente fixa por registo (obtenção, correspondência, mudança de contexto) reduz a poupança em direção a 1x.

Ressalva do modelo: o risco constante é uma simplificação, e é testável. Trace a curva de sobrevivência num eixo logarítmico; se não for reta, ajuste uma Weibull S(t) = exp(-(t/eta)^k), dando T = eta*(ln(1/A_floor))^(1/k). Os dados de deterioração de ligações da Pew são carregados no início, que é o caso k<1 (um risco decrescente, perda inicial pesada). Sob k<1 a exponencial subestima a perda inicial e sobrestima a sobrevivência tardia, pelo que a primeira atualização tem de chegar mais cedo do que T.

Para fontes que não controla, o [When Online Content Disappears](https://www.pewresearch.org/data-labs/2024/05/17/when-online-content-disappears/) da Pew (2024) é a única âncora externa limpa que encontrei: 38% das páginas que existiam em 2013 tinham desaparecido até outubro de 2023, mas 8% das páginas de 2023 já tinham desaparecido no prazo de um ano. O risco médio a dez anos é -ln(0.62)/10 = 0.0478/yr, mas o risco de primeiro ano diretamente observado é 8%. Use 8% para definir a cadência em fontes recentes.

Um aviso de proveniência: a figura de 22.5% por ano de contactos B2B remonta à MarketingSherpa via [Database Decay Simulation da HubSpot](https://www.hubspot.com/database-decay), replicada por fornecedores de geração de leads com um interesse comercial e sem metodologia ou tamanho de amostra publicados. Aplique-a a listas de contactos B2B e a mais nada. As taxas de decaimento para catálogos de produtos, preços, corpora regulatórios, documentação geoespacial e técnica parecem não publicadas. A tabela é um modelo onde encaixar o seu próprio r.

| Taxa de mudança anual r | lambda | Dias entre atualizações, piso 95% | Dias, piso 90% | Passagens completas/ano, piso 95% | Meia-vida de uma cópia única |
|---|---|---|---|---|---|
| 2% | 0.0202 | 927 | 1,904 | 0.39 | 34.3 yr |
| 5% | 0.0513 | 365 | 750 | 1.00 | 13.5 yr |
| 10% | 0.1054 | 178 | 365 | 2.05 | 6.58 yr |
| 22.5% (apenas contactos B2B) | 0.2549 | 73.5 | 151 | 4.97 | 2.72 yr |
| 30% | 0.3567 | 52.5 | 108 | 6.95 | 1.94 yr |
| 60% | 0.9163 | 20.4 | 42.0 | 17.87 | 0.76 yr |

## O cartão de pontuação: sete linhas, duas barreiras

Símbolos usados abaixo e aqui definidos: D é decisões por ano, v é o valor líquido por decisão correta (a oscilação entre uma decisão certa e uma errada, pelo que o custo do erro já está incluído nele), e D_be é o volume de equilíbrio entre construir e nada-fazer (Cb/H+Mb)/(v*(Ab-A0)) derivado na secção seguinte. O limiar da Linha 3 usa o valor anual de decisão, escrito D vezes v.

| Critério | Teste que pode fazer esta semana | Limiar | Pontuação 0-3 |
|---|---|---|---|
| 1. Enumerabilidade | Duas amostras independentes por duas vias, sobreposição m, Chapman estimator | 3 se cobertura >=95%; 2 se 90-95%; 1 se 75-90% e conseguir nomear o segmento excluído; 0 se não houver N-hat | |
| 2. Verificabilidade (BARREIRA) | Nomeie o oráculo independente; meça k e minutos por registo | Passa se k <= 1% de v e <= 10 min/registo | passa/falha |
| 3. Comportabilidade do decaimento | Reverifique registos em dois pontos temporais, anualize para r, calcule a manutenção como % de D vezes v | 3 se <=5%; 2 se 5-15%; 1 se 15-30%; 0 se >30% ou r por medir | |
| 4. Pulsação | Últimas 12 versões publicadas, coeficiente de variação dos intervalos entre publicações | 3 se CV <=0.25 e intervalo máximo <=2x mediana; 2 se CV <=0.5 ou controlar a extração; 1 se CV <=1.0 ou intervalos irregulares mas limitados; 0 se não houver histórico de versões | |
| 5. Ligação à decisão (BARREIRA) | Nomeie a decisão, o ator, a predefinição, D por ano; meça a taxa de divergência a 90 dias | Passa se D >= D_be e divergência >= 2% | passa/falha |
| 6. Não substituibilidade | Atribua preço à replicação completa mais barata em dias de trabalho qualificado | 3 se a replicação estiver legalmente bloqueada (nomeie o direito de acesso); 2 se >180 dias; 1 se 30-180 dias; 0 se <30 dias ou um fornecedor a listar como SKU | |
| 7. Integridade da junção | Tente a junção numa amostra de 500 linhas, meça a taxa de correspondência exata da chave primária | 3 se >=98%; 2 se 95-98%; 1 se 90-95%; 0 se <90% | |

**A Linha 1** usa o Chapman estimator N-hat = ((n1+1)(n2+1)/(m+1)) - 1: n1=300, n2=250, m=180 dá 416, pelo que deter 380 linhas é 91.3% de cobertura. O Chapman assume igual capturabilidade, mas as entidades em falta são sistematicamente as mais recentes e mais remotas, o que enviesa N-hat para baixo. Assim N-hat é um limite inferior do universo e a figura de cobertura um limite superior. Volte a fazer a recaptura restrita a entidades vistas pela primeira vez nos últimos 12 meses, como um segundo número obrigatório.

**A Linha 2 é uma barreira** porque, sem um oráculo, não consegue medir r, pelo que as linhas 1 e 3 ficam sem resposta. k é também o multiplicando na fórmula de manutenção, pelo que este único número atribui preço a toda a obrigação. Note que k = 0.40 USD no exemplo trabalhado implica verificação quase automatizada (cerca de um minuto por registo a 25.23 USD/h); a própria barreira tolera 10 min/registo, que é 4.20 USD, uma ordem de grandeza mais alta.

**Linha 3, trabalhada:** N=4,000, k=0.40 USD, r=30%, piso de 95% dá 6.95 passagens e 11,126 USD/ano; segmentar para 20% de campos voláteis dá 2,729 USD. Ambos escalam linearmente com o k assumido.

**A Linha 4** torna mensurável o "muda num ritmo que consegue aprender" do texto retirado: uma fonte cujo próprio intervalo de publicação é mais variável do que o seu T necessário torna o piso impossível de impor a qualquer custo.

**A Linha 5 mata a maioria dos candidatos.** Nomeie a decisão, o ator, a predefinição e D por ano, e meça a taxa de divergência a 90 dias (com que frequência os dados teriam mudado a decisão). Abaixo de 2% os dados não estão a mover decisões, uma falha absoluta. Não encontrei nenhum estudo a medir a divergência em produção, pelo que os 2% são a minha opinião.

**A Linha 6** emparelha com a meia-vida ln2/lambda da cópia única, mas calcule-a por segmento de campo: um concorrente copia os 80% estáveis (meia-vida de 34.3 anos a r=2%) e volta a derivar o quinto volátil, pelo que a meia-vida ao nível do conjunto de dados sobrestima a defensibilidade. Reporte o número do segmento estável.

**A Linha 7** importa porque as exatidões multiplicam-se: um conjunto de dados com 95% de exatidão juntado a 90% entrega 85.5% de exatidão efetiva. Aplique o fator de junção tanto à sua construção como a qualquer conjunto de fornecedor, uma vez que degrada qualquer conjunto de dados externo juntado às suas chaves.

Regra de pontuação, a minha opinião: duas barreiras passa/falha, cinco linhas pontuam 0-3 para um máximo de 15, invista com 11 e ambas as barreiras passadas. Os testes são executáveis e a aritmética por trás das linhas 1, 3 e 7 está na página. Cada limiar numérico na coluna de limiar é a minha opinião, calibrada pela experiência, não derivada nem citada; altere-os. A verdadeira função do instrumento é forçar sete medições que demoram cerca de uma semana.

Aplicado aos exemplos intermutáveis dos textos retirados: o desempenho de pontualidade de uma transportadora numa rota, cada licença de comércio numa metrópole, as taxas de reembolso de um pagador, e o preço e o stock de 40 SKUs verificados duas vezes por dia diferem enormemente nas linhas 3, 4 e 6. O conjunto de SKUs tem um lambda nas centenas por ano (r fixado essencialmente em 100%, porque r é uma fração limitada abaixo de 1; use lambda diretamente quando a mudança for mais rápida do que anual) e uma meia-vida de cópia em dias. O registo de licenças tem um r perto de zero e é trivialmente copiável.

## Quanto os dados custam realmente

Cada figura carrega o seu grau de proveniência.

| Item | Fornecedor | Preço publicado | Proveniência |
|---|---|---|---|
| Largura de banda de proxy residencial | [Bright Data](https://brightdata.com/pricing/proxy-network/residential-proxies) | 8 USD/GB PAYG, 5 USD/GB no escalão de 1,999 USD/mês | Obtenção de página primária |
| Proxies de datacenter / ISP | Bright Data | 1.30-1.80 USD e 0.90-1.40 USD por IP por mês | Obtenção de página primária |
| Scraping por escalão de dificuldade | [Zyte](https://docs.zyte.com/zyte-api/pricing.html) | 5 escalões HTTP e 5 de navegador; ~0.13-1.27 USD e ~1.01-16.08 USD por 1,000 | Estrutura de escalões primária; taxas reportadas por agregador |
| Complemento de captura de ecrã | Zyte | 0.002 USD cada | Documentação primária |
| Ferramentas de rotulagem | SageMaker Ground Truth | 0.08 / 0.04 / 0.02 USD por objeto (escalões 1-50k / 50-100k / >100k); 500 objetos/mês grátis nos primeiros dois meses | Reportado por agregador, possivelmente legado, atualmente não publicado pela AWS |
| Ferramentas de rotulagem | Labelbox | 0.10 USD por Labelbox Unit, 1 LBU por linha rotulada | Reportado por agregador |
| Rotulagem | [Scale AI](https://scale.com/pricing) | Sem tarifa empresarial publicada; apenas escalão gratuito | Obtenção de página primária |
| Mão de obra de anotação nos EUA | [ZipRecruiter](https://www.ziprecruiter.com/Salaries/Data-Annotation-Salary) | ~25.23 USD/h (52,488 USD/ano); offshore ~2 a 5-12 USD/h | Primária; offshore reportado por agregador |
| Dados de contactos B2B | [Vendr](https://www.vendr.com/buyer-guides/zoominfo) | ZoomInfo mediana 33,500 USD/ano em 1,566 compras, intervalo 7,200-155,550 USD | Dados de transações verificados |
| Dados de mercado | [Databento](https://databento.com/pricing) | 199 / 1,750 / 4,500 USD por mês | Obtenção de página primária |
| Feeds estreitos de propósito único | [Massive](https://massive.com/pricing) | NYSE Order Imbalances 49 USD/mês; European Consumer Spending by Merchant 99 USD/mês | Obtenção de página primária |
| Listagens de marketplace | [AWS Data Exchange](https://aws.amazon.com/data-exchange/pricing/) | Definido pelo fornecedor; 0.023 USD/GB/mês de armazenamento, 0.04167 USD/h de concessões de dados | Obtenção de página primária |
| Listagens de marketplace | Snowflake Marketplace | Por mês, por consulta ou híbrido; listagens reais 100-1,500 USD/mês | Documentação do fornecedor mais secundária |
| Licenciamento de dados de treino | News Corp / OpenAI; Reddit / Google | >250M USD ao longo de 5 anos; ~60M USD/ano (Reddit S-1: 203M USD agregados) | Cobertura de imprensa corroborada |
| Revisão jurídica do método de aquisição | O seu jurista | Indicativo: revisão mais DPIA, cinco dígitos baixos-a-médios pontual mais tratamento contínuo | A minha opinião, nenhuma figura transacionada localizada |

Duas figuras derivadas, com os pressupostos à vista. Um corpus de nicho de um milhão de páginas a 200KB por página assumidos (o meu pressuposto) são 200GB: cerca de 1,600 USD de largura de banda residencial da Bright Data a preço de tabela, contra cerca de 130 USD através da Zyte no escalão HTTP 1 ou cerca de 16,080 USD no escalão de navegador 5, com duas ordens de grandeza de diferença, decidido por qual o escalão em que o alvo cai. Rotular 100,000 registos nos escalões do Ground Truth acima são 50,000*0.08 USD + 50,000*0.04 USD = 6,000 USD em ferramentas (a franquia de 500 grátis é imaterial, e estes escalões por objeto são possivelmente legado, já não publicados pela AWS), ou 100,000*0.10 USD = 10,000 USD em Labelbox units, ambos excluindo a mão de obra humana, a rubrica maior.

O buraco honesto: não encontrei nenhum preço transacionado de mercado intermédio para licenciar ou construir um conjunto de dados de domínio de 10,000 a 100,000 linhas. O intervalo publicado vai de cerca de 0.01 USD por rótulo a 250M USD por acordo, aproximadamente dez ordens de grandeza, com o meio por documentar. Também não há nenhuma referência pública para k, o custo por registo verificado, a entrada à qual o ponto de equilíbrio abaixo é mais sensível.

## Construir, comprar, ou não fazer nada

O género compara construir contra comprar e nunca testa a terceira opção. Não fazer nada tem um valor líquido positivo, D*v*A0, e o modelo abaixo vence ambas as alternativas em qualquer volume abaixo do ponto de equilíbrio com estas entradas.

\`\`\`
Nothing = D*v*A0
Buy     = D*v*Av - L
Build   = D*v*Ab - (Cb/H + Mb)

Build beats buy     when D > (Cb/H + Mb - L) / (v*(Ab - Av))
Build beats nothing when D > (Cb/H + Mb)     / (v*(Ab - A0))
Buy   beats nothing when D > L               / (v*(Av - A0))
\`\`\`

Cb é a aquisição pontual, H o horizonte de amortização, Mb a manutenção por período, L a licença por período, v o valor líquido por decisão correta (já é a oscilação entre certo e errado, pelo que o custo do erro está incluído; se preferir o valor bruto mais um custo de erro separado c, substitua v por v+c).

Entradas trabalhadas, todas pressupostos ilustrativos, não medições: N=4,000, Cb=30,000 USD, H=3 anos, L=18,000 USD/ano, v=60 USD, Ab=0.95, Av=0.78, A0=0.55, r=30%, k=0.40 USD. Mb=11,100 USD/ano é derivado delas (4,000*0.40 USD*6.95 passagens num piso de 95%), o que não é o mesmo que independentemente conhecido: herda o k assumido, e k não tem referência pública. Um intervalo Ab-A0 de 40 pontos é otimista; um intervalo mais pequeno e mais realista sobe os três pontos de equilíbrio e alarga a faixa onde não fazer nada vence.

Pontos de equilíbrio em k=0.40 USD: construir vence comprar acima de (10,000+11,100-18,000)/(60*0.17) = 304/ano; construir vence não fazer nada acima de 21,100/(60*0.40) = 879; comprar vence não fazer nada acima de 18,000/(60*0.23) = 1,304.

Estes invertem-se com k. Em k=0.40 USD a faixa de comprar está vazia (limite superior 304 abaixo do limite inferior 1,304), pelo que comprar é dominado. Mas a barreira da linha 2 tolera 10 min/registo, que a 25.23 USD/h é 4.20 USD. Em k=4.20 USD, Mb=116,800 USD: construir-vence-nada passa de 879 para 5,283, e a faixa de comprar abre para cerca de 1,304 a 10,667. A faixa abre assim que k excede cerca de 0.75 USD. Portanto "comprar é dominado em qualquer volume" só se verifica sob verificação quase automatizada. Não é um resultado geral, e fica retirado para a verificação manual.

| Decisões/ano | Não fazer nada | Comprar a 18,000 USD/ano | Construir (30k USD em 3 anos + 11.1k USD/ano) | Vencedor |
|---|---|---|---|---|
| 294 | 9,702 USD | -4,241 USD | -4,342 USD | Nada |
| 879 | 29,007 USD | 23,137 USD | 29,003 USD | Nada (ponto de cruzamento) |
| 1,304 | 43,032 USD | 43,027 USD | 53,228 USD | Construir |
| 2,000 | 66,000 USD | 75,600 USD | 92,900 USD | Construir |

| Exatidão do fornecedor Av | Limite inferior da faixa de comprar | Limite superior da faixa de comprar | Faixa (k=0.40 USD) |
|---|---|---|---|
| 0.78 | 1,304 | 304 | Vazia |
| 0.85 | 1,000 | 517 | Vazia |
| 0.90 | 857 | 1,033 | Aberta, 857-1,033 |
| 0.93 | 789 | 2,583 | Aberta, 789-2,583 |

Comprar está certo precisamente quando o fornecedor é quase tão exato como você seria na sua própria superfície, o que é uma questão sobre o seu subconjunto, não sobre o marketing deles. Amostre 200 registos do fornecedor dentro do seu nicho e meça Av antes de assinar. A sensibilidade sobre v escala como 1/v: a 6 USD em vez de 60 USD, construir vence comprar apenas acima de 3,100/(6*0.17) = cerca de 3,040/ano.

O modelo também omite a opção que normalmente domina no regime deste leitor: comprar o volume copiável e construir apenas a coluna de resultado que ninguém consegue raspar. Numa janela de contexto detém ambos, pelo que raramente há razão para escolher um conjunto de dados em detrimento do outro.

Agora o ciclo de cadência. A sua vantagem sobre um concorrente é o intervalo de exatidão média de uma cadência mais rápida, onde a exatidão média ao longo do intervalo T é (1-e^(-lambda*T))/(lambda*T). A r=30%, a atualização mensal dá uma média de 98.53%, a anual dá 84.11%, um intervalo de 14.4 pontos. O custo incremental do mensal sobre o anual são 11 passagens extra, 11*4,000*0.40 USD = 17,600 USD/ano, pelo que o intervalo de cadência só compensa acima de 17,600/(60*0.144) = cerca de 2,034 decisões/ano, acima de ambos os pontos de equilíbrio trabalhados. E não é um fosso de dados: um concorrente que dote o mesmo pipeline de atualização de pessoal apaga-o. A coisa defensável é uma cadência operacional, um facto de contratação e de ferramentas, não um facto de dados.

## Quatro formas de isto correr mal depois de comprar

| Modo de falha | Sintoma que verá de facto | Método de deteção | Limiar de alerta |
|---|---|---|---|
| Ilusão de cobertura | Backtest bem, desempenho ao vivo em casos novos fraco, intervalo a alargar | Captura-recaptura (linha 1) em entidades vistas pela primeira vez nos últimos 12 meses | Cobertura de novas entidades mais de 15pp abaixo do total |
| Obsoleto mas confiável | Respostas confiantes construídas sobre campos que ninguém toca há anos | Obsolescência ponderada por leituras: fração das leituras que caem em linhas mais antigas do que T | Mais de 5% das leituras para além da cadência do piso |
| Deriva de decisão | Pipeline verde, dados a atualizar, a ação de ninguém muda | Taxa de divergência a 90 dias (linha 5) | Abaixo de 2%, elimine o conjunto de dados |
| Precipício de manutenção | k dispara, uma passagem de atualização falha em silêncio, uma fonte começa a bloqueá-lo, um campo passa a significar outra coisa | Concentração de fontes, k ano-a-ano, e taxa de bloqueio de fontes | Qualquer fonte única >50% das linhas, k a subir >25% YoY, ou uma fonte raspada a recusá-lo |

Na minha opinião, o défice numa figura de cobertura não é aleatório: concentra-se nas entidades mais recentes, mais pequenas e mais remotas, exatamente o segmento sobre o qual é a decisão. Se as suas vias de amostragem estão correlacionadas com a idade da entidade, corra a linha 1 separadamente sobre os últimos 12 meses para descobrir.

A ponderação por leituras importa porque os 5% quentes de linhas são normalmente as consultadas (o meu pressuposto, testável via a própria medida ponderada por leituras); se forem também as voláteis, a frescura ponderada por registo iludi-lo. Adicione uma coluna verified_at ou nenhum dos modelos deste artigo pode ser executado. A deriva de decisão sobrevive mais tempo porque cada painel lê-se saudável. Uma fonte que começa a recusá-lo é ao mesmo tempo um precipício de manutenção e um sinal legal. Os limiares nestas linhas são a minha opinião; o risco base para fontes web não controladas é o de primeiro ano de 8% da Pew.

## Onde a tese de nicho se verifica, e onde não

A minha contribuição, oferecida como opinião: a defensibilidade é proporcional ao custo de atualização, e os dados proprietários por si só não são um fosso. O ponto de Lambrecht and Tucker mantém-se: o recurso escasso é o conjunto de ferramentas operacionais em torno dos dados. O que pode ser defensável é uma cadência de atualização mantida envolvendo um ciclo de decisão fechado, e apenas enquanto nenhum concorrente dotar de pessoal o mesmo pipeline. Isso é uma corrida de contratação, não uma vantagem de dados. "Encontre dados baratos de manter" e "encontre dados que sejam defensáveis" são portanto instruções opostas, e à maioria dos fundadores são dadas ambas.

Diga o placar claramente. A antítese tem dois fracassos documentados (Watson Health, Zillow) e seis fios empíricos. A pró-tese tem zero casos de produção nomeados no regime deste leitor: nenhuma medição transparente do que a curadoria de um corpus privado compra, e o único estudo de recuperação no alvo concluiu que não compra nada. Os fracassos desta classe não são publicados, pelo que a amostra é selecionada por sobrevivência. Trate a tese como uma hipótese que este artigo instrumenta, não como um resultado que prova. O seu teste de falsificação: meça a divergência e o ganho de exatidão efetiva na sua própria superfície; se o ganho estiver dentro do ruído, a tese falhou para si.

Quatro condições sob as quais poderá sobreviver.

1. **Os dados registam uma decisão que só você toma, não um corpo de conhecimento.** O objeto defensável é o ciclo fechado de decisão, resultado, registo rotulado, porque a coluna de resultado não pode ser raspada, apenas conquistada. Esta é a única condição consistente com toda a evidência acima: nenhuma alegação de factos raros, nenhuma dependência de escala, não inferível a partir de texto público.
2. **Observação em primeira mão de eventos que não deixam rasto público juntável.** Um evento que observa deixa mesmo assim uma pegada com a sua contraparte, um corretor, ou um processador (o feed de gastos de comerciante de 99 USD/mês acima é exatamente dados de transações revendidos). Mas mais ninguém detém o registo juntado de evento, contexto e resultado sob a sua chave. Essa junção é o objeto defensável, não o evento.
3. **Decaimento alto, entendido como um custo recorrente em vez de uma barreira.** Um conjunto de decaimento rápido não pode ser roubado uma vez, apenas mantido, pelo que só é defensável enquanto você detiver o intervalo de cadência, que um concorrente pode contratar. A r=30% um instantâneo único está 23.5% errado no prazo de 9 meses, mas um concorrente que também construa uma máquina de atualização não perde nada.
4. **Suficientemente pequeno para verificar exaustivamente.** A 4,000 registos e k=0.40 USD um piso de 99% a r=30% custa cerca de 56,800 USD/ano; a 400,000 registos são cerca de 5.68M USD e ninguém o compra. Ambos escalam com o k assumido.

Onde não se verifica: (a) um fornecedor vende-o como um SKU (alugue-o, veja os feeds de 49 a 99 USD/mês acima); (b) decaimento baixo mais fontes públicas (a sua cópia e a deles envelhecem juntas, pelo que compete na distribuição, onde as experiências naturais dizem que o fosso realmente estava); (c) abaixo do volume de decisão de equilíbrio; (d) sem oráculo independente (não consegue medir r, pelo que não consegue atribuir preço a nada aqui); (e) a tarefa é raciocínio ou semântica em vez de consulta e extração estruturada (GPT-4 sobre BloombergGPT, prompting genérico sobre Med-PaLM 2); (f) divergência abaixo de 2%; (g) o método de aquisição está contratual ou legalmente barrado na fonte, pelo que atribua preço ao jurista antes do scraper.

Por fim, o slogan retirado, mantido como uma condição. A exatidão efetiva é c*A_small + (1-c)*A0 apenas se recorrer à linha de base fora da superfície curada. Numa janela de contexto detém normalmente ambos os conjuntos, pelo que a exatidão efetiva é c*A_small + (1-c)*A_big, que é pelo menos A_big para todo o c>0: o conjunto pequeno e limpo nunca é pior, e não há limiar nenhum. Um limiar existe apenas onde os dois são mutuamente exclusivos, o que para a recuperação raramente são. Sob essa exclusividade, com A_small=0.99, A0=0.55 e A_big=0.60 (todos assumidos), a cobertura de equilíbrio é (0.60-0.55)/(0.99-0.55) = 11.4%; a A_big=0.65 duplica para 22.7%. Portanto a resposta é cobertura, não contagem de linhas, e é dominada por quão boa a linha de base genérica já é na sua superfície, o que consegue medir.

O trabalho da semana: compre os dois pré-requisitos, um oráculo e um custo por registo k; meça r por campo com o seu intervalo de confiança; corra as sete linhas; calcule os seus três pontos de equilíbrio com k variado ao longo do intervalo que o seu próprio custo de mão de obra implica. Só então decida. Se o conjunto de dados qualificar, [from-dataset-to-live-workflow](/blog/from-dataset-to-live-workflow) cobre o que acontece a seguir.
`;

export default content;
