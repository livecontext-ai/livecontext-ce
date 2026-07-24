// ai-agent-audit-trail - pt
// Translated from the English body; structure identical to it. Legal scope
// statements and every "at least 6 months"/"out of scope"/"not legal advice" are
// load-bearing and must not be strengthened or softened. Fenced code stays fenced.
const content = `Um registo de auditoria (audit trail) não é um log mais longo. É um artefacto diferente, com um leitor diferente, um contrato de escrita diferente e um relógio diferente. Este texto publica um esquema copiável ao nível da execução (run) e ao nível do passo (step) em que cada campo transporta quatro coisas ao mesmo tempo: o seu tipo de dados, a sua classe de cardinalidade, se pode conter dados pessoais e a razão pela qual existe. Um artigo companheiro faz a aritmética de armazenamento que transforma a estratificação da retenção numa decisão derivada, mapeia as obrigações legais reais e trata do pedido de eliminação que colide com um registo mantido durante anos.

A implementação de referência citada ao longo do texto é a própria plataforma deste blogue. Nomes de colunas reais, migrações reais, bugs reais.

## O leitor para quem está a escrever não é você, nem é agora

Um dashboard é lido pelo seu autor, ao fim de minutos, com o incidente ainda na memória de trabalho. Um registo de auditoria é lido por um terceiro indiferente ou hostil, meses mais tarde, que não pode fazer uma pergunta de seguimento. Essa diferença gera todas as decisões abaixo.

Seguem-se dois invariantes, e quase ninguém os põe por escrito:

1. **Os registos de auditoria nunca são amostrados.**
2. **Os campos de conteúdo nunca são degradados dentro da sua janela de retenção.**

O resto é critério de desenho, e o custo de armazenamento desse critério é aritmética.

Diga a coisa incómoda logo à partida: **nenhum instrumento especifica este esquema.** Fora do Art. 12(3) do EU AI Act, que se aplica a exatamente um sub-ponto do Annex III (ponto 1(a), identificação biométrica remota, e não à verificação biométrica), nada do que foi analisado aqui (o AI Act, ISO/IEC 42001, NIST AI RMF, SOC 2) especifica um esquema de log, tipos de campos, limites de cardinalidade ou uma estratégia de amostragem. O esquema abaixo é critério de engenharia orientado para satisfazer as *finalidades* que a lei nomeia no Art. 12(2)(a) a (c) e o direito à explicabilidade no Art. 86. Não é um artefacto de conformidade e não o vou vender como tal.

Cada campo de um registo utilizável transporta quatro coisas ao mesmo tempo: o seu tipo de dados e nulabilidade, a pergunta ou obrigação que o força a existir, a sua classe de cardinalidade e a sua classe de retenção, incluindo se pode ser amostrado ou degradado. Nenhuma fonte publicada preenche os quatro cantos. [As convenções GenAI da OpenTelemetry](https://github.com/open-telemetry/semantic-conventions-genai) têm tipos mas não têm obrigações nem conteúdo por defeito; [o registo de auditoria mínimo viável da ARMO](https://www.armosec.io/blog/minimum-viable-audit-trail/) tem obrigações e nomes de campos mas não tem tipos; o conjunto do AI Act tem a lei e admite que não especifica campos.

Duas notas anti-sobreposição. O registo é **linear nos passos, não quadrático**: você paga ao modelo para reenviar o contexto acumulado a cada turno mas armazena cada mensagem uma só vez, portanto uma execução de seis passos são ~27 linhas independentemente do crescimento do contexto (o lado quadrático pertence ao artigo do modelo de custos). E \`stop_reason\` e \`terminal_category\` aparecem aqui puramente como campos a registar; a taxonomia e o comportamento de limite (cap) pertencem ao artigo sobre a imposição de orçamentos.

## Um dashboard de observabilidade não é um registo de auditoria

A confusão divide-se de forma limpa pelo título do artigo: os textos com título de observabilidade vendem os traces como o registo de auditoria; os textos com título de auditoria raramente mencionam que o esquema padrão não regista qualquer conteúdo por defeito.

Esse defeito é a conclusão de destaque. As convenções semânticas GenAI tornam os prompts, as completions, as instruções de sistema, os argumentos de ferramentas e os resultados de ferramentas todos com nível de requisito \`Opt-In\`, e a posição da especificação é que as instrumentações "SHOULD NOT capture them by default", sendo a opção 1 "[Default] Don't record instructions, inputs, or outputs." Portanto "temos tracing OTel, logo temos um registo de auditoria" é falso à partida: o que você tem é o modelo, as contagens de tokens, a latência e o motivo de conclusão (finish reason), nada do material que reconstrói uma decisão.

Ativá-lo é mais difícil do que parece. No [opentelemetry-python-contrib](https://github.com/open-telemetry/opentelemetry-python-contrib/blob/main/util/opentelemetry-util-genai/src/opentelemetry/util/genai/utils.py), o interruptor de captura não é um booleano:

\`\`\`
OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT
  = NO_CONTENT | SPAN_ONLY | EVENT_ONLY | SPAN_AND_EVENT
  unset            -> NO_CONTENT
  invalid value    -> warning, then NO_CONTENT

# second gate, barely documented:
OTEL_SEMCONV_STABILITY_OPT_IN must select the GenAI
experimental mode, or get_content_capturing_mode() raises.
\`\`\`

Definir apenas a variável de captura não é suficiente. (Verificado apenas para os pacotes Python contrib; outros SDKs de linguagem podem diferir no nome da flag, nos valores do enum, ou em se o segundo portão sequer existe.)

Entretanto, os conselhos de observabilidade mainstream são fatais para a auditoria de duas formas independentes. Para funcionalidades de grande volume acima de ~1,000 pedidos/segundo, [reduza a amostragem do envelope de chamadas para 10-20% e reserve a captura completa ao nível do token para sessões de depuração explícitas](https://www.braintrust.dev/articles/llm-call-observability); e [limpe ou mascare o conteúdo antes de este chegar ao backend](https://mlflow.org/articles/setting-up-llm-observability-pipelines-in-2026/). Uma amostra de dez por cento é inútil quando a decisão que tem de defender está nos noventa por cento que descartou.

| Dimensão | Dashboard de observabilidade | Registo de auditoria |
|---|---|---|
| Consumidor | O autor, minutos depois | Um terceiro indiferente ou hostil, meses depois |
| Latência de leitura | Segundos a horas | Meses a anos |
| Amostragem | Esperada (10-20%, ou baseada na cauda) | Proibida |
| Conteúdo por defeito | Desligado (o conteúdo GenAI da OTel é Opt-In) | Ligado, dentro da sua janela de retenção |
| Contrato de escrita | Fire-and-forget, falha registada | Mesma transação, a falha faz falhar a operação |
| Fonte de ordenação | Timestamps, reamostrados | Sequência atribuída pelo escritor |
| Mutabilidade | Mutável por desenho (reprocessamento, campos descartados na atualização do backend) | Append-only, idealmente encadeado por hash |
| Motor de retenção | Quanto tempo uma regressão se mantém interessante (dias) | Uma obrigação ou um horizonte de disputa (meses a anos) |
| Modo de falha | Depura mais devagar | Não consegue responder à pergunta |

O contrato de escrita é a coisa mais barata de errar. Esta plataforma sustenta ambas as posturas, cada uma correta para o seu artefacto. A escrita de observabilidade do agente é um HTTP POST fire-and-forget (\`AgentClient.recordObservability\`) cuja falha é apanhada e registada em WARN como "non-critical": a execução ainda cobra e retorna, a linha de auditoria é simplesmente perdida. A auditoria de feature-flag (\`V173__flag_flip_audit.sql\`) enuncia o contrato oposto no cabeçalho da sua migração: mesma transação, sem \`REQUIRES_NEW\`, sem async, sem listener \`AFTER_COMMIT\` (isso competiria com um kill da JVM), e se a inserção de auditoria lançar exceção, a flag não é alterada.

A consequência da opção best-effort é o modo de falha que parece bem até precisar dele: **a cobertura do registo passa a estar correlacionada com a saúde do sistema**, portanto rareia exatamente durante os incidentes que lhe vão pedir para explicar.

## O esquema ao nível da execução (run)

Uma linha por execução. Este é o cabeçalho que um auditor lê primeiro.

| Campo | Tipo | Null | Cardinalidade | Dados pessoais | Porque existe |
|---|---|---|---|---|---|
| \`run_id\` | uuid | não | alta | não | Chave de junção para cada linha filha. Cunhe no **dispatch**, não no INSERT. |
| \`trail_seq\` | bigint (sequência dedicada) | não | alta | não | Ordenação que sobrevive ao desvio de relógio e a escritas no mesmo milissegundo. |
| \`prev_row_hmac\` | bytea(32) | sim | alta | não | Evidência de adulteração: cobre o próprio conteúdo mais o HMAC da linha anterior. |
| \`tenant_id\`, \`organization_id\` | text / uuid | não | média | indireto | Chave de âmbito de eliminação e controlo de acesso. |
| \`actor_subject_ref\` | text (token pseudónimo) | sim | alta | **sim** | "Quem pediu." Só resolve para a identidade através de um mapeamento mantido em separado. |
| \`parent_run_id\` | uuid | sim | alta | não | Qual execução deu origem a esta. |
| \`caller_agent_id\` | uuid | sim | média | não | Qual agente a originou. |
| \`depth\` | int2 | não | baixa | não | Deteção de ciclos e ordenação da árvore. |
| \`caller_tool_call_id\` | text | sim | alta | não | A chamada exata no pai que originou o filho. |
| \`trigger_source\` | enum | não | **baixa** | não | manual / chat / webhook / schedule / datasource / workflow / error. Decide se um humano é responsável pela existência da execução. |
| \`started_at\`, \`ended_at\` | timestamptz | não / sim | alta | não | Dois timestamps, não um mais uma duração. |
| \`status\` | enum | não | baixa | não | A afirmação que lhe vão pedir para defender: esta execução teve êxito. |
| \`stop_reason\` | text (string de enum em bruto) | sim | baixa | não | Armazenado literalmente para fins forenses. |
| \`terminal_category\` | enum | sim | baixa | não | Materializado, não derivado no momento da leitura. |
| \`billed_provider\`, \`billed_model\` | text | não | baixa | não | Aquilo por que foi cobrado. |
| \`executed_provider\`, \`executed_model\` | text | sim | baixa | não | Aquilo que efetivamente correu. Podem diferir. |
| \`model_snapshot\` | jsonb (com chave \`_v\`) | sim | média | não | Lista de preços e configuração do modelo congeladas no início da execução. |
| \`prompt_tokens\`, \`completion_tokens\`, \`cache_creation_tokens\`, \`cache_read_tokens\`, \`reasoning_tokens\` | int4 x5 | não (default 0) | alta | não | Cinco contadores, não um total: têm preços diferentes. |
| \`cost_settled\` | numeric(15,4) | sim | alta | não | O montante efetivamente cobrado, materializado no momento da escrita. |
| \`system_prompt_hash\` | bytea(32) | sim | alta | não | Referência, nunca o texto. |
| \`build_sha\` | text(40) | sim | baixa | não | Esta execução é anterior à correção. |
| \`config_snapshot\` | jsonb | sim | média | talvez | Política em vigor, incluindo se era exigida aprovação. |
| \`approval_ref\` | uuid | **sim** | alta | não | NULL significa "nenhuma aprovação exigida pela política em vigor". |
| \`iteration_count\`, \`tool_call_count\` | int4 | não | alta | não | Forma da execução sem ler os seus passos. |

Onze desses precisam de mais do que uma frase.

**Cunhe \`run_id\` no dispatch.** Um bug real: as linhas de reivindicação de tarefa (task-claim) do lado do MCP eram escritas antes de a linha de execução existir, portanto um id gerado pelo Hibernate deixava \`task_id\` silenciosamente NULL. A correção passa um id de execução explícito através da chamada de dispatch e usa-o como chave primária (\`AgentObservabilityRequest.executionId\`, documentado no código como "stable correlation ID minted at dispatch").

**A árvore de chamadas de sub-agentes precisa de quatro campos, não um:** run pai, agente chamador, profundidade, e a chamada de ferramenta exata no pai. Retire qualquer um e uma execução multi-agente lê-se como uma pilha plana e não ordenável.

**Dois timestamps, não um mais uma duração.** Uma duração não pode ser reconciliada com uma linha temporal de eventos externa. Esta é também a única forma de campo que o próprio AI Act nomeia: o Art. 12(3)(a) exige "recording of the period of each use of the system (start date and time and end date and time of each use)".

**O modelo cobrado e o executado podem diferir.** Uma camada de encaminhamento pode enviar um par \`(provider, model)\` cobrado para um alvo de execução diferente, preservando a identidade cobrada na resposta (\`V365__create_model_execution_links.sql\`). Um registo que grave apenas um está errado sobre o que produziu o resultado.

**\`model_snapshot\`** congela a lista de preços no início da execução:

\`\`\`json
{
  "_v": 1,
  "provider": "anthropic",
  "model_id": "claude-opus-4-8",
  "price_input": 5.0,
  "price_output": 25.0,
  "credits_input": 1.0,
  "credits_output": 5.0,
  "canonical_id": "anthropic/claude-opus-4-8",
  "bundle_version": 41,
  "markup": 1.2,
  "captured_at": "2026-07-22T09:14:03Z"
}
\`\`\`

Cerca de 260 bytes, cerca de 905 MB/ano com 10k runs/dia, cerca de um dólar por ano de armazenamento em bloco. Existe para que o custo sobreviva à descontinuação do modelo a meio da execução e a edições de preço retroativas, e é o campo que os engenheiros cortam primeiro e de que mais se arrependem.

**\`cost_settled\` é materializado no momento da escrita.** Recalcular a partir de tokens vezes preço no momento da leitura é o *fallback* que \`model_snapshot\` permite, não o registo; qualquer divergência posterior é ela própria uma conclusão.

**\`terminal_category\` é armazenado materializado mesmo sendo derivável** de \`stop_reason\`, atualmente por código de contrato gerado (\`AgentStopReason.valueOfOrError(x).terminal()\`). O codegen muda; um registo legível daqui a sete anos não pode depender do build deste mês, ou linhas antigas reclassificam-se silenciosamente a si próprias.

**\`build_sha\`** (~40 bytes) é o campo mais frequentemente em falta e mais frequentemente necessário. Armadilha: \`.git\` normalmente não está no contexto de build do Docker, portanto a versão em execução reporta um placeholder estático a menos que o commit seja passado como build arg.

**Nunca armazene o texto do system prompt por execução.** Com 10k runs/dia, um system prompt de 6 KB são 20.89 GB/ano de pura duplicação, e esta plataforma armazena-o até três vezes por execução (a coluna \`agent_executions.system_prompt TEXT\`, uma cópia em \`agent_config_snapshot\` JSONB, e ainda uma linha com role SYSTEM em \`agent_execution_messages\`), portanto 20.89 GB/ano é o piso, não o total. Armazene cada prompt distinto uma vez por versão, referencie por hash. Não é, porém, a maior rubrica evitável: o armazenamento duplicado de resultados de ferramentas (quantificado no artigo companheiro sobre retenção) são 83.55 GB/ano, quatro vezes maior. Esses dois, 83.55 GB/ano de resultados de ferramentas e depois 20.89 GB/ano de system prompts, são os únicos itens evitáveis acima de 10 GB/ano neste modelo.

**\`trail_seq\` vem de uma sequência dedicada, não de \`created_at\`.** Sobrevive ao desvio de relógio, a um restore noutro fuso horário e a duas linhas escritas no mesmo milissegundo. As lacunas são aceitáveis e devem ser documentadas como tal; a monotonicidade é a propriedade afirmada. \`V169__trigger_lifecycle_invariants.sql\` mostra o padrão: ordena o histórico por \`(trigger_id, trigger_type, seq DESC)\` e mantém um índice \`created_at DESC\` apenas para a consulta de operações por janela temporal.

**\`prev_row_hmac\` é a fronteira** entre um log de observabilidade e um registo de auditoria. O HMAC de cada linha cobre o seu próprio conteúdo mais o da linha anterior, portanto uma edição ou eliminação silenciosa quebra a cadeia. O cabeçalho de \`V195__create_organization_audit_event.sql\` desta plataforma lista-o como deliberadamente omitido desse MVP, a par de uma purga de retenção sob um lock distribuído, de um espelho WORM e da separação de papéis append-only. Essa lista serve também de checklist de maturidade.

## O esquema ao nível do passo (step)

Uma linha por turno de LLM, chamada de ferramenta, decisão ou sinal. As linhas de passo superam as linhas de execução em cerca de 26 para 1 e transportam toda a carga (payload), portanto o seu perfil de retenção e de dados pessoais é inteiramente diferente.

| Campo | Tipo | Null | Cardinalidade | Dados pessoais | Porque existe |
|---|---|---|---|---|---|
| \`run_id\` | uuid | não | alta | não | Chave de junção com o pai. |
| \`tenant_id\`, \`organization_id\` | text / uuid | não | média | indireto | Em **cada** linha filha, para eliminação com âmbito de organização. |
| \`step_seq\` | int4 (atribuído pelo escritor) | não | alta | não | Ordem determinística. Nunca derivada de \`created_at\`. |
| \`iteration_seq\` | int4 (atribuído pelo escritor) | não | média | não | A qual turno de LLM isto pertence. |
| \`parallel_index\` | int2 | **sim** | baixa | não | NULL significa sequencial. Distingue um lote concorrente de uma cadeia causal. |
| \`step_kind\` | enum | não | baixa | não | llm_turn / tool_call / decision / signal / message. |
| \`tool_name\` | text | sim | **baixa** | não | O GROUP BY para "o que este agente realmente faz". |
| \`tool_call_id\` | text | sim | alta | não | Correlaciona o pedido com o resultado ao longo de retentativas e reordenações. |
| \`args_digest\` | bytea(32) | sim | alta | não* | Provar ou refutar um payload produzido sem o reter. |
| \`result_digest\` | bytea(32) | sim | alta | não* | O mesmo, para resultados. |
| \`content_length\` | int4 | sim | alta | não | Quão grande **era** o payload, retido depois de ele desaparecer. |
| \`payload_ref\` | uuid | sim | alta | apenas ponteiro | Blob descarregado acima do limiar inline. |
| \`content\` | text | sim | alta | **sim** | Payload inline, no relógio curto. |
| \`error_code\` | enum | sim | baixa | não | Classe de falha legível por máquina. Janela completa. |
| \`error_message\` | text | sim | alta | **sim** | Texto livre. Relógio do payload. |
| \`branch_taken\` | text (rótulo de porta) | sim | baixa | não | Qual aresta de saída a execução seguiu. |
| \`skip_reason\` | text | sim | baixa | não | Porque é que um nó **não** correu. |
| \`skip_source_node\` | text | sim | média | não | Qual decisão a montante o saltou. |
| \`redaction_applied\` | int2 (bitmask) | não | baixa | não | Quais regras de ocultação dispararam. |
| \`prompt_tokens\`, \`completion_tokens\`, ... | int4 | **sim** | alta | não | Escrito apenas quando não zero, portanto NULL mantém o seu significado. |
| \`duration_ms\` | int8 | sim | alta | não | Atribui um timeout ao nível da execução ao passo que consumiu o orçamento. |

\\* Um digest não é dado pessoal apenas quando o espaço do payload não é enumerável (ver a ressalva abaixo).

Os cinco contadores de tokens são NOT NULL default 0 no cabeçalho da execução (uma execução tem sempre um total) mas anuláveis nas linhas de passo, onde NULL significa "não aplicável" (uma linha de chamada de ferramenta não tem tokens), não zero. Some os passos contra o cabeçalho tendo essa regra em mente, ou os dois divergem.

**\`parallel_index\` custa quatro bytes** e evita a pior falha de um registo: reconstruir uma cadeia causal a partir de um lote paralelo, o que é pior do que uma lacuna porque está confiantemente errado.

**\`args_digest\` e \`result_digest\` são o pivô do desenho de retenção.** 32 B por digest; as 6 linhas de chamada de ferramenta transportam dois, as 14 linhas de mensagem transportam um, portanto 832 bytes por execução, 2.83 GB/ano com 10k runs/dia. Mantenha o digest durante toda a janela de obrigação, o payload num relógio curto: quando alguém produz um documento e alega que o agente o viu, o digest prova-o ou refuta-o com zero payload retido.

A ressalva, sem rodeios: **para um espaço de entrada pequeno e enumerável (um código postal, uma data de nascimento) o digest é reidentificável** e tem de ser salgado com uma chave mantida em separado. A regra é "nunca publicar um digest não salgado de um campo de baixa entropia", não "os digests não são pessoais". As [diretrizes de pseudonimização do EDPB](https://www.edpb.europa.eu/system/files/2025-01/edpb_guidelines_202501_pseudonymisation_en.pdf) sustentam que o hashing simples sem separação de domínio e controlo de acesso é insuficiente (projeto de consulta de janeiro de 2025).

**\`content_length\` é definido incondicionalmente antes da decisão de inline, offload ou truncar**, o que é o que diz a um leitor futuro que houve truncagem e quanto é que ele não está a ver (\`AgentObservabilityService\`, \`CONTENT_INLINE_THRESHOLD = 8192\`):

\`\`\`
length = content.length()          # set FIRST, always
if length > 8192:
    id = storage.saveText(content) # payload_ref
    content = content[:500] + "...[truncated]"
else:
    keep inline
# if the offload throws: fall back to an inline prefix
# with NO storage id, which MUST be distinguishable
# from a successful offload.
\`\`\`

**Separe \`error_code\` de \`error_message\`.** As mensagens de texto livre são não consultáveis, instáveis entre atualizações de bibliotecas, e rotineiramente ecoam a entrada que causou a falha, tornando-as o campo de dados pessoais de maior risco no registo, ao mesmo tempo que parecem diagnóstico. O código retém-se durante toda a janela; a mensagem vai para o relógio do payload.

**\`branch_taken\` torna o registo reproduzível no papel** em vez de por reexecução; num motor de workflow as portas são um conjunto fechado de baixa cardinalidade por tipo de nó (\`if\` / \`else\` / \`elseif_N\`, \`case_N\` / \`default\`, \`body\` / \`iterate\` / \`exit\`, \`branch_N\`). Registe também porque é que um nó **não** correu: \`skip_reason\` mais \`skip_source_node\` tornam o negativo um facto de primeira classe, portanto um ramo saltado é distinguível de um que nunca foi alcançado.

**\`redaction_applied\` são dois bytes** que separam três estados que um registo simples confunde: payload limpo, payload ocultado, ou ocultador desativado. Sem ele, um registo de aparência limpa não tem valor probatório. O \`ToolCallRedactor\` desta plataforma tem duas camadas (uma regex de nomes de campos secretos mais uma allowlist de ferramentas de credenciais que apaga todo o corpo do argumento) e não persiste qualquer marcador de qual camada disparou; essa é a lacuna que este campo fecha.

## O registo de aprovação é a sua própria linha, e o seu campo mais difícil é o que o humano viu

O human-in-the-loop é a única coisa que o AI Act enumera para os sistemas que abrange, e a única coisa para a qual a OTel não tem atributo. O Art. 12(3)(d) exige, para os sistemas do Annex III ponto 1(a), "the identification of the natural persons involved in the verification of the results" referidos no Art. 14(5).

Um registo de aprovação utilizável (o \`orchestrator.workflow_signal_waits\` desta plataforma):

\`\`\`
signal_type, signal_config jsonb, status, resolution,
resolution_data jsonb, approval_context text,
expires_at, created_at, claimed_at, claimed_by,
resolved_at, resolved_by,
UNIQUE (run_id, node_id, item_id, epoch)

signal_config = { type, approverRoles, requiredApprovals,
                  timeoutMs, receivedApprovals, delegation,
                  continuationMode }
\`\`\`

**O campo que ninguém regista é o que o aprovador realmente viu.** \`approval_context\` é o template de contexto do nó renderizado contra o contexto de execução **congelado no momento da pausa**, persistido com o sinal, depois reemitido literalmente para o output do nó resolvido para que sobreviva à transição awaiting-to-resolved (migração \`V373\`, que adiciona \`approval_context\` à tabela de signal-wait).

**\`approval_ref\` na linha da execução é anulável, e NULL tem de significar "nenhuma aprovação era exigida pela política em vigor"**, um facto diferente de "estado de aprovação desconhecido". Isso exige que a versão da política seja recuperável a partir de \`config_snapshot\`.

**Os valores por defeito de identidade têm de ser visivelmente distinguíveis de identidades reais.** Aqui, \`resolved_by\` recai no literal \`"system"\` quando é null no output do nó, e em \`"api"\` quando o cabeçalho do utilizador a montante está ausente. Tudo bem, desde que nenhum humano possa alguma vez chamar-se \`api\`.

**Dimensionar uma coluna de identidade é uma preocupação de auditoria.** \`resolved_by\` era \`VARCHAR(100)\` até que identificadores federados da forma \`b:org:user\` (~120 chars) o transbordaram, revertendo a transação de resolução e deixando as aprovações presas em \`CLAIMED\` para sempre, indistinguíveis das genuinamente pendentes (\`V191__signal_waits_widen_resolved_by.sql\`).

**As aprovações delegadas precisam do seu próprio livro-razão de entrega.** \`orchestrator.approval_channel_deliveries\`: um token de callback de uso único (\`VARCHAR(64) UNIQUE\`), estado (\`PENDING\`, \`SENT\`, \`FAILED\`, \`RESOLVED\`, \`CANCELLED\`), o texto da mensagem efetivamente enviado, uma allowlist de utilizadores permitidos, e \`UNIQUE (signal_wait_id, channel)\` como guarda de repetição (replay). A identidade é então uma string com namespace, como \`telegram:<fromId>\`.

**A intenção registada não é controlo imposto, e o registo não deve dar a entender o contrário.** Aqui, \`approverRoles\` é registado na config do sinal e mostrado ao aprovador, mas o endpoint de resolução na aplicação impõe apenas o âmbito da execução, não a pertença ao papel. Se o seu registo grava um papel que não verificou, diga-o na documentação do campo.
`;

export default content;
