// ai-agent-audit-log-retention - pt
// Translated from the English body; structure identical to it. Legal scope
// statements and every "at least 6 months"/"out of scope"/"not legal advice" are
// load-bearing and must not be strengthened or softened. Fenced code stays fenced.
const content = `--- CORRECTED TRANSLATION ---

Um artigo complementar publica o esquema de campos ao nível de execução e ao nível de passo que este avalia em custos: os nomes dos campos, tipos e classes de cardinalidade referenciados nas tabelas abaixo estão definidos lá. Este texto responde às três perguntas que o esquema deixa em aberto. Quantos bytes custa efetivamente o rasto? Durante quanto tempo tem cada campo de ser conservado? E aplica-se algo disto legalmente a si, o que para a maioria dos leitores não acontece.

A implementação de referência citada ao longo do texto é a própria plataforma deste blog: nomes de coluna reais, migrações reais, bugs reais.

## A aritmética, para que o escalonamento seja derivado e não afirmado

Tudo o que se segue é um **modelo**, não uma medição. Os dados de entrada são indicados para que possa voltar a executá-lo com os seus próprios números. Os tamanhos de linha são analíticos, derivados dos tipos de coluna do DDL mais a sobrecarga documentada do Postgres; as tabelas reais correm cerca de 10-25% maiores quando se incluem fillfactor, espaço livre e bloat, por isso leia cada figura derivada abaixo como "+10 a 25% em produção" (a figura fixa de captura total a sete anos, 1.68 TB no modelo, é cerca de 2.1 TB no topo desse intervalo).

\`\`\`
Volume:  10,000 runs/day, 6 steps/run
Rows:    27/run = 1 run header + 6 iterations
                + 6 tool calls + 14 messages
Payload: 1500-token system prompt, 200-token user msg,
         250-token completions, 150 B tool arguments,
         4 KB mean tool result, 4 bytes/token
PG overhead/row: 23 B heap tuple header, MAXALIGNed to 24
                 + 4 B line pointer
                 + 8 B assumed null bitmap (1 bit/column,
                   present only when the row has NULLs;
                   8 B covers up to 64 columns) = 36 B
                 + ~16 B per btree index entry
\`\`\`

A figura só-de-metadados a partir da qual o resto do modelo escala é 9.05 KB/run, derivada assim:

\`\`\`
Worked row sizes (metadata only):
Run header (1 row):
  ~300 B column data (uuids, 3 timestamptz, 5 int4 token
   counters, 3 bytea(32) hashes, build_sha, enums, numerics)
  + 36 B tuple overhead + ~48 B (3 btree entries) = ~384 B
Step row (avg over 26):
  ~180 B column data + 36 B overhead + ~80 B index entries
  = ~335 B
Per run: 384 + 26 x 335 = ~9.05 KB
\`\`\`

| Nível de captura | Bytes/run | MB/dia @10k runs | GB/ano | GB ao longo de 7 anos | GB/ano comprimido |
|---|---|---|---|---|---|
| Só metadados | 9.05 KB | 88.38 | 31.50 | 220.51 | 31.50 (não comprimido no PG; arquiva bem) |
| Metadados + digests (~832 B/run) | 9.86 KB | 96.29 | 34.33 | 240.31 | 34.33 |
| Captura total | 70.43 KB | 687.78 | 245.16 | 1,716 (1.68 TB) | 92.6-117 |

A captura total é 7.8x a só-de-metadados. A compressão assume 2.5-3.5x em payloads acima do limiar TOAST do Postgres de ~2 kB (2048 bytes), um intervalo publicado típico e não uma medição sobre este corpus, por isso a figura de captura total comprimida abrange de 92.6 a 117 GB/ano consoante onde nesse intervalo cair.

Um dado de entrada domina o resultado:

| Resultado médio de ferramenta | KB/run (captura total) | GB/ano @10k runs/dia | Forma de agente que aqui vive |
|---|---|---|---|
| 1 KB | 34.43 | 119.84 | Classificação, encaminhamento, consultas curtas de API |
| 4 KB | 70.43 | 245.16 | Uso misto de ferramentas, o modelo acima |
| 8 KB | 118.43 | 412.24 | Redação de documentos, CRUD multi-registo |
| 20 KB | 262.43 | 913.50 | Pesquisa, leitura de ficheiros, agentes intensivos em SQL |

Prompts e completions são 20% do payload com um resultado médio de ferramenta de 4 KB (12.8 KB de 61.38 KB) e caem para cerca de 5% aos 20 KB (12.8 KB de 253.38 KB), por isso os resultados de ferramenta são onde o escalonamento compensa. **Se escalonar uma coisa, escalone os resultados de ferramenta.**

Agora a inversão que motiva toda a secção. 245 GB/ano são cerca de **$235/ano** de armazenamento em bloco gp3, **$68/ano** em S3 Standard, **$12/ano** em Glacier Instant Retrieval; só-de-metadados são cerca de $30/ano. (Figuras de ordem de grandeza da lista us-east-1, excluindo encargos de pedido e de recuperação; os níveis frios assumem volume de leitura quase nulo.) **Ninguém está a cortar o seu rasto para poupar $200.**

O que a figura em dólares esconde é o custo real: **98.55 milhões de linhas/ano** (689.85 milhões ao longo de sete anos) de superfície de apagamento, manutenção de índices e tempo de restauro, além do facto de que cada byte retido de prompt e resultado de ferramenta é responsabilidade. Desenhe o escalonamento em torno do raio de impacto e da contagem de linhas.

A 1M runs/dia o teto operacional morde bem antes da fatura de armazenamento: ~54M inserções de índice/dia, 9.86 mil milhões de linhas/ano, 23.94 TB/ano de captura total, e cerca de 140 horas para restaurar logicamente um ano a 50 MB/s. O nível esqueleto é o que mantém um rasto *restaurável*, não apenas comportável.

Uma poupança gratuita, encontrada ao ler o esquema em vez do código: **o resultado da ferramenta é frequentemente persistido duas vezes**, uma como conteúdo da linha da chamada de ferramenta e outra como conteúdo da linha da mensagem de papel-ferramenta correspondente. Guarde o payload uma vez e faça a linha da mensagem transportar o mesmo \`payload_ref\`, e o payload cai de 61.38 KB para 37.38 KB por run, de 245.16 GB/ano para 161.61 GB/ano. Qualquer rasto com uma tabela de chamadas de ferramenta e uma tabela de mensagens tem esta forma. (A observação ao nível do esquema é sólida; a taxa exata de sobreposição em produção não foi medida.)

## Níveis de retenção, cada um justificado pela decisão que suporta

| Nível | Conteúdos | Janela | GB/ano | Pergunta que responde | Amostrado ou degradado? |
|---|---|---|---|---|---|
| 0 Esqueleto | Cabeçalho da run sem todo o texto; metadados de passo (\`step_seq\`, \`tool_name\`, \`branch_taken\`, status, \`stop_reason\`, durações, contagens de tokens, \`content_length\`, todos os digests) | Janela total de obrigação (7 anos modelados) | 31.50 | Esta run aconteceu, quando, quem a acionou, o que fez, por que caminho ramificou, quanto custou | **Nunca** |
| 1 Digests e códigos | \`args_digest\`, \`result_digest\`, \`error_code\`, \`redaction_applied\`, \`model_snapshot\` | 12-24 meses | 34.33 | Provar ou refutar que o agente viu um documento produzido; recalcular o custo de uma run contestada aos preços em vigor | **Nunca** |
| 2 Argumentos e resultados de ferramenta | \`content\`, \`payload_ref\` para passos de ferramenta | 30-90 dias quente, depois amostrado | ~80% dos bytes de payload | Depurar uma regressão ativa; responder à reclamação de um cliente | Sim, após a janela quente |
| 3 Prompts e completions | Conteúdo das mensagens | 30 dias, **mais 100% das runs falhadas ou que dispararam guardrail em qualquer idade** | ver abaixo | Reconstruir o raciocínio de uma decisão contestada | Só de forma não-uniforme |
| 4 Modelos de prompt | System prompts, texto de prompt por versão | Para sempre (kilobytes) | ~0 | Que versão de prompt correu | Nunca num relógio por-run |

O Nível 0 ao longo de sete anos são 220.51 GB, cerca de **$10.60/ano** em Glacier Instant Retrieval (220.51 GB x $0.004/GB-mês x 12). Isso responde à maioria das perguntas de auditor conservando zero bytes de dados pessoais.

A regra de amostragem do Nível 3 é a que vale a pena discutir, e o botão só alguma vez toca nos níveis 2 e 3 (invariante 1: os registos de auditoria nunca são amostrados). A uma taxa de falha assumida de 8%, manter todas as falhas mais 5% dos sucessos retém 12.6% das runs (0.08 + 0.92 x 0.05 = 0.126). Aplicado apenas aos níveis de payload (captura total menos o esqueleto de 31.50 e os níveis de digest de 2.83, ou seja 210.83 GB/ano), isso mantém 26.56 GB/ano de payload; com os níveis 0 e 1 mantidos a 100%, o detalhe completo residente cai de 245.16 para cerca de **60.9 GB/ano** (31.50 + 2.83 + 26.56), mantendo cada run sobre a qual alguém irá efetivamente perguntar. A amostragem uniforme otimiza para as runs que ninguém investiga.

Plano combinado, por nível:

\`\`\`
30 days full capture:   20.15 GB gp3           $19.34
365 days digests:       34.33 GB S3 Standard    $9.47
7 years skeleton:      220.51 GB Glacier IR    $10.58
resident total:        274.99 GB             ~ $39/year
\`\`\`

Isso são 274.99 GB residentes contra 1.68 TB para captura total fixa mantida sete anos, uma redução de 6.2x, cerca de $39/ano contra $1,647/ano de gp3 fixo. A poupança que importa não é o dinheiro: **apenas 30 dias de payload de dados pessoais estão alguma vez no âmbito de um pedido de eliminação, em vez de sete anos.**

Quente-mais-frio é a forma que os reguladores já codificam. O requisito 10.5.1 do PCI DSS 4.0 pede 12 meses com os 3 mais recentes imediatamente disponíveis; a SEC Rule 17a-4 seis anos com os primeiros dois facilmente acessíveis. (Ambos confirmáveis tal como enunciados.)

O anti-padrão a nomear: a amplamente divulgada **escada de degradação progressiva** que descarta o conteúdo de prompt e completion após o primeiro ano e mantém apenas metadados a partir do terceiro ano. Degrada o conteúdo precisamente ao longo da janela em que um auditor dele precisa, e permite a uma empresa alegar "sete anos de registos de auditoria" enquanto não conserva nada que explique uma única decisão.

## O que efetivamente deve, e porque é provavelmente nada

| Instrumento | Artigo / controlo | Vincula quem | O que efetivamente exige | Retenção | Especifica campos? |
|---|---|---|---|---|---|
| EU AI Act | [Art. 12(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-12) | **Sistemas** de alto risco (requisito de conceção) | Os sistemas "devem permitir tecnicamente o registo automático de eventos (logs) ao longo da vida do sistema" | n/a | **Não** |
| EU AI Act | Art. 12(2)(a)-(c) | como acima | Apenas as *finalidades*: risco ao abrigo do Art. 79(1) ou modificação substancial; monitorização pós-comercialização ao abrigo do Art. 72; monitorização de funcionamento ao abrigo do Art. 26(5) | n/a | **Não** |
| EU AI Act | Art. 12(3)(a)-(d) | **Apenas o ponto 1(a) do Anexo III** (ID biométrica à distância) | Período de cada uso; base de dados de referência consultada; dados de entrada cuja pesquisa levou a uma correspondência; identificação das pessoas que verificam os resultados | n/a | **Sim, o único lugar** |
| EU AI Act | [Art. 19(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-19) | **Fornecedores** | Conservar os logs do Art. 12(1) "na medida em que esses logs estejam sob o seu controlo" | **pelo menos 6 meses** | Não |
| EU AI Act | [Art. 26(6)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-26) | **Responsáveis pela implantação** | O mesmo dever, o mesmo limitador, um relógio separado | **pelo menos 6 meses** | Não |
| EU AI Act | [Art. 18(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-18) | Fornecedores | Documentação técnica, documentação do QMS, decisões de organismo notificado, declaração UE de conformidade | **10 anos** após colocação no mercado ou entrada em serviço | n/a |
| EU AI Act | [Art. 86](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-86) | Responsáveis pela implantação | "Explicações claras e significativas do papel do sistema de IA no procedimento de tomada de decisão e dos principais elementos da decisão tomada" | n/a | **Não** |
| ISO/IEC 42001 | o controlo de registo de eventos do Anexo A | Voluntário | Registos de eventos mais registos de monitorização que demonstrem que o registo está operacional | nenhuma prescrita | **Não** |
| NIST AI RMF | MEASURE 2.8, MANAGE 2.4, MANAGE 4.3 | Voluntário | Instrumentar e manter históricos e registos de auditoria; preservar materiais para revisão forense, regulatória e legal; manter bases de dados de incidentes e de alterações de sistema | nenhuma prescrita | **Não** |
| SOC 2 | 2017 TSC (pontos de foco revistos em 2022) | Contratual | Prova genérica de ambiente de controlo aplicada ao seu agente | baseada em critérios, sem período | **Não** |
| HIPAA | [45 CFR 164.316(b)(2)(i)](https://www.govinfo.gov/content/pkg/CFR-2023-title45-vol2/xml/CFR-2023-title45-vol2-sec164-316.xml) | Entidades abrangidas | Conservar a documentação exigida | **6 anos** | Não |

Três distinções que a maioria dos resumos entende mal.

**O Art. 12(1) é um requisito de conceção sobre o sistema. O Art. 19(1) impõe um piso de seis meses ao fornecedor. O Art. 26(6) impõe um piso de seis meses separado e paralelo ao responsável pela implantação.** Seis meses são devidos duas vezes por duas partes diferentes, não um relógio único partilhado, ambas com o mesmo limitador, "na medida em que esses logs estejam sob o seu controlo".

**Seis meses é o piso dos LOGS; dez anos é o piso da DOCUMENTAÇÃO.** O Art. 18(1) e o Art. 19(1) são dois regimes distintos, rotineiramente confundidos.

**A obrigação que efetivamente força a explicabilidade por-decisão é o Art. 86, não o Art. 12.** Uma pessoa afetada sujeita a uma decisão tomada pelo responsável pela implantação com base no resultado de um sistema de alto risco do Anexo III (exceto o ponto 2), que produza efeitos jurídicos ou a afete de forma similarmente significativa de um modo que considere ter um impacto adverso na sua saúde, segurança ou direitos fundamentais, tem direito a explicações do papel do sistema de IA e dos principais elementos da decisão. O Art. 86(3) torna-o subsidiário de outro direito da União.

**E agora a resposta honesta para a maioria dos leitores: totalmente fora do âmbito do Art. 12/19/26(6).** Alto risco significa Art. 6(1) (componente de segurança de um produto do Anexo I que exige avaliação de conformidade por terceiros) ou Art. 6(2) (as oito áreas [do Anexo III](https://ai-act-service-desk.ec.europa.eu/en/ai-act/annex-3)). Um assistente de programação, um agente interno de investigação ou de suporte, um agente de redação de documentos não está em nenhuma delas.

O "a não ser que" que apanha as pessoas é o **ponto 4** do Anexo III (recrutamento e seleção, anúncios de emprego direcionados, filtragem de candidaturas, avaliação de candidatos, decisões sobre condições de trabalho, promoção, cessação, atribuição de tarefas com base em comportamento ou traços, monitorização de desempenho) e o **ponto 5** (uma lista parcial dos seus quatro subpontos, os dois que mais frequentemente apanham construtores: (b) avaliação de solvência e pontuação de crédito excluindo a deteção de fraude, e (c) avaliação de risco e fixação de preços em seguros de vida e de saúde; os outros dois, (a) avaliação por autoridade pública da elegibilidade para prestações e serviços essenciais de assistência pública incluindo cuidados de saúde, e (d) triagem e envio de chamadas de emergência, apanham agentes de govtech e adjacentes a prestações sociais).

Mesmo um sistema do Anexo III pode escapar via a derrogação do [Art. 6(3)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-6) (tarefa procedimental restrita; melhoria de uma atividade humana previamente concluída; deteção de padrões sem substituir a avaliação humana anterior; uma tarefa preparatória), mas **nunca se efetuar definição de perfis de pessoas singulares**. E o Art. 6(4) faz a válvula de escape gerar o seu próprio papelório: documentar a avaliação antes da colocação no mercado, mais uma obrigação de registo ao abrigo do Art. 49(2).

Duas armadilhas para construtores. Construir um agente puramente para uso interno não faz de si um mero responsável pela implantação: o [Art. 3(11)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-3) define entrada em serviço como fornecimento para primeira utilização "ou para uso próprio", pelo que um sistema interno de alto risco pode dever simultaneamente o Art. 19, o Art. 26(6) e o Art. 18. O [Art. 25(1)(c)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-25) faz o mesmo a quem modificar a finalidade prevista de um modelo de finalidade geral de modo que o sistema se torne de alto risco.

A exposição a coimas pelos deveres de registo é o nível intermédio, não a manchete: o [Art. 99(4)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-99) é até EUR 15,000,000 ou 3% do volume de negócios anual mundial, consoante o que for mais elevado. Cobre os Arts. 16, 22, 23, 24, 26, 31, 33, 34 e 50; o Art. 19 não está ele próprio listado, pelo que uma violação de conservação de logs por um fornecedor é alcançada via Art. 16(e), que importa o dever do Art. 19, enquanto a do responsável pela implantação é o Art. 26 diretamente. O nível de 35 milhões / 7% está reservado às práticas proibidas do Art. 5.

**O calendário mudou.** O Digital Omnibus on AI adia as datas de aplicação de alto risco para **2 de dezembro de 2027** para os sistemas de alto risco autónomos (Anexo III) e **2 de agosto de 2028** para a IA de alto risco incorporada em produtos regulados, segundo o [Conselho da UE](https://www.eeas.europa.eu/delegations/chile/artificial-intelligence-council-gives-final-green-light-simplify-and-streamline-rules_en). Estado processual no final de julho de 2026: aprovação em plenário do PE a 16 de junho de 2026, adoção pelo Conselho a 29 de junho de 2026, assinatura a 8 de julho de 2026, a aguardar publicação no Jornal Oficial ([EP Legislative Train](https://www.europarl.europa.eu/legislative-train/package-digital-package/file-digital-omnibus-on-ai)). Qualquer artigo que ainda cite 2 de agosto de 2026 para alto risco está desatualizado. O Omnibus não altera os Artigos 12, 19 ou 26(6) no texto acordado, tal como reportado por todas as análises publicadas sobre ele; o piso de seis meses permanece inalterado. Confirme contra o texto do JO uma vez publicado.

Os sistemas legados podem escapar por completo: o [Art. 111(2)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-111) aplica o Regulamento aos sistemas de alto risco colocados no mercado antes da transição apenas se forem posteriormente sujeitos a alterações significativas na sua conceção; os responsáveis pela implantação de autoridade pública têm até 2 de agosto de 2030.

Dois deveres vinculam independentemente do nível de risco: o [Art. 4](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-4) (literacia em IA, aplicável desde 2 de fevereiro de 2025, sobre fornecedores e responsáveis pela implantação) e o [Art. 50(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-50) (os fornecedores devem conceber os sistemas de forma que as pessoas singulares sejam informadas de que estão a interagir com uma IA, salvo se for óbvio), que se aplica a partir de 2 de agosto de 2026, dez dias após a publicação deste texto. A marcação de conteúdos do Art. 50(2) tem um período de graça até 2 de dezembro de 2026 para sistemas já no mercado. O Omnibus suaviza o Art. 4 de garantir um nível suficiente de literacia para apoiar o seu desenvolvimento entre o pessoal; a data de 2 de fevereiro de 2025 permanece inalterada, e até à publicação no JO é a redação original que ainda vincula.

E as normas que especificariam *como* satisfazer o Art. 12 ainda não existem: o [CEN-CENELEC JTC 21](https://www.cencenelec.eu/news-events/news/2025/brief-news/2025-10-23-ai-standardization/) continua a desenvolver as normas do Capítulo III Secção 2, com medidas de aceleração adotadas em outubro de 2025 a apontar para disponibilidade por volta do Q4 de 2026. Até lá é uma obrigação legal sem especificação técnica por trás.

Os enquadramentos voluntários também não lhe dão nenhum esquema. A [ISO/IEC 42001](https://www.iso.org/standard/81230.html) é voluntária (a ISO não certifica organizações; organismos acreditados fazem-no), e o seu controlo do Anexo A A.6.2.8, "AI system recording of event logs", não prescreve nem uma duração de retenção nem uma lista de campos. O [NIST AI RMF](https://www.nist.gov/itl/ai-risk-management-framework) é explicitamente voluntário e comportamental. A SOC 2 usa os Trust Services Criteria de 2017 com pontos de foco revistos em 2022, e não foram emitidos critérios específicos de IA, pelo que um auditor testa prova genérica de ambiente de controlo aplicada ao seu agente.

O Colorado merece uma linha se tocar em contratações ou decisões consequentes. A SB 26-189, segundo a [página do projeto de lei](https://leg.colorado.gov/bills/sb26-189), foi assinada a 14 de maio de 2026, com efeitos a 1 de janeiro de 2027; revoga e volta a promulgar o Colorado AI Act de 2024. O âmbito é a tecnologia de tomada de decisão automatizada usada em decisões consequentes (educação, emprego, habitação, financeiro/crédito, seguros, cuidados de saúde, serviços governamentais essenciais). Os developers e os responsáveis pela implantação devem conservar registos de conformidade durante pelo menos três anos, para os responsáveis pela implantação a contar da data da decisão consequente.

**A conclusão anti-teatro.** Se estiver fora do âmbito, construa o rasto para as perguntas que efetivamente lhe serão feitas: uma disputa de cliente, uma revisão de incidente, uma disputa de faturação, uma investigação de segurança. Dimensione o nível esqueleto para a obrigação futura plausível mais longa, porque custa 31.50 GB/ano. Depois deixe que seis meses sejam um piso que por acaso ultrapassa, e não um programa de trabalho. Isto não é aconselhamento jurídico, e nenhum dos regimes de retenção acima deve ser achatado num único número que se lhe aplique.

## Dados pessoais: o rasto que conserva durante anos e o pedido de eliminação que recebe amanhã

**Uma referência pseudonimizada de ator não tira o rasto do âmbito do GDPR.** O Recital 26 trata os dados que poderiam ser atribuídos a uma pessoa recorrendo a informação adicional como dados pessoais. Guarde um token que só resolve para a identidade através de uma tabela de mapeamento controlada separadamente, e não alegue que o rasto é anónimo.

**O piso de seis meses tem um teto na mesma frase.** O Art. 19(1) e o Art. 26(6) terminam ambos "salvo disposição em contrário no direito aplicável da União ou nacional, em especial no direito da União relativo à proteção de dados pessoais". Manter tudo para sempre não é a resposta conforme, é uma violação separada.

**A resposta de conceção é o pivô do digest:** o nível longo guarda hashes, códigos, contagens e classificações, nenhum payload. É isso que torna um esqueleto de sete anos defensável em vez de uma responsabilidade de sete anos.

**Coloque \`tenant_id\` e \`organization_id\` em cada linha-filha, não apenas na linha-pai.** O apagamento corre como DELETEs por-tabela com âmbito de org; as linhas que transportam apenas um \`execution_id\` precisam de um join, e qualquer linha cujo pai já desapareceu sobrevive como um órfão inalcançável que ainda contém dados pessoais. O \`WorkspaceDataPurger\` desta plataforma emite um DELETE com âmbito de org contra \`agent_execution_tool_calls\` chaveado por \`organization_id\` (e equivalentes), o que só funciona porque o \`V210\` adicionou a coluna às cinco tabelas de runtime do agente e fez backfill de quatro delas (as linhas de \`agent_tasks\` ficam NULL por conceção, um âmbito pessoal).

**Divida o rasto numa camada operacional apagável e numa camada de livro-razão não apagável**, e deixe a eliminação levar apenas a primeira. A implementação de referência elimina 31 tabelas declaradas com âmbito de org (\`PURGED_ORG_SCOPED_TABLES\`) mais as tabelas-filhas de execução do agente que atinge diretamente (messages, tool calls, iterations), sem nunca tocar em \`auth.credit_ledger\`, \`auth.usage_cycle\`, \`auth.credit_reconciliation_log\` ou \`auth.organization_audit_event\`, e mantém a linha da organização como uma tombstone para que as referências do livro-razão permaneçam válidas. Um teste de cobertura afirma tanto o âmbito de org de cada instrução como a não-eliminação das tabelas conservadas. O limite honesto: o livro-razão sobrevivente ainda prova que as runs de um titular existiram e quanto custaram, por isso isto só satisfaz a minimização se o livro-razão não transportar payload nem identificadores que não sejam pseudónimos.

**Apagamento que não apaga.** Quando payloads grandes são descarregados para armazenamento de objetos e a linha guarda um ponteiro, eliminar a linha **orfaniza o blob**. Os dados pessoais sobrevivem ao pedido de eliminação, sem referência e portanto invisíveis a qualquer auditoria posterior do que detém. O purger acima documenta exatamente este órfão no seu próprio javadoc: elimina as linhas de \`storage.storage\` mas não os objetos S3/MinIO subjacentes. Correção: faça do armazém de payload o alvo da eliminação e da linha o ponteiro, e reconcilie os órfãos de forma agendada.

**Decida se a redação acontece na escrita ou na leitura, e registe qual.** Um redator que só corre ao apresentar linhas a um revisor deixa credenciais em bruto pousadas nos argumentos de ferramenta armazenados (o estado atual aqui: o \`ToolCallRedactor\` é um filtro do caminho de leitura). Um redator em tempo de escrita destrói prova de que pode precisar. Seja qual for a escolha, \`redaction_applied\` é o que torna a escolha auditável.

**O padrão por resolver que vale a pena implementar:** marcar com tombstone o conteúdo apagado enquanto se retém o seu digest, para que a cadeia à prova de adulteração sobreviva a um apagamento e um leitor posterior ainda consiga saber que algo esteve lá, quão grande era, e que foi removido ao abrigo de um pedido de direitos em vez de perdido.

## Duas falhas a evitar por conceção, e o que fazer quanto ao OpenTelemetry

**Retenção que não consegue prolongar retroativamente.** No dia em que descobre que a janela é mais longa do que o seu cron de purga, os dados desapareceram. Uma equipa aqui, ao subir um registo de auditoria de ciclo de vida de 30 para 365 dias, atingiu um backlog de 12x na primeira purga a seguir, e essa foi a direção *sortuda*. Defina o nível esqueleto para a obrigação plausível mais longa no primeiro dia; a 31.50 GB/ano é o seguro mais barato do sistema. (Relacionado: um comentário de retenção documentado a dizer "30d default" enquanto o \`@Value\` do serviço tinha por defeito 365 é como a retenção documentada e a configurada divergem em silêncio.)

**Erros no caminho de consulta que tornam um rasto inutilizável em vez de errado.** As linhas de detalhe não são o caminho de consulta: pré-agregue as dimensões de baixa cardinalidade em rollups chaveados por \`(tenant, date, provider, model)\` e \`(tenant, tool_name)\`. O Postgres não indexa automaticamente as chaves estrangeiras: uma tabela de chamadas de ferramenta aqui com 18k linhas e 39 MB cujo único índice era a sua chave primária fazia full-scan em cada leitura agregada até o \`V341\` adicionar um btree \`CONCURRENTLY\` em \`execution_id\`. E leituras não paginadas de linhas de payload à escala de MB são uma forma de OOM: limite a página (100 é um máximo rígido razoável) e devolva \`total\` / \`shown\` / \`truncated\` para que um leitor seja informado quando linhas mais antigas foram descartadas, em vez de ver silenciosamente um rasto parcial.

A regra de cardinalidade que decorre das tabelas de esquema: os **campos de baixa cardinalidade** (\`status\`, \`stop_reason\`, \`provider\`, \`model\`, \`tool_name\`, \`trigger_source\`, \`branch_taken\`) são aquilo por que cada pergunta agrupa e pertencem aos rollups; os **campos de alta cardinalidade** (\`run_id\`, \`tool_call_id\`, digests) são chaves de join que precisam de índices btree e nunca devem entrar numa chave de rollup.

### O veredicto sobre o OpenTelemetry

**Não fixe ainda um esquema de auditoria a ele.** Zero atributos \`gen_ai.*\` são Stable (99 Development, 0 Stable no registo ativo), o [repositório GenAI semconv](https://github.com/open-telemetry/semantic-conventions-genai) não tem releases nem tags, e as convenções saíram do repositório principal semantic-conventions, que agora renderiza cada atributo \`gen_ai.*\` na [página do registo legado](https://opentelemetry.io/docs/specs/semconv/registry/attributes/gen-ai/) como "Deprecated" como um artefacto da mudança. Um sinal falso em ambas as direções.

Os renomeamentos já quebraram esquemas uma vez:

\`\`\`
gen_ai.system              -> gen_ai.provider.name (now absent)
gen_ai.usage.prompt_tokens -> gen_ai.usage.input_tokens
gen_ai.usage.completion_tokens -> gen_ai.usage.output_tokens
gen_ai.prompt / gen_ai.completion
   -> gen_ai.input.messages / gen_ai.output.messages
\`\`\`

O OTel **não tem atributo** para uma aprovação humana, uma identidade de ator ou principal, uma decisão de política ou guardrail, uma classe de retenção, ou custo monetário (apenas contagens de tokens, sem \`gen_ai.cost.*\`). Esses são precisamente os campos que suportam a auditoria, e é por isso que o rasto é a sua tabela e não o seu backend de tracing.

Dois campos vale a pena adotar textualmente porque são baratos e respondem a perguntas reais de auditoria: **\`gen_ai.prompt.name\` mais \`gen_ai.prompt.version\`** provam que versão de prompt correu sem armazenar o seu texto, e **\`gen_ai.conversation.compacted\`** responde se o modelo viu o histórico completo ou um resumo. Note também que \`gen_ai.provider.name\` é um discriminador de formato de telemetria que pode apontar para um proxy, não prova de qual fornecedor processou os dados, e que \`gen_ai.conversation.id\` não deve ser fabricado a partir de um UUID, trace id ou hash de conteúdo, pelo que está legitimamente ausente em muitos rastos.

Os limites de span truncam um rasto em silêncio: o \`OTEL_SPAN_ATTRIBUTE_COUNT_LIMIT\` tem por defeito 128. Atributos indexados por-mensagem achatados (a forma OpenInference \`llm.input_messages.<i>.message.*\`) podem exceder isso numa conversa longa, enquanto um único \`gen_ai.input.messages\` estruturado custa um atributo. Isso é aritmética derivada, não um incidente documentado. Os valores de atributo estruturados também ainda não são universalmente suportados em spans, pelo que o mesmo campo lógico é uma string JSON num backend e um objeto noutro.

A própria recomendação de produção da especificação é a arquitetura aqui defendida: armazenar conteúdo em armazenamento externo com controlos de acesso separados e registar referências nos spans, e invocar o hook de upload "regardless of the span sampling decision". **Amostre os traces, nunca amostre a prova.** Isso é \`payload_ref\` mais digest com outro nome.

Regra de fecho: **emita OTel para o dashboard, seja dono de uma tabela para o rasto, junte-os por \`run_id\`, e mantenha os dois relógios de retenção separados.**
`;

export default content;
