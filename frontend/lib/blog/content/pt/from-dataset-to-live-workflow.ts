// from-dataset-to-live-workflow - pt
// Translated from the English body; structure identical. Everything inside a
// fenced code block, every {{...}} template, node name, prefix, output field,
// enum value and file:line citation is byte-identical to English; only prose is
// translated. A wrong template string is the one error a reader copies.
const content = `Escolheu os dados. Agora eles têm de correr sozinhos.

Um conjunto de dados de nicho qualificado permanece inerte até que algo o leia num horário, decida com base nele, e termine numa ação em que um humano confie. Este texto começa exatamente aí: o conjunto de dados já está escolhido. Como selecionar um conjunto de dados de nicho, e que contexto e orçamento custa fazer correr um, são temas cobertos nas peças companheiras (referidas uma vez, não re-argumentadas aqui). Esta começa depois de os dados estarem escolhidos e para num workflow em funcionamento.

Cada mecânica de nó abaixo é citada com base no código e na documentação de um motor de workflows de produção, com as strings exatas. A construção trabalhada numa linha: um trigger de horário atualiza de hora a hora, um pedido HTTP volta a obter a listagem seguida, um nó de código normaliza a resposta em bruto, uma consulta a tabela mais uma decisão separam um SKU nunca visto de um já conhecido, uma segunda decisão sinaliza um movimento de preço material, uma porta de aprovação humana protege a escrita, e só então dispara um alerta. Uma escrita de baseline idempotente significa que as re-execuções nunca duplicam uma única linha. Para cada nó o padrão é o mesmo: primeiro a armadilha portável, depois a string exata deste motor.

## O grafo: oito nós, sete prefixos

Antes de a prosa percorrer a construção, veja o todo de uma só vez. O motor nomeia cada nó com um prefixo de categoria. São sete: \`trigger:\`, \`mcp:\`, \`table:\`, \`agent:\`, \`core:\`, \`note:\`, e \`interface:\` (\`LabelNormalizer.java:14-24\`, \`:262-265\`). A família \`core:\` é a maior, cobrindo Loop, Split, Decision, Switch, Merge, Transform, Wait, Fork, Download File, HTTP Request, Data Input, e User Approval (\`LabelNormalizer.java:182\`). Note que HTTP Request é um nó \`core:\`, não um \`mcp:\` (\`WORKFLOW_NODE_TYPES.md:1559-1594\`).

| # | Nó (papel) | O que faz na construção | Campo de saída chave | Citado em |
|---|---|---|---|---|
| 1 | Trigger de horário | Dispara de hora a hora, o batimento cardíaco | \`triggered_at\`, \`execution_count\` | \`triggers.md:23-27\` |
| 2 | \`core:fetch_listings\` (HTTP) | Leitura fresca da fonte ativa | \`data.organic_results[]\` | \`AGENTS.md:371\`; \`nodes.md:66\` |
| 3 | \`core:normalize\` (código) | Remodela JSON em bruto para \`{sku, price, currency, seen_at}\` | \`result\` (embrulhado) | \`CodeNode.java:130-137\` |
| 4 | \`find_rows\` (consulta de baseline) | Sonda de idempotência por \`sku\` | \`items\`, \`item_count\` | \`ConceptsHelpProvider.java:281\` |
| 5 | \`core:decision\` (novo vs conhecido) | Separa em \`item_count == 0\` | \`selected_branch\` | \`nodes.md:29\` |
| 6a | \`insert_row\` (ramo novo) | Escreve a baseline | linha inserida | \`tables.md:52\` |
| 6b | \`core:decision\` (movimento material) | Sinaliza um movimento acima de 5% | \`selected_branch\` | \`expressions.md:96\` |
| 7 | \`core:user_approval\` | Porta humana antes da escrita | \`approved\`/\`rejected\`/\`timeout\` | \`nodes.md:39\` |
| 8 | \`mcp:send_alert\` + \`update_row\` | A ação real, depois a escrita protegida | enviado, linha combinada | \`nodes.md:62\`; \`tables.md:49\` |

As três operações de tabela mapeiam para os mosaicos da paleta do builder Create Row / Find Rows / Update Row (tipos \`create-row\` / \`find\` / \`update-row\`); os nomes da prosa \`insert_row\` / \`find_rows\` / \`update_row\` são os aliases de agent-tool para esses mosaicos.

Cada saída de nó é referenciada com uma forma uniforme, independentemente do tipo de nó:

\`\`\`
{{type:label.output.field}}
\`\`\`

O segmento \`.output.\` é obrigatório (\`WORKFLOW_NODE_TYPES.md:1650-1660\`; \`expressions.md:9\`). Campos aninhados e indexação de arrays ambos funcionam (\`expressions.md:28-32\`):

\`\`\`
{{mcp:api_call.output.data.users[0].email}}
\`\`\`

Os labels normalizam através de um pipeline fixo de cinco passos: transliterar acentos, passar a minúsculas, substituir cada carácter não alfanumérico por um underscore, colapsar repetições, aparar as extremidades (\`LabelNormalizer.java:55-82\`). Assim, um nó a que atribua o label \`Baseline Lookup\` é referenciado como:

\`\`\`
{{table:baseline_lookup.output.item_count}}
\`\`\`

Se um LLM escrever um label em bruto com espaços dentro de um template, o motor normaliza-o automaticamente antes da avaliação (\`LabelNormalizer.java:496-537\`), razão pela qual os espaços não quebram a resolução. Uma restrição rígida governa o que qualquer nó pode ler: só pode referenciar os seus ancestrais, os nós que já executaram. Os pares e os ramos paralelos não se veem uns aos outros, e não há referência para a frente. O motor resolve apenas a partir de \`context.stepOutputs\` (\`WORKFLOW_NODE_TYPES.md:1617-1644\`).

O trigger de horário aceita apenas um cron padrão de cinco campos. O predefinido do builder \`0 * * * *\` é de hora a hora, e uma abreviatura de intervalo como \`5m\` ou \`1h\` é rejeitada de imediato (\`triggers.md:23-27\`). Emite \`triggered_at\` e um \`execution_count\` iniciado em um, e cada disparo abre uma nova época (\`EXECUTION_ENGINE.md:15\`).

## Atualizar e ler: o batimento cardíaco e a forma real da resposta

O nó 1 é o batimento cardíaco. O nó 2 é um nó HTTP Request que puxa a listagem atual para o único SKU que este workflow segue. É aqui que "atualiza-se a si próprio" deixa de ser um slogan e começa a depender de um payload real.

A lição portável: liga-te à resposta efetiva, não ao schema declarado. Um schema declarado é uma promessa. O que passa no fio é a verdade, e discordam mais vezes do que se admite.

Um exemplo verificado em produção torna-o concreto. O \`amazon_search\` do SerpAPI devolve itens sob \`organic_results[]\`, cada um a transportar \`title\`, \`thumbnail\`, \`price\`, \`extracted_price\`, \`rating\`, \`reviews\`, \`badges\`, \`sponsored\`, e \`delivery[]\`. O que não transporta é um booleano \`prime\` nem um campo \`brand\`. Para saber se um item é enviado com Prime, faz correspondência de \`/prime/i\` contra o array \`delivery[]\`, não contra um campo \`prime\` que não existe (\`AGENTS.md:371\`). Entretanto, o \`outputSchema\` declarado do catálogo lista otimisticamente um booleano \`prime\` (\`serpapi.json:8879\`), um \`brand\` (\`serpapi.json:8849\`), e \`delivery\` como um objeto (\`serpapi.json:8889\`). O payload ativo contradiz os três. Lê o que chega.

Há uma segunda razão pela qual o nó de leitura não pode ser confiado às cegas. Um nó HTTP Request trata um 404 ou 500 como um sucesso ao nível do nó. Só um erro de transporte faz o nó falhar (\`nodes.md:66\`). Por isso o passo de normalização que se segue tem de se defender contra um erro em forma de corpo, um erro entregue dentro de um 200. Não assumas que uma falha de nó o vai apanhar, porque não vai.

## Remodelar: o nó de código, e as duas armadilhas que o fazem parecer vazio

O nó 3 é um nó \`core:code\` que achata a resposta em bruto na forma de que tudo a jusante precisa: \`{sku, price, currency, seen_at}\`. Aceita exatamente três parâmetros: \`code\`, \`language\`, e \`timeoutSeconds\`. Não há \`input_mapping\`. As linguagens são \`javascript\`, \`python\`, \`typescript\`, e \`bash\`, e \`timeoutSeconds\` fica limitado ao intervalo 1 a 120, com predefinição de 10 (\`CodeNode.java:67-70\`, \`:170-177\`).

Como os itens de \`amazon_search\` não transportam campo \`sku\` nem \`currency\`, a normalização deriva-os: \`sku\` a partir do identificador do produto (o \`asin\`, ou extraído do link do produto), e \`currency\` como uma constante para uma vigilância de um único marketplace, já que nenhum deles é um campo de primeira classe na resposta. É também aqui que vive a proteção contra erro em forma de corpo: inspeciona o corpo de 200 à procura de uma chave de erro e confirma o array antes de mapeares. A versão ERRADA lê \`organic_results\` diretamente e deixa um corpo de erro fluir a jusante; a versão CORRETA falha de forma barulhenta primeiro:

\`\`\`
const res = $input.fetch_listings && $input.fetch_listings.data;
if (!res || res.error || !Array.isArray(res.organic_results)) {
  throw new Error("bad body: " + JSON.stringify($input).slice(0, 300));
}
const top = res.organic_results[0];
$output = {
  sku: top.asin,
  price: top.extracted_price,
  currency: "USD",
  seen_at: new Date().toISOString()
};
\`\`\`

Como a normalização escolhe \`organic_results[0]\`, \`$output\` é um único objeto, não um array. Isso importa: uma saída de normalização em forma de array faria o template de valor único \`{{core:normalize.output.result.sku}}\` resolver para nada, o valor do \`find_rows\` da proteção ficaria vazio, \`item_count\` leria 0 em cada execução, e uma linha de SKU em branco seria inserida de hora a hora com a proteção idempotente silenciosamente derrotada. Mantém a normalização a emitir um objeto; se alguma vez precisares de espalhar por muitas listagens, isso é um nó \`core:split\`, não um retorno de array simples.

Duas armadilhas fazem um nó de código parecer vazio sem erro nenhum, o que é o pior tipo de falha porque não há nada no log para perseguir.

A armadilha portável um é a forma do input. Os dados a montante não chegam à raiz do teu objeto de input. Chegam indexados pelo label do nó predecessor. Neste motor o wrapper JavaScript injeta \`const $input = JSON.parse(...)\` e \`let $output = undefined\` (\`CodeNode.java:180-190\`), e a saída de cada passo a montante é colocada sob a sua própria chave de label com o seu envelope removido (\`CodeNode.java:300-319\`; \`OutputUnwrapper.java:178-186\`). Por isso lês a saída do fetch como \`$input.fetch_listings.data.organic_results\`, ou \`$input['core:fetch_listings']\` se preferires o acesso por parênteses retos. Nunca lês \`$input.organic_results\`, que é undefined. Atribuis o teu resultado a \`$output\`, e ele é capturado através de um prefixo de stdout \`__RESULT__\` e re-parseado de JSON (\`CodeNode.java:180-190\`). O Python usa \`_input\` e \`_output\`, o bash usa \`INPUT\` e \`OUTPUT\`.

A armadilha portável dois é o aninhamento da saída. Muitos motores embrulham o que devolves dentro de um envelope próprio. Aqui, o motor embrulha o teu objeto \`$output\` sob uma chave \`result\` extra (\`CodeNode.java:130-137\`, \`result.put("result", parsedResult)\`; \`CodeNodeSpec.java:22-26\`). A jusante tens de furar para lá dela:

\`\`\`
{{core:normalize.output.result.sku}}
\`\`\`

E para mapear o objeto normalizado inteiro para um parâmetro a jusante, apontas para \`.result\`:

\`\`\`
{"result":"{{core:normalize.output.result}}"}
\`\`\`

Erra o aninhamento e obténs um silencioso duplo \`result.result\` e uma leitura vazia, nunca um erro (nota do Interface System em \`AGENTS.md\`).

Uma mecânica de apoio explica por que o mapa de objeto inteiro acima tem de ser um template solitário. Um \`{{...}}\` único e puro devolve o valor tipado, um Number, um Map, ou uma List. A mesma expressão embutida em prosa envolvente é coagida a uma String, com os Maps auto-codificados como JSON (\`expressions.md:72-74\`). Os parâmetros do tipo objeto têm portanto de ser um único template, nunca costurados dentro de texto.

## A tabela correto-versus-errado que mais ninguém tem

Cada linha enuncia a armadilha geral em palavras simples; as strings exatas errada e correta para este motor estão cercadas logo abaixo, para que a diferença de um token seja legível sem enfiar um template longo numa célula de tabela.

| Nó / operação | Armadilha geral (portável) | Citado em |
|---|---|---|
| Leitura de campo de nó de código | O objeto devolvido fica sob um envelope | \`CodeNode.java:130-137\` |
| Mapa de objeto inteiro de nó de código | O envelope tem de ser incluído ao mapear o objeto inteiro | \`AGENTS.md\` GOTCHA |
| Leitura de input de nó de código | O input é indexado pelo label do predecessor, não pela raiz | \`CodeNode.java:300-319\` |
| Coluna where de tabela | A coluna é o nome nu armazenado | \`CrudRepository.java:369-372\` |
| Limiar numérico | Um filtro que parece numérico pode comparar como texto | \`CrudRepository.java:378-416\` |
| Construir um parâmetro objeto | Alguns transforms convertem objetos em string | \`AGENTS.md\` achado #2 |

Leitura de campo de nó de código:

\`\`\`
WRONG:   {{core:normalize.output.sku}}
CORRECT: {{core:normalize.output.result.sku}}
\`\`\`

Mapa de objeto inteiro de nó de código:

\`\`\`
WRONG:   {"result":"{{core:normalize.output}}"}
CORRECT: {"result":"{{core:normalize.output.result}}"}
\`\`\`

Leitura de input de nó de código:

\`\`\`
WRONG:   $input.organic_results
CORRECT: $input.fetch_listings.data.organic_results
\`\`\`

Coluna where de tabela:

\`\`\`
WRONG:   {column:'data.sku', operator:'=', value:'ABC-123'}
CORRECT: {column:'sku', operator:'=', value:'ABC-123'}
\`\`\`

Limiar numérico (faz a matemática num \`core:decision\`, não na query):

\`\`\`
WRONG:   {column:'price', operator:'>', value:9}
CORRECT: compare in core:decision (SpEL, numeric)
\`\`\`

Construir um parâmetro objeto:

\`\`\`
WRONG:   assemble the object in a core:transform mapping
CORRECT: assemble it in a core:code node ($output keeps JSON types)
\`\`\`

A linha do transform queima quem nunca desconfia dela. Um nó \`core:transform\` converte valores de objeto em string. Um objeto que montes dentro de uma expressão de transform chega a um parâmetro de ferramenta do tipo objeto a jusante como uma String, produzindo um erro do fornecedor como \`expected map, actual string\` (\`AGENTS.md\` achado #2 do workflow-builder). Os valores do tipo objeto têm de ser construídos num nó \`core:code\`, onde os campos de \`$output\` mantêm os seus tipos JSON reais através do template de valor inteiro.

A linha da coluna where de tabela também vale a pena internalizar. Os dados do utilizador vivem numa única coluna JSONB \`data\`, e a coluna where é o nome nu. Um prefixo \`data.\` inicial é auto-removido tanto em tempo de build como em tempo de execução, e uma coluna com ponto é de resto rejeitada pelo sanitizer, por isso a remoção é obrigatória e não cosmética (\`CrudRepository.java:369-372\`; \`SqlSanitizer.java:46\`). O nome reservado \`id\` mapeia para a chave primária da linha via \`id::text\`, não para um campo JSONB.

## Decidir: onde a comparação acontece de facto

O nó 5 é a camada de decisão, e esconde a mecânica mais surpreendente da construção.

A armadilha portável: um filtro que parece numérico pode comparar como texto, e a ordenação de texto não é a ordenação de números. Neste motor, as cláusulas where de CRUD de tabela comparam tudo como texto. As colunas armazenadas são lidas via \`jsonb_extract_path_text(data, :col)\`, a chave primária via \`id::text\`, e o valor ligado passa por \`.toString()\` (\`CrudRepository.java:378-416\`). Entretanto a comparação SpEL dentro de uma condição de decisão é numérica (\`expressions.md:96\`). O mesmo operador \`>\` de aparência igual, dois mundos diferentes.

| Onde a comparação corre | Tipo de comparação | Operadores fiáveis | Operadores que enganam | Citado em |
|---|---|---|---|---|
| Cláusula where de CRUD de tabela | Textual / lexicográfica | \`=\`, \`!=\`, \`IN\`, \`IS NULL\`, \`IS NOT NULL\`, \`LIKE\` | \`>\`, \`<\`, \`>=\`, \`<=\` | \`CrudRepository.java:378-416\` |
| \`core:decision\` (SpEL) | Numérica | todos os operadores de comparação | nenhum para números | \`expressions.md:96\` |

A consequência é um bug latente real. Numa cláusula where, \`amount > 9\` exclui \`'100'\`, porque \`'1'\` ordena antes de \`'9'\`. E \`id > 5\` salta silenciosamente os ids 10 a 99 (\`WorkflowBuilderHelpModule.java:258-262\`). Os operadores de ordenação só são seguros numa cláusula where quando a ordem lexical calha coincidir com a intenção, o que significa strings com zeros à esquerda ou datas ISO na forma \`yyyy-MM-dd\` (\`WorkflowBuilderHelpModule.java:262\`). Não há um operador de ordenação com cast numérico ao qual recorrer; uma comparação ciente de números é uma correção conhecida mas não lançada à data desta escrita.

Por isso a matemática de "o preço moveu-se mais de 5%" pertence ao nó 6b, um \`core:decision\`, não à query. Precisa do preço anterior, que vive no resultado do \`find_rows\`: \`find_rows\` devolve \`items[]\`, e cada linha correspondida expõe os seus campos achatados, por isso o preço de baseline está em \`items[0].price\` (\`ConceptsHelpProvider.java:281\`; indexação de array conforme \`expressions.md:28-32\`). Como o valor armazenado voltou através do mesmo caminho de texto de toda leitura JSONB, a aritmética tem de o converter: envolve ambos os operandos em \`double()\` antes de subtrair. A condição:

\`\`\`
{{ (double(core:normalize.output.result.price) - double(table:baseline_lookup.output.items[0].price)) / double(table:baseline_lookup.output.items[0].price) > 0.05 }}
\`\`\`

Uma decisão ativa exatamente um ramo. A primeira condição verdadeira vence, e as restantes tornam-se SKIPPED. As suas portas são \`if\`, \`elseif_N\`, e \`else\` (\`nodes.md:29\`; \`WORKFLOW_NODE_TYPES.md:411-418\`).

Uma regra estrutural amarra o grafo. As arestas são registos simples \`{from, to}\` com um sufixo \`:port\` opcional, e as condições de ramo nunca vivem na aresta. Vivem no nó \`cores[]\`, como \`decisionConditions\` ou \`switchCases\` (\`WORKFLOW_NODE_TYPES.md:33-40\`, \`:349-361\`). Duas consequências decorrem só da topologia das arestas. Múltiplas arestas sem condição a saírem de uma fonte formam um Fork implícito, correndo todos os ramos em paralelo. Múltiplas arestas para um só nó formam um merge-AND implícito que espera que cada predecessor resolva, seja COMPLETED ou SKIPPED (\`WORKFLOW_NODE_TYPES.md:1008-1010\`, \`:1053-1056\`, \`:925-940\`).

## A proteção de escrita idempotente, desenhada como um sub-grafo real

Um trigger que se atualiza a si próprio dispara a mesma leitura de hora a hora. Sem uma proteção, insere a baseline do mesmo SKU todas as horas, e a tabela enche-se de duplicados. O padrão geral que corrige isto em qualquer motor: procura primeiro, decide com base na contagem, depois escreve só quando o item é novo. Nunca insiras incondicionalmente quando o mesmo item pode voltar a ser obtido.

Este motor não tem operação de upsert nem de truncate, e é precisamente por isso que a proteção é obrigatória e não opcional (\`tables.md:49\`; \`CrudRepository.java\` \`deleteRows\` requer um where validado).

| Passo | Nó | Ramo / porta tomado | Efeito na tabela | Citado em |
|---|---|---|---|---|
| 1 | \`find_rows\` por \`sku\` | (alimenta a decisão) | lê, não escreve nada | \`ConceptsHelpProvider.java:281\` |
| 2 | \`core:decision\` sobre item_count | \`if\` (verdadeiro) = nunca visto | ainda nada | \`WorkflowBuilderHelpModule.java:252-254\` |
| 3a | \`insert_row\` (baseline) | no ramo \`if\` | uma nova linha escrita | \`tables.md:52\` |
| 3b | decisão de mudança material | no ramo \`else\` | ainda nada | \`nodes.md:29\` |
| 4 | \`update_row\` (após aprovação) | porta approved | chaves JSONB nomeadas combinadas | \`tables.md:49\` |

As duas strings exatas da proteção, cercadas para que os templates fiquem inteiros:

\`\`\`
find_rows {column:'sku', operator:'=', value:'{{core:normalize.output.result.sku}}'}
\`\`\`

\`\`\`
{{table:baseline_lookup.output.item_count == 0}}
\`\`\`

A sonda que faz isto funcionar é o \`find_rows\`, que expõe \`items[]\` (as linhas encontradas) e \`item_count\` (a contagem). Um \`item_count\` de 0 é o sinal de "ainda não processado" que transforma a tabela em memória partilhada entre execuções (\`ConceptsHelpProvider.java:281\`). A proteção procura-depois-decide é o que torna um workflow que se atualiza seguro (\`AGENTS.md\` \`dedupe_idempotent_write\`).

A escrita no caminho do SKU conhecido é um \`update_row\`, que requer tanto um where como um mapa set não vazio, e combina apenas as chaves JSONB nomeadas através de \`data || jsonb_build_object\` (\`tables.md:49\`). É uma combinação parcial, não uma substituição, por isso não anula os campos que omitires.

Uma cilada de tenant vai desperdiçar uma tarde se não a souberes. A ferramenta MCP \`table\` corre sob o tenant do utilizador do chat, não sob o do dono do workflow. Cada query CRUD é limitada com \`AND tenant_id = :tenant_id\`, por isso a ferramenta pode mostrar 0 linhas enquanto o próprio \`find_rows\` do workflow vê os dados reais (\`AGENTS.md\`). Para inspecionar ou limpar uma tabela detida por um workflow, corre a operação de dentro desse workflow, no tenant correto.

## Barrar, depois agir

O nó 7 é a verificação humana antes do passo irreversível. O princípio geral: coloca uma porta bloqueante antes de qualquer ação que não possas desfazer, e torna-a determinística quanto ao que acontece a seguir.

Neste motor a porta é um sinal \`USER_APPROVAL\`. O nó entra em AWAITING_SIGNAL e a execução pausa. USER_APPROVAL é sempre bloqueante, ao contrário de um sinal de interface, que bloqueia apenas quando \`__continue\` está mapeado (\`EXECUTION_ENGINE.md:15\`; \`INTERFACE_NODE_GUIDE.md:783-787\`). O nó tem três portas de retoma nomeadas, \`approved\`, \`rejected\`, e \`timeout\`, e encaminha deterministicamente pela decisão tomada (\`nodes.md:39\`; \`WorkflowHelpProvider.java:665\`). O timeout predefinido é de 24 horas quando não definido (\`nodes.md:39\`).

Como uma atualização dispara de hora a hora, duas questões importam. Primeiro, o que acontece se a aprovação for disparada duas vezes? Nada de mau. A resolução é reclamar-antes-de-processar: \`resolveSignal()\` devolve false num sinal já resolvido, por isso uma aprovação re-disparada nunca faz o DAG avançar em duplicado (\`INTERFACE_NODE_GUIDE.md:1008\`). Segundo, o que acontece ao próximo disparo agendado enquanto um humano está sentado sobre a decisão? Cada disparo abre uma nova época, os resultados da época anterior persistem e continuam navegáveis, e um sinal bloqueante adia o reset do ciclo de trigger até resolver (\`EXECUTION_ENGINE.md:15\`). A atualização não atropela uma decisão pendente.

Na porta \`approved\`, a ação real dispara. Isso pode ser um nó Send Email de primeira classe ou qualquer integração \`mcp:\` ligada (\`nodes.md:62\`), seguido do \`update_row\` protegido. Nas portas \`rejected\` e \`timeout\`, nada é escrito e nada é enviado.

## Prova cada ramo antes de o declarares ativo

A regra de teste não é negociável: exercita cada ramo contra um orquestrador ativo e segue o log do serviço em paralelo. Uma resposta verde com um stacktrace no log é uma falha, não uma aprovação (\`AGENTS.md\` Feature Development Flow passo 4). "Devolveu 200" não é prova de que o ramo funcionou.

| Cenário | Condição de disparo | Ramo / sinal esperado | Asserção de aprovação | Sinal de falha |
|---|---|---|---|---|
| Inserção de SKU novo | SKU sem linha de baseline | ramo \`if\`, \`insert_row\` | exatamente uma linha inserida | linha duplicada, ou stacktrace no log |
| Sem mudança | SKU conhecido, preço dentro de 5% | decisão material \`else\` | sem flag, sem aprovação, sem alerta | qualquer alerta ou pausa |
| Mudança material | SKU conhecido, movimento acima de 5% | execução PAUSA em AWAITING_SIGNAL | estado AWAITING_SIGNAL USER_APPROVAL | execução completa sem pausar |
| Portas de aprovação | Resolve cada uma das três portas | approved / rejected / timeout | approved escreve + alerta; as outras não fazem nenhum | escrita em rejected/timeout |
| Idempotência de re-execução | Dispara o horário duas vezes | a proteção bloqueia a segunda inserção | contagem de linhas estável | contagem de linhas cresce |

Corre os cinco antes de confiar no grafo. O cenário de mudança material deve pausar visivelmente; se completar, a tua matemática de limiar está na camada errada, provavelmente uma cláusula where lexicográfica a fingir ser numérica.

Três lições transportam-se para qualquer motor em que construas a seguir. Aninhamento de saída: fura até \`{{core:normalize.output.result.sku}}\`, nunca \`{{core:normalize.output.sku}}\`, porque as plataformas embrulham o que devolves. Comparação textual: calcula o movimento de 5% num \`core:decision\`, não na cláusula where do \`find_rows\`, porque essa comparação é lexical. Objetos convertidos em string: constrói valores tipados num nó \`core:code\`, não num \`core:transform\` que os achata para strings. E a proteção procura-depois-decide é o padrão que torna um workflow que se atualiza a si próprio seguro em qualquer lado, porque um horário que age só é tão fiável quanto a sua defesa contra repetir-se a si mesmo.
`;

export default content;
