// from-dataset-to-live-workflow - es
// Translated from the English body; structure identical. Everything inside a
// fenced code block, every {{...}} template, node name, prefix, output field,
// enum value and file:line citation is byte-identical to English; only prose is
// translated. A wrong template string is the one error a reader copies.
const content = `Elegiste los datos. Ahora tienen que ejecutarse solos.

Un conjunto de datos de nicho cualificado es inerte hasta que algo lo lee de forma programada, decide en función de él y termina en una acción en la que un humano confiará. Esta pieza empieza justo ahí: el conjunto de datos ya está elegido. Cómo seleccionar un conjunto de datos de nicho, y qué contexto y presupuesto cuesta ejecutar uno, se cubren en las piezas complementarias (enlazadas una vez, no vueltas a argumentar aquí). Esta empieza después de que los datos están elegidos y se detiene en un flujo de trabajo en ejecución.

Cada mecánica de nodo de abajo está citada al código y la documentación de un motor de flujos de trabajo de producción, con las cadenas exactas. La construcción trabajada en una línea: un schedule trigger se refresca cada hora, una petición HTTP vuelve a extraer el listado rastreado, un nodo de código normaliza la respuesta cruda, una búsqueda en tabla más una decisión separan un SKU nunca visto de uno conocido, una segunda decisión marca un movimiento de precio significativo, una compuerta de aprobación de usuario protege la escritura, y solo entonces se dispara una alerta. Una escritura de línea base idempotente significa que las re-ejecuciones nunca duplican una sola fila. Para cada nodo el patrón es el mismo: primero la trampa portable, luego la cadena exacta de este motor.

## El grafo: ocho nodos, siete prefijos

Antes de que la prosa recorra la construcción, obsérvala entera de un vistazo. El motor nombra cada nodo con un prefijo de categoría. Hay siete: \`trigger:\`, \`mcp:\`, \`table:\`, \`agent:\`, \`core:\`, \`note:\` e \`interface:\` (\`LabelNormalizer.java:14-24\`, \`:262-265\`). La familia \`core:\` es la más grande, y cubre Loop, Split, Decision, Switch, Merge, Transform, Wait, Fork, Download File, HTTP Request, Data Input y User Approval (\`LabelNormalizer.java:182\`). Ten en cuenta que HTTP Request es un nodo \`core:\`, no uno \`mcp:\` (\`WORKFLOW_NODE_TYPES.md:1559-1594\`).

| # | Nodo (rol) | Qué hace en la construcción | Campo de salida clave | Citado en |
|---|---|---|---|---|
| 1 | Schedule trigger | Se dispara cada hora, el latido | \`triggered_at\`, \`execution_count\` | \`triggers.md:23-27\` |
| 2 | \`core:fetch_listings\` (HTTP) | Lectura fresca de la fuente en vivo | \`data.organic_results[]\` | \`AGENTS.md:371\`; \`nodes.md:66\` |
| 3 | \`core:normalize\` (code) | Reformar el JSON crudo a \`{sku, price, currency, seen_at}\` | \`result\` (envuelto) | \`CodeNode.java:130-137\` |
| 4 | \`find_rows\` (búsqueda de línea base) | Sonda de idempotencia por \`sku\` | \`items\`, \`item_count\` | \`ConceptsHelpProvider.java:281\` |
| 5 | \`core:decision\` (nuevo vs conocido) | Divide según \`item_count == 0\` | \`selected_branch\` | \`nodes.md:29\` |
| 6a | \`insert_row\` (rama nueva) | Escribe la línea base | fila insertada | \`tables.md:52\` |
| 6b | \`core:decision\` (movimiento significativo) | Marca un movimiento por encima del 5% | \`selected_branch\` | \`expressions.md:96\` |
| 7 | \`core:user_approval\` | Compuerta humana antes de la escritura | \`approved\`/\`rejected\`/\`timeout\` | \`nodes.md:39\` |
| 8 | \`mcp:send_alert\` + \`update_row\` | La acción real, luego la escritura protegida | enviado, fila combinada | \`nodes.md:62\`; \`tables.md:49\` |

Las tres operaciones de tabla se corresponden con las fichas de la paleta del constructor Create Row / Find Rows / Update Row (tipos \`create-row\` / \`find\` / \`update-row\`); los nombres \`insert_row\` / \`find_rows\` / \`update_row\` de la prosa son los alias de herramienta de agente para esas fichas.

Cada salida de nodo se referencia con una forma uniforme, sin importar el tipo de nodo:

\`\`\`
{{type:label.output.field}}
\`\`\`

El segmento \`.output.\` es obligatorio (\`WORKFLOW_NODE_TYPES.md:1650-1660\`; \`expressions.md:9\`). Tanto los campos anidados como la indexación de arreglos funcionan (\`expressions.md:28-32\`):

\`\`\`
{{mcp:api_call.output.data.users[0].email}}
\`\`\`

Las etiquetas se normalizan a través de una tubería fija de cinco pasos: transliterar acentos, pasar a minúsculas, reemplazar cada carácter no alfanumérico por un guion bajo, colapsar repeticiones, recortar los extremos (\`LabelNormalizer.java:55-82\`). Así, un nodo que etiquetes como \`Baseline Lookup\` se referencia como:

\`\`\`
{{table:baseline_lookup.output.item_count}}
\`\`\`

Si un LLM escribe una etiqueta cruda con espacios dentro de una plantilla, el motor la normaliza automáticamente antes de la evaluación (\`LabelNormalizer.java:496-537\`), por lo que los espacios no rompen la resolución. Una restricción estricta gobierna lo que cualquier nodo puede leer: solo puede referenciar a sus ancestros, los nodos que ya se ejecutaron. Los pares y las ramas paralelas no pueden verse entre sí, y no hay referencia hacia adelante. El motor resuelve únicamente desde \`context.stepOutputs\` (\`WORKFLOW_NODE_TYPES.md:1617-1644\`).

El schedule trigger solo acepta un cron estándar de cinco campos. El valor predeterminado del constructor \`0 * * * *\` es cada hora, y las abreviaturas de intervalo como \`5m\` o \`1h\` se rechazan de plano (\`triggers.md:23-27\`). Emite \`triggered_at\` y un \`execution_count\` que empieza en uno, y cada disparo abre una nueva época (\`EXECUTION_ENGINE.md:15\`).

## Refrescar y leer: el latido y la forma real de la respuesta

El nodo 1 es el latido. El nodo 2 es un nodo HTTP Request que extrae el listado actual del único SKU que este flujo de trabajo rastrea. Aquí es donde "se refresca solo" deja de ser un eslogan y empieza a depender de un payload real.

La lección portable: enlaza con la respuesta real, no con el esquema declarado. Un esquema declarado es una promesa. El cable es la verdad, y discrepan más a menudo de lo que nadie admite.

Un ejemplo verificado en producción lo hace concreto. El \`amazon_search\` de SerpAPI devuelve elementos bajo \`organic_results[]\`, cada uno con \`title\`, \`thumbnail\`, \`price\`, \`extracted_price\`, \`rating\`, \`reviews\`, \`badges\`, \`sponsored\` y \`delivery[]\`. Lo que no lleva es un booleano \`prime\` ni un campo \`brand\`. Para saber si un elemento se envía con Prime, comparas \`/prime/i\` contra el arreglo \`delivery[]\`, no contra un campo \`prime\` que no existe (\`AGENTS.md:371\`). Mientras tanto, el \`outputSchema\` declarado del catálogo lista optimistamente un booleano \`prime\` (\`serpapi.json:8879\`), un \`brand\` (\`serpapi.json:8849\`) y \`delivery\` como un objeto (\`serpapi.json:8889\`). El payload en vivo contradice los tres. Lee lo que llega.

Hay una segunda razón por la que no se puede confiar ciegamente en el nodo de lectura. Un nodo HTTP Request trata un 404 o un 500 como un éxito a nivel de nodo. Solo un error de transporte hace fallar al nodo (\`nodes.md:66\`). Así que el paso de normalización que sigue debe defenderse de un error con forma de cuerpo, un error entregado dentro de un 200. No supongas que un fallo de nodo lo atrapará, porque no lo hará.

## Reformar: el nodo de código, y las dos trampas que lo hacen parecer vacío

El nodo 3 es un nodo \`core:code\` que aplana la respuesta cruda a la forma que todo lo de aguas abajo necesita: \`{sku, price, currency, seen_at}\`. Toma exactamente tres parámetros: \`code\`, \`language\` y \`timeoutSeconds\`. No hay \`input_mapping\`. Los lenguajes son \`javascript\`, \`python\`, \`typescript\` y \`bash\`, y \`timeoutSeconds\` se limita al rango de 1 a 120, con valor predeterminado 10 (\`CodeNode.java:67-70\`, \`:170-177\`).

Como los elementos de \`amazon_search\` no llevan campo \`sku\` ni \`currency\`, la normalización los deriva: \`sku\` a partir del identificador del producto (el \`asin\`, o extraído del enlace del producto), y \`currency\` como constante para un seguimiento de un solo marketplace, ya que ninguno es un campo de primera clase en la respuesta. Aquí es también donde vive la protección contra el error con forma de cuerpo: inspecciona el cuerpo del 200 en busca de una clave de error y confirma el arreglo antes de mapear. La versión INCORRECTA lee \`organic_results\` directamente y deja que un cuerpo de error fluya aguas abajo; la versión CORRECTA falla ruidosamente primero:

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

Como la normalización elige \`organic_results[0]\`, \`$output\` es un único objeto, no un arreglo. Eso importa: una salida de normalización con forma de arreglo haría que la plantilla de valor único \`{{core:normalize.output.result.sku}}\` resolviera a nada, el valor del \`find_rows\` de la protección quedaría vacío, \`item_count\` leería 0 en cada ejecución, y se insertaría cada hora una fila con SKU en blanco mientras la protección idempotente queda silenciosamente derrotada. Mantén la normalización emitiendo un objeto; si alguna vez necesitas desplegarte sobre muchos listados, eso es un nodo \`core:split\`, no un simple retorno de arreglo.

Dos trampas hacen que un nodo de código parezca vacío sin ningún error en absoluto, que es el peor tipo de fallo porque no hay nada en el log que perseguir.

La trampa portable número uno es la forma de la entrada. Los datos de aguas arriba no llegan a la raíz de tu objeto de entrada. Llegan indexados por la etiqueta del nodo predecesor. En este motor el envoltorio de JavaScript inyecta \`const $input = JSON.parse(...)\` y \`let $output = undefined\` (\`CodeNode.java:180-190\`), y la salida de cada paso de aguas arriba se coloca bajo su propia clave de etiqueta con su envoltura eliminada (\`CodeNode.java:300-319\`; \`OutputUnwrapper.java:178-186\`). Así que lees la salida del fetch como \`$input.fetch_listings.data.organic_results\`, o \`$input['core:fetch_listings']\` si prefieres el acceso por corchetes. Nunca lees \`$input.organic_results\`, que es undefined. Asignas tu resultado a \`$output\`, y se captura mediante un prefijo de stdout \`__RESULT__\` y se parsea de vuelta como JSON (\`CodeNode.java:180-190\`). Python usa \`_input\` y \`_output\`, bash usa \`INPUT\` y \`OUTPUT\`.

La trampa portable número dos es el anidamiento de la salida. Muchos motores envuelven lo que devuelves dentro de una envoltura propia. Aquí, el motor envuelve tu objeto \`$output\` bajo una clave \`result\` adicional (\`CodeNode.java:130-137\`, \`result.put("result", parsedResult)\`; \`CodeNodeSpec.java:22-26\`). Aguas abajo debes atravesarla:

\`\`\`
{{core:normalize.output.result.sku}}
\`\`\`

Y para mapear el objeto normalizado completo a un parámetro de aguas abajo, apuntas a \`.result\`:

\`\`\`
{"result":"{{core:normalize.output.result}}"}
\`\`\`

Equivoca el anidamiento y obtienes un silencioso doble \`result.result\` y una lectura vacía, nunca un error (nota del Interface System en \`AGENTS.md\`).

Un mecanismo de apoyo explica por qué el mapeo del objeto completo de arriba debe ser una plantilla solitaria. Un \`{{...}}\` puro y único devuelve el valor tipado, un Number, un Map o una List. La misma expresión incrustada en prosa circundante se convierte a String, con los Maps auto-codificados como JSON (\`expressions.md:72-74\`). Los parámetros de tipo objeto tienen por tanto que ser una única plantilla, nunca cosidos dentro de un texto.

## La tabla de correcto-contra-incorrecto que nadie más tiene

Cada fila enuncia la trampa general en palabras llanas; las cadenas exactas, la incorrecta y la correcta, para este motor están encerradas justo debajo, de modo que la diferencia de un solo token sea legible sin embutir una plantilla larga en una celda de tabla.

| Nodo / operación | Trampa general (portable) | Citado en |
|---|---|---|
| Lectura de campo de nodo de código | El objeto devuelto queda bajo una envoltura | \`CodeNode.java:130-137\` |
| Mapeo de objeto completo de nodo de código | La envoltura debe incluirse al mapear el objeto completo | \`AGENTS.md\` GOTCHA |
| Lectura de entrada de nodo de código | La entrada se indexa por la etiqueta del predecesor, no por la raíz | \`CodeNode.java:300-319\` |
| Columna where de tabla | La columna es el nombre almacenado desnudo | \`CrudRepository.java:369-372\` |
| Umbral numérico | Un filtro que parece numérico puede comparar como texto | \`CrudRepository.java:378-416\` |
| Construir un parámetro objeto | Algunas transformaciones convierten los objetos en cadenas | \`AGENTS.md\` hallazgo #2 |

Lectura de campo de nodo de código:

\`\`\`
WRONG:   {{core:normalize.output.sku}}
CORRECT: {{core:normalize.output.result.sku}}
\`\`\`

Mapeo de objeto completo de nodo de código:

\`\`\`
WRONG:   {"result":"{{core:normalize.output}}"}
CORRECT: {"result":"{{core:normalize.output.result}}"}
\`\`\`

Lectura de entrada de nodo de código:

\`\`\`
WRONG:   $input.organic_results
CORRECT: $input.fetch_listings.data.organic_results
\`\`\`

Columna where de tabla:

\`\`\`
WRONG:   {column:'data.sku', operator:'=', value:'ABC-123'}
CORRECT: {column:'sku', operator:'=', value:'ABC-123'}
\`\`\`

Umbral numérico (haz el cálculo en un \`core:decision\`, no en la consulta):

\`\`\`
WRONG:   {column:'price', operator:'>', value:9}
CORRECT: compare in core:decision (SpEL, numeric)
\`\`\`

Construir un parámetro objeto:

\`\`\`
WRONG:   assemble the object in a core:transform mapping
CORRECT: assemble it in a core:code node ($output keeps JSON types)
\`\`\`

La fila del transform quema a quien nunca lo sospecha. Un nodo \`core:transform\` convierte los valores de objeto en cadenas. Un objeto que ensamblas dentro de una expresión de transform llega a un parámetro de herramienta de tipo objeto de aguas abajo como String, produciendo un error del proveedor como \`expected map, actual string\` (\`AGENTS.md\` hallazgo #2 del constructor de flujos de trabajo). Los valores de tipo objeto deben construirse en un nodo \`core:code\` en su lugar, donde los campos de \`$output\` conservan sus tipos JSON reales a través de la plantilla de valor completo.

La fila de la columna where de tabla también vale la pena interiorizar. Los datos de usuario viven en una única columna JSONB \`data\`, y la columna where es el nombre desnudo. Un prefijo \`data.\` inicial se elimina automáticamente tanto en tiempo de construcción como en tiempo de ejecución, y una columna con punto es en cambio rechazada por el sanitizador, por lo que la eliminación es obligatoria y no cosmética (\`CrudRepository.java:369-372\`; \`SqlSanitizer.java:46\`). El nombre reservado \`id\` se mapea a la clave primaria de la fila vía \`id::text\`, no a un campo JSONB.

## Decidir: dónde ocurre realmente la comparación

El nodo 5 es la capa de decisión, y esconde el mecanismo más sorprendente de toda la construcción.

La trampa portable: un filtro que parece numérico puede comparar como texto, y el orden de texto no es el orden de números. En este motor, las cláusulas where de CRUD de tabla comparan todo como texto. Las columnas almacenadas se leen vía \`jsonb_extract_path_text(data, :col)\`, la clave primaria vía \`id::text\`, y el valor enlazado pasa por \`.toString()\` (\`CrudRepository.java:378-416\`). Mientras tanto, la comparación SpEL dentro de una condición de decisión es numérica (\`expressions.md:96\`). Mismo operador \`>\` de aspecto idéntico, dos mundos distintos.

| Dónde corre la comparación | Tipo de comparación | Operadores fiables | Operadores que engañan | Citado en |
|---|---|---|---|---|
| Cláusula where de CRUD de tabla | Textual / lexicográfica | \`=\`, \`!=\`, \`IN\`, \`IS NULL\`, \`IS NOT NULL\`, \`LIKE\` | \`>\`, \`<\`, \`>=\`, \`<=\` | \`CrudRepository.java:378-416\` |
| \`core:decision\` (SpEL) | Numérica | todos los operadores de comparación | ninguno para números | \`expressions.md:96\` |

La consecuencia es un bug latente real. En una cláusula where, \`amount > 9\` excluye \`'100'\`, porque \`'1'\` se ordena antes que \`'9'\`. Y \`id > 5\` omite silenciosamente los ids del 10 al 99 (\`WorkflowBuilderHelpModule.java:258-262\`). Los operadores de ordenación son seguros en una cláusula where solo cuando el orden léxico casualmente coincide con la intención, lo que significa cadenas rellenadas con ceros o fechas ISO en forma \`yyyy-MM-dd\` (\`WorkflowBuilderHelpModule.java:262\`). No hay ningún operador de ordenación con casteo numérico al que recurrir; una comparación consciente de lo numérico es un arreglo conocido pero no liberado al momento de escribir esto.

Así que el cálculo de "¿el precio se movió más del 5%?" pertenece al nodo 6b, un \`core:decision\`, no a la consulta. Necesita el precio previo, que vive en el resultado del \`find_rows\`: \`find_rows\` devuelve \`items[]\`, y cada fila coincidente expone sus campos aplanados, de modo que el precio de línea base está en \`items[0].price\` (\`ConceptsHelpProvider.java:281\`; indexación de arreglos según \`expressions.md:28-32\`). Como el valor almacenado volvió por la misma ruta de texto que cada lectura JSONB, la aritmética debe castearlo: envuelve ambos operandos en \`double()\` antes de restar. La condición:

\`\`\`
{{ (double(core:normalize.output.result.price) - double(table:baseline_lookup.output.items[0].price)) / double(table:baseline_lookup.output.items[0].price) > 0.05 }}
\`\`\`

Una decisión activa exactamente una rama. La primera condición verdadera gana, y el resto pasan a SKIPPED. Sus puertos son \`if\`, \`elseif_N\` y \`else\` (\`nodes.md:29\`; \`WORKFLOW_NODE_TYPES.md:411-418\`).

Una regla estructural ata el grafo entero. Las aristas son simples registros \`{from, to}\` con un sufijo \`:port\` opcional, y las condiciones de rama nunca viven en la arista. Viven en el nodo \`cores[]\`, como \`decisionConditions\` o \`switchCases\` (\`WORKFLOW_NODE_TYPES.md:33-40\`, \`:349-361\`). Dos consecuencias se derivan solo de la topología de aristas. Múltiples aristas sin condición saliendo de una fuente forman un Fork implícito, ejecutando todas las ramas en paralelo. Múltiples aristas entrando en un nodo forman un AND-merge implícito que espera a que cada predecesor se resuelva, ya sea COMPLETED o SKIPPED (\`WORKFLOW_NODE_TYPES.md:1008-1010\`, \`:1053-1056\`, \`:925-940\`).

## La protección de escritura idempotente, dibujada como un subgrafo real

Un trigger que se auto-refresca dispara la misma lectura cada hora. Sin una protección, inserta la línea base del mismo SKU cada hora, y la tabla se llena de duplicados. El patrón general que arregla esto en cualquier motor: busca primero, decide según el conteo, luego escribe solo cuando el elemento es nuevo. Nunca insertes incondicionalmente cuando el mismo elemento puede re-obtenerse.

Este motor no tiene operación de upsert ni de truncate, que es precisamente por lo que la protección es obligatoria y no opcional (\`tables.md:49\`; \`CrudRepository.java\` \`deleteRows\` requiere un where validado).

| Paso | Nodo | Rama / puerto tomado | Efecto en la tabla | Citado en |
|---|---|---|---|---|
| 1 | \`find_rows\` por \`sku\` | (alimenta la decisión) | lee, no escribe nada | \`ConceptsHelpProvider.java:281\` |
| 2 | \`core:decision\` sobre item_count | \`if\` (verdadero) = nunca visto | ninguno aún | \`WorkflowBuilderHelpModule.java:252-254\` |
| 3a | \`insert_row\` (línea base) | en la rama \`if\` | una nueva fila escrita | \`tables.md:52\` |
| 3b | decisión de cambio significativo | en la rama \`else\` | ninguno aún | \`nodes.md:29\` |
| 4 | \`update_row\` (tras la aprobación) | puerto approved | claves JSONB nombradas combinadas | \`tables.md:49\` |

Las dos cadenas exactas de la protección, encerradas para que las plantillas queden enteras:

\`\`\`
find_rows {column:'sku', operator:'=', value:'{{core:normalize.output.result.sku}}'}
\`\`\`

\`\`\`
{{table:baseline_lookup.output.item_count == 0}}
\`\`\`

La sonda que hace que esto funcione es \`find_rows\`, que expone \`items[]\` (las filas encontradas) e \`item_count\` (el conteo). Un \`item_count\` de 0 es la señal de "aún no procesado" que convierte la tabla en memoria compartida entre ejecuciones (\`ConceptsHelpProvider.java:281\`). La protección de buscar-luego-decidir es lo que hace seguro a un flujo de trabajo que se refresca (\`AGENTS.md\` \`dedupe_idempotent_write\`).

La escritura en la ruta del SKU conocido es un \`update_row\`, que requiere tanto un where como un mapa set no vacío, y combina solo las claves JSONB nombradas mediante \`data || jsonb_build_object\` (\`tables.md:49\`). Es una combinación parcial, no un reemplazo, así que no anulará los campos que omitas.

Un detalle de tenant te hará perder una tarde si no lo conoces. La herramienta MCP \`table\` corre bajo el tenant del usuario del chat, no del dueño del flujo de trabajo. Cada consulta CRUD se restringe con \`AND tenant_id = :tenant_id\`, así que la herramienta puede mostrar 0 filas mientras el propio \`find_rows\` del flujo de trabajo ve los datos reales (\`AGENTS.md\`). Para inspeccionar o borrar una tabla propiedad de un flujo de trabajo, ejecuta la operación desde dentro de ese flujo de trabajo, en el tenant correcto.

## Poner la compuerta, luego actuar

El nodo 7 es la verificación humana antes del paso irreversible. El principio general: pon una compuerta bloqueante antes de cualquier acción que no puedas deshacer, y hazla determinista respecto a lo que ocurre a continuación.

En este motor la compuerta es una señal \`USER_APPROVAL\`. El nodo cede con AWAITING_SIGNAL y la ejecución se pausa. USER_APPROVAL siempre bloquea, a diferencia de una señal de interfaz, que bloquea solo cuando se mapea \`__continue\` (\`EXECUTION_ENGINE.md:15\`; \`INTERFACE_NODE_GUIDE.md:783-787\`). El nodo tiene tres puertos de reanudación nombrados, \`approved\`, \`rejected\` y \`timeout\`, y enruta de forma determinista según la decisión tomada (\`nodes.md:39\`; \`WorkflowHelpProvider.java:665\`). El tiempo de espera predeterminado es de 24 horas cuando no se fija (\`nodes.md:39\`).

Como un refresco se dispara cada hora, importan dos preguntas. Primero, ¿qué pasa si la aprobación se dispara dos veces? Nada malo. La resolución es reclamar-antes-de-procesar: \`resolveSignal()\` devuelve false ante una señal ya resuelta, así que una aprobación re-disparada nunca avanza el DAG dos veces (\`INTERFACE_NODE_GUIDE.md:1008\`). Segundo, ¿qué pasa con el siguiente disparo programado mientras un humano se demora sobre la decisión? Cada disparo abre una nueva época, los resultados de la época previa persisten y siguen siendo navegables, y una señal bloqueante difiere el reinicio del ciclo de trigger hasta que se resuelva (\`EXECUTION_ENGINE.md:15\`). El refresco no atropella una decisión pendiente.

En el puerto \`approved\`, se dispara la acción real. Puede ser un nodo Send Email de primera clase o cualquier integración \`mcp:\` conectada (\`nodes.md:62\`), seguido del \`update_row\` protegido. En los puertos \`rejected\` y \`timeout\`, no se escribe nada y no se envía nada.

## Prueba cada rama antes de llamarla en vivo

La regla de prueba no es negociable: ejercita cada rama contra un orquestador en vivo y sigue en paralelo el log del servicio. Una respuesta verde con un stacktrace en el log es un fallo, no un aprobado (\`AGENTS.md\` paso 4 del Feature Development Flow). "Devolvió 200" no es evidencia de que la rama funcionó.

| Escenario | Condición de disparo | Rama / señal esperada | Aserción de aprobado | Señal de fallo |
|---|---|---|---|---|
| Inserción de SKU nuevo | SKU sin fila de línea base | rama \`if\`, \`insert_row\` | exactamente una fila insertada | fila duplicada, o stacktrace en el log |
| Sin cambio | SKU conocido, precio dentro del 5% | decisión de cambio \`else\` | sin marca, sin aprobación, sin alerta | cualquier alerta o pausa |
| Cambio significativo | SKU conocido, movimiento por encima del 5% | la ejecución SE PAUSA en AWAITING_SIGNAL | estado AWAITING_SIGNAL USER_APPROVAL | la ejecución termina sin pausar |
| Puertos de aprobación | Resuelve cada uno de los tres puertos | approved / rejected / timeout | approved escribe + alerta; los otros no hacen ninguna | escritura en rejected/timeout |
| Idempotencia en re-ejecución | Dispara el schedule dos veces | la protección bloquea la segunda inserción | conteo de filas estable | el conteo de filas crece |

Ejecuta los cinco antes de confiar en el grafo. El escenario de cambio significativo debería pausar de forma visible; si termina, tu cálculo de umbral está en la capa equivocada, probablemente una cláusula where lexicográfica que finge ser numérica.

Tres lecciones se trasladan a cualquier motor sobre el que construyas después. Anidamiento de salida: profundiza hasta \`{{core:normalize.output.result.sku}}\`, nunca \`{{core:normalize.output.sku}}\`, porque las plataformas envuelven lo que devuelves. Comparación textual: calcula el movimiento del 5% en un \`core:decision\`, no en la cláusula where de \`find_rows\`, porque esa comparación es léxica. Objetos convertidos en cadenas: construye valores tipados en un nodo \`core:code\`, no en un \`core:transform\` que los aplana a cadenas. Y la protección de buscar-luego-decidir es el patrón que hace seguro en cualquier lugar a un flujo de trabajo que se auto-refresca, porque un schedule que actúa es solo tan confiable como su defensa contra repetirse a sí mismo.
`;

export default content;
