// cap-ai-agent-cost-budgets - es
// Translated from the English body; the structure must stay identical to it.
// Formulas and code samples are fenced on purpose: an inline code span over
// ~45 chars overflows the page on a phone. The hedges are load-bearing here.
const content = `## Una alerta no es un techo

Un monitor es asíncrono y a posteriori: te dice lo que ya gastaste, así que no puede ser la capa de aplicación. Un techo es síncrono y previo a la ejecución: rechaza la siguiente llamada. La reconciliación y la telemetría de motivos de parada siguen importando, pero para dimensionar el límite y detectar uno demasiado ajustado, no para detener el trabajo.

Esta es la prueba que hay que hacer antes de seguir leyendo, y no necesita ningún umbral: extrae los registros de denegación de tu límite configurado durante la última ventana de observación. ¿Ha rechazado algo alguna vez? Un número que nunca ha denegado nada no es un control, es un comentario.

Los límites a nivel de proveedor son redes de seguridad, no aplicación de primera línea:

- El límite de gasto de proyecto y organización de OpenAI es un **presupuesto blando por defecto**: notifica y las solicitudes siguen fluyendo. Existe un techo duro, pero como una opción separada que hay que activar, que entonces devuelve HTTP 429 hasta que se eleva el límite o se reinicia ([guía de límites de gasto](https://developers.openai.com/api/docs/guides/spend-limits)).
- La [Spend Limits API](https://platform.claude.com/docs/en/manage-claude/spend-limits-api) de Anthropic es exclusiva de Claude Enterprise, explícitamente no disponible para organizaciones de Claude Platform (Console), y admite \`monthly\` como único período (con reinicio a las 00 UTC del primer día del mes natural). Limita el uso de los asientos humanos, no el gasto de API de los agentes.
- La documentación de Anthropic también descalifica el gasto del proveedor como barrera: \`period_to_date_spend\` "puede leerse como '0' si la lectura del gasto no está disponible temporalmente; trátalo como informativo, no transaccional."
- Anthropic sí aplica un tope mensual por nivel de uso (Start $500, Build $1,000, Scale $200,000, Custom sin tope) que pausa el uso de la API hasta el mes siguiente ([límites de tasa](https://platform.claude.com/docs/en/api/rate-limits)). Un techo real, pero de toda la organización y mensual: una sola ejecución descontrolada puede consumirlo y convertir un fallo de coste en una caída de toda la organización.

El coste por paso crece de forma superlineal porque el contexto se acumula, y por eso contar pasos no acota dólares. Esa derivación vive en el artículo complementario sobre el modelo de costes. Solo como entradas de dimensionamiento, Anthropic informa de que los agentes usan aproximadamente 4x los tokens del chat y los sistemas multiagente aproximadamente 15x ([multi-agent research system](https://www.anthropic.com/engineering/multi-agent-research-system)).

**Divulgación.** Los detalles de implementación, constantes y mensajes de denegación de más abajo proceden del \`agent-service\` de LiveContext, la plataforma a la que pertenece este blog. Léelos como las decisiones de un solo sistema, verificables en su código fuente de edición comunitaria, no como práctica de campo contrastada.

## Las cinco partes de un objeto presupuesto

Un presupuesto no es un número. Es un objeto con cinco partes, y un presupuesto al que le falte cualquiera de ellas falla de una forma específica y diagnosticable.

**1. Alcance.** El nivel en el que se lleva el libro mayor. Existen cuatro en este sistema ya en producción: saldo de inquilino/cuenta (macro), agente/paso (micro), \`parent_reservation\` (un ancestro en la cadena de llamadas se niega a financiar la creación de un hijo) y por ejecución/por época. Una denegación que no nombra qué alcance se disparó es indepurable.

**2. Unidad.** Dólares, tokens o meros recuentos (turnos, superpasos, llamadas a herramientas). Los recuentos flotan en términos monetarios. Solo tokens o dinero constituyen un presupuesto.

**3. Punto de aplicación.** Reconciliación a posteriori, proyección previa a la iteración, reserva previa a la creación o tope de admisión sobre las entradas. Cada uno tiene una cota de exceso distinta (Tabla 1).

**4. Política de reserva.** Si el presupuesto se decrementa después del hecho o se retiene antes de que empiece el trabajo. Esta es la única parte que hace segura la expansión paralela.

**5. Respuesta terminal.** Lo que recibe quien llama en el instante en que se alcanza el tope. Existen cinco comportamientos distintos en la práctica y no son intercambiables.

**Tabla 1: Puntos de aplicación y su cota de exceso**

| Punto de aplicación | Cuándo se ejecuta | Qué puede rechazar | Exceso en el peor caso | ¿Seguro para expansión paralela? |
|---|---|---|---|---|
| Reconciliación / alertas a posteriori | Después de que la llamada se liquida | Nada | Sin cota | No (detección, no aplicación) |
| Proyección previa a la iteración | Antes de la siguiente llamada al modelo | La siguiente iteración | Una iteración (hasta 40x la primera iteración en un paso de navegador) | No |
| Reserva previa a la creación | Antes de que arranque un hijo | El hijo entero | Cero para el hijo | Sí |
| Tope de admisión sobre las entradas | Antes de ensamblar el prompt | Contexto / salida sobredimensionados | Acota la iteración en sí | Sí (se compone con los demás) |

Dos decisiones de diseño que la gente trata como configuración pero que pertenecen al objeto:

**El orden de los guardas es diseño de alcance.** Esta implementación ejecuta exactamente dos guardas, \`TenantBudgetGuard\` y luego \`AgentBudgetGuard\`, donde gana la primera denegación y se cortocircuita el resto, por dos razones documentadas: el agotamiento del inquilino vuelve irrelevante el presupuesto del agente, y el guarda de inquilino se coloca primero como rechazo temprano antes del viaje de ida y vuelta de reserva de créditos aguas abajo.

**El período es una decisión de dimensionamiento.** Un acumulador acumulativo convierte el tope en un total vitalicio, de modo que un agente de larga vida se acerca en silencio al agotamiento a lo largo de meses. Los reinicios semanales o mensuales convierten ese mismo número en una tasa. Los reinicios pueden resolverse de forma perezosa al inicio de la ejecución con una actualización compare-and-set en lugar de mediante un planificador (modos de \`BudgetResolver\`: cumulative, weekly, monthly; los valores desconocidos se tratan como cumulative).

Una trampa semántica que conviene comprobar en tu propia pila: la ayuda de parámetros de herramienta orientada al agente de esta plataforma sigue diciendo "Each LLM iteration costs 1 credit" mientras que el guarda compara una proyección monetaria contra ese mismo campo \`credit_budget\`. Otras dos cadenas de ayuda lo matizan como "at least one credit" y "more than 1 credit in practice", así que la documentación también se contradice a sí misma. Una regla general en la documentación y una comparación monetaria en el código son una clase de fallo, no un detalle de redacción.

## No puedes detener la llamada que ya estás haciendo

El consumo de tokens solo se conoce una vez que la llamada se completa. Ningún presupuesto en ejecución puede impedir que una sola llamada cara reviente el tope; solo puede impedir la siguiente. El peor caso realizado es, por tanto, **el presupuesto más una iteración**, no el presupuesto. Dilo claramente en lugar de dar a entender un tope duro.

La fórmula de control tal como aparece en el fichero compartido de fixtures multilenguaje:

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

Dos salvedades antes de copiarla. Los dos guardas de Java implementan \`>=\` en la comparación de la proyección, no \`>\`; su gemelo en JS implementa \`>\`. Con igualdad exacta del total proyectado discrepan, y ningún caso del fixture se sitúa en esa frontera. Y la comparación de alcance de agente no tiene dos términos sino cuatro:

\`\`\`
totalProjected = consumedBeforeRun
               + creditsReserved
               + runCostSoFar
               + nextProjected
deny iff totalProjected >= totalBudget
\`\`\`

\`creditsReserved\` son los créditos actualmente bloqueados por subagentes en vuelo, de modo que el bucle propio de un padre queda limitado por lo que retienen sus hijos.

Cada rama de proyección es no redundante:

- \`growthProj\` (media de tokens por iteración completada) capta una subida sostenida.
- \`lastDeltaProj\` (delta de la última iteración por 2) capta una ráfaga que una media diluye.
- \`worstCaseSingleIter\` (ventana de contexto completa por salida máxima completa a las tarifas del modelo) es invariante al patrón de crecimiento y capta un salto en escalón en la iteración 1.

La rama del peor caso hace el trabajo de verdad. Con precios de clase opus (15 / 75 USD por 1M) con un contexto de 200K y 64K de salida máxima:

\`\`\`
worstCaseSingleIter = 200 * 15 + 64 * 75
                    = 3,000 + 4,800
                    = 7,800 credits      (1 credit = $0.001)
\`\`\`

Cualquier saldo por debajo de 7,800 créditos está protegido frente a esa iteración de ráfaga por la rama del peor caso y por nada más.

La segunda condición de denegación, \`runCostSoFar >= balance\`, es lógicamente redundante: siempre que la proyección sea positiva, la primera condición ya la cubre. Existe únicamente para que la denegación nombre el modo de fallo real en lugar de aparecer como un exceso de proyección.

La fórmula de coste, para reproducibilidad:

\`\`\`
inputCost  = inputRate  * promptTokens     / 1000
outputCost = outputRate * completionTokens / 1000
total      = inputCost + outputCost + fixedCost
\`\`\`

Las tarifas son USD por 1M de tokens; el \`/1000\` convierte a una unidad de crédito donde 1 crédito = $0.001. Redondea cada subtérmino a 6 decimales antes de sumar, o dos implementaciones de la misma fórmula divergirán.

Tres restricciones honestas sobre este mecanismo:

**El guarda por agente necesita dos iteraciones completadas.** Con una sola muestra, \`lastDelta == runCost == growth\`, así que \`lastDelta * 2 = 2 * runCost\`, y cualquier primera iteración que consuma más de \`budget/3\` se autodenegaría la iteración 2 incluso cuando la siguiente llamada sea legítimamente pequeña. El guarda de inquilino no tiene esa condición: proyecta desde la iteración 1, donde growth y lastDelta son ambos cero, así que allí solo vincula la rama del peor caso. Eso es por diseño, y es la razón por la que el techo de la iteración 1 pertenece a la rama del peor caso.

**La obsolescencia agrava la brecha.** Un saldo re-consultado cada 5 iteraciones (bajando a cada iteración cuando las tarifas de coste no son fiables) añade una ventana de obsolescencia encima de la brecha de proyección de una iteración. Una variante adaptativa refresca en cada iteración una vez que la tasa de consumo supera el 70% del saldo.

**El repliegue para modelos desconocidos es una decisión real con historial de fallos.** Falla de forma pesimista en las tarifas (repliega a la banda más alta, 15 / 75 USD por 1M) pero indulgente en el techo (deja la ventana de contexto nula, de modo que \`worstCase\` devuelve null y el guarda cae a solo crecimiento). Un repliegue anterior de 0.015 / 0.075 saltaba el guarda por completo en silencio.

Los propios comentarios del guarda llevan la admisión: se prototipó y se revirtió una capa de reserva atómica por turno, porque un exceso de como mucho una iteración se juzgó aceptable a cambio de una ruta de llamada más simple. Y la comprobación previa es explícitamente "a snapshot, not authoritative": la reconciliación posterior a la ejecución sigue ejecutándose, y ambas pueden discrepar.

## El momento del impacto: lo que realmente recibe quien llama

Una parada por presupuesto se clasifica como \`PARTIAL\`, no \`FAILURE\`, en el contrato de motivos de parada de esta plataforma: salida utilizable pero truncada. No lanza excepción, y una parada por presupuesto que ha producido tokens se persiste con estado de ejecución \`COMPLETED\`, así que solo la columna \`stop_reason\` lleva el detalle. Dos matizaciones, porque las medias verdades aquí son la forma en que un tope demasiado ajustado permanece invisible: una parada por presupuesto con cero tokens se persiste como \`FAILED\`, y el agregado diario de métricas sí cuenta cada ejecución detenida por presupuesto en su recuento de fallos. Lo que es genuinamente invisible es la forma del daño, no el hecho de que exista. Si solo vigilas las tasas de error, un tope demasiado ajustado aflora meses después como una regresión de calidad.

**Tabla 2: Dónde se decide cada motivo de parada (6 de los 10 valores del contrato)**

| Motivo de parada | Categoría terminal | Dónde se decide | Qué debe hacer quien llama |
|---|---|---|---|
| \`MAX_ITERATIONS\` | partial | A posteriori, tras salir del bucle | Tratar la salida como truncada; subir n o el presupuesto |
| \`TIMEOUT\` | partial | A posteriori, tras salir del bucle | Trabajando activamente, pasado el tiempo de reloj; reanudar o ampliar |
| \`BUDGET_EXHAUSTED\` | partial | Guarda previo a la iteración, antes de la llamada | Leer \`budgetScope\` (\`tenant\`, \`agent\`, \`parent_reservation\`, \`browser\`), decidir recarga o redimensionado |
| \`LOOP_DETECTED\` | partial | A mitad de iteración, tras parsear las llamadas a herramientas | Inspeccionar la firma repetida; la tarea está malformada |
| \`STOPPED_BY_USER\` | partial | Canal de cancelación | Conservar la salida parcial |
| \`INACTIVITY_TIMEOUT\` | failure | Watchdog, no el bucle; una pasada posterior reclasifica \`STOPPED_BY_USER\` | Se quedó en silencio, hubo que matarlo; investigar el bloqueo |

\`BUDGET_EXHAUSTED\` es el único valor que lleva un array de alcances. Una parada por presupuesto que no te dice qué techo se disparó te obliga a adivinar.

La denegación no debería ser una excepción. Una implementación viable sale del bucle y registra metadatos estructurados: el motivo de parada, más \`budgetScope\`, más una cadena \`denialReason\` que nombra qué rama de proyección se disparó:

\`\`\`
tenant balance X would be exceeded
(run=A + next=B [growth=..., lastDelta=..., worstCase=...])
\`\`\`

Usa las mismas claves en las rutas síncrona y de streaming para que las métricas no puedan divergir.

En el conjunto del campo estudiado existen cinco comportamientos terminales y no son intercambiables:

1. **Excepción**: \`MaxTurnsExceeded\` (OpenAI Agents SDK), \`GraphRecursionError\` (LangGraph), \`UsageLimitExceeded\` (Pydantic AI), \`ModelCallLimitExceededError\` (LangChain).
2. **Resultado tipado y ramificable**: el \`stop_reason\` de AutoGen en el \`TaskResult\`, el subtipo \`error_max_budget_usd\` del Claude Agent SDK, el \`exit_behavior='end'\` de LangChain con un mensaje de IA inyectado.
3. **Truncamiento silencioso con HTTP 200**: el \`max_tokens\` de Anthropic fija \`stop_reason: "max_tokens"\` y devuelve éxito ([Messages API](https://platform.claude.com/docs/en/api/messages)).
4. **Rechazo HTTP 429**: el límite duro opcional de OpenAI. Anthropic documenta el 429 solo para \`rate_limit_error\` y sitúa los problemas de facturación en 402, así que no hay ningún código de estado documentado para su tope mensual de gasto por nivel; confírmalo contra tus propios registros.
5. **Respuesta degradada de mejor esfuerzo**: el \`max_iter\` de CrewAI, donde el agente "must provide its best answer" ([agentes de CrewAI](https://docs.crewai.com/en/concepts/agents)).

Un conflicto de semántica que conviene comprobar en tu propia pila: [los presupuestos de iteración de LiteLLM](https://docs.litellm.ai/docs/a2a_iteration_budgets) devuelven 429 con tipo de error \`budget_exceeded\`, y por convención HTTP el 429 significa reintentar más tarde. Para un tope de organización que se reinicia con el tiempo eso es defendible, ya que esperar acaba haciendo la solicitud satisfacible. Para un presupuesto por ejecución o por agente es incorrecto: esperar nunca lo satisface, y la lógica de reintento estándar de los SDK golpeará el muro. LiteLLM es la única instancia confirmada aquí, no una clase demostrada. Comprueba qué hace la política de reintentos de tu cliente con un 429.

Lo que debería sobrevivir a la parada es la otra mitad del contrato. El [Claude Agent SDK](https://code.claude.com/docs/en/agent-sdk/agent-loop) es lo más parecido a un diseño de referencia: el campo \`result\` (la respuesta final) solo está presente en el subtipo \`success\`, pero todos los subtipos de error siguen llevando \`total_cost_usd\`, \`usage\`, \`num_turns\` y \`session_id\`. Pierdes la respuesta, no la sesión. Fíjate en la asimetría: una \`query()\` de un solo disparo lanza tras emitir el resultado de error, mientras que una sesión de entrada por streaming sigue viva.

Por qué esto importa comercialmente, según un [informe de incidente](https://github.com/anthropics/claude-code/issues/68430): las únicas opciones del operador eran "let it run and watch it burn the session budget on a recursive loop that will never succeed" o "kill it and lose everything, including legitimate work completed by early agents." Un tope que descarta el trabajo parcial convierte un problema de coste en un problema de pérdida total, que es precisamente por lo que los operadores desactivan los topes.

Una negativa por parte del padre debería seguir la misma regla: no un error lanzado sino un resultado de fallo sintetizado que nombre al ancestro y el alcance.

\`\`\`
Cannot spawn child 'X': ancestor agent <id> has
insufficient free budget for reservation N
(scope=parent_reservation, BUDGET_EXHAUSTED)
\`\`\`

Por último, haz que el tope sea introspeccionable desde dentro del agente. La forma de respuesta ya en producción:

\`\`\`
budget.{ unlimited, total, consumed,
         consumed_own, consumed_from_subagents,
         reserved_for_subagents, free,
         reset_mode, last_reset }

free = max(total - consumed - reserved_for_subagents, 0)
\`\`\`

En la rama unlimited, \`total\` y \`free\` son null y \`reserved_for_subagents\` se devuelve como 0. La regla explícita: si \`free\` está por debajo del presupuesto de un hijo, la creación falla con \`scope=parent_reservation\`.

## Qué puede y qué no puede aplicar cada pila

**Tabla 3: Qué puede aplicar realmente cada pila** (limitado a las plataformas estudiadas; Google ADK y LlamaIndex no lo fueron)

| Pila | Unidad aplicada | Valor por defecto | Comportamiento en el techo | ¿Se propaga a los subagentes? |
|---|---|---|---|---|
| [Claude Agent SDK](https://code.claude.com/docs/en/agent-sdk/python) | USD por ejecución (\`max_budget_usd\`), más turnos | Ambos sin límite | Subtipo de resultado tipado \`error_max_budget_usd\` / \`error_max_turns\`, sesión preservada | \`usage\` excluye los tokens de subagentes; \`total_cost_usd\` los incluye |
| Anthropic Messages API | Tokens (\`max_tokens\`) | Sin valor por defecto; debes fijarlo | HTTP 200 con \`stop_reason: "max_tokens"\`, truncado | N/A |
| OpenAI (cuenta) | USD por mes | Blando por defecto | Notificación, o 429 si se ha optado por el límite duro | N/A |
| [OpenAI Agents SDK](https://openai.github.io/openai-agents-python/running_agents/) | Turnos ([\`DEFAULT_MAX_TURNS = 10\`](https://github.com/openai/openai-agents-python/blob/main/src/agents/run_config.py)) | 10 | Lanza \`MaxTurnsExceeded\` | No documentado |
| [LangGraph](https://docs.langchain.com/oss/python/langgraph/graph-api) | Superpasos (\`recursion_limit\`) | La documentación se contradice: 1000 desde v1.0.6 en el runtime de grafo OSS, 25 en el esquema \`Config\` del SDK y en informes de campo | Lanza \`GraphRecursionError\` | Dos fallos de propagación documentados (más abajo) |
| [Middleware de LangChain](https://reference.langchain.com/python/langchain/agents/middleware/model_call_limit/ModelCallLimitMiddleware) | Solo recuentos de llamadas, sin presupuesto de tokens ni de coste | Ambos límites \`None\` | Configurable: \`exit_behavior='end'\` inyecta un mensaje, \`'error'\` lanza | No aplicable |
| [Pydantic AI](https://pydantic.dev/docs/ai/api/pydantic-ai/usage/) | Tokens, solicitudes, llamadas a herramientas | \`request_limit=50\`, límites de tokens \`None\` | Lanza \`UsageLimitExceeded\`; comprobación previa opcional | No documentado |
| AutoGen ([condiciones](https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.conditions.html), [equipos](https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.teams.html)) | Tokens (\`TokenUsageTermination\`) | Valores por defecto del equipo: \`termination_condition=None\`, \`max_turns=None\` | \`TaskResult\` tipado con una cadena \`stop_reason\` | Ámbito de equipo |
| [CrewAI](https://docs.crewai.com/en/concepts/agents) | Iteraciones (\`max_iter\`) | La documentación dice 20, el código fuente dice 25 | El agente "must provide its best answer" | No documentado |

Cinco cosas que dice esta tabla y que la prosa enterraría:

**Casi todo por defecto es ilimitado.** \`max_turns\` y \`max_budget_usd\` del Claude Agent SDK no tienen límite; [los equipos de AutoGen](https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.teams.html) afirman llanamente que el chat de grupo "will run indefinitely"; los límites de gasto por asiento Enterprise de Anthropic son ilimitados por defecto cuando no existe ningún valor por defecto en ningún nivel (los topes por nivel de la API, en cambio, siempre se aplican).

**El único mando de coste del estudio sin valor por defecto es el \`max_tokens\` de Anthropic**, que el esquema de la Messages API te exige fijar explícitamente. Es también el único cuyo incumplimiento devuelve HTTP 200 con contenido truncado. El esquema ahora también documenta fijarlo a 0 para calentar la caché de prompts, así que obligatorio no significa techo significativo.

**El único techo en dólares por ejecución del estudio se aplica contra una estimación.** La página de seguimiento de costes de Anthropic advierte de que \`total_cost_usd\`, la cifra exacta contra la que se compara \`max_budget_usd\`, consiste en "client-side estimates, not authoritative billing data" calculadas a partir de una tabla de precios empaquetada en tiempo de compilación, y dice "Do not bill end users or trigger financial decisions from these fields." También se evalúa entre turnos, así que el gasto puede exceder el límite configurado en un turno. Esa es la misma garantía de presupuesto más una iteración, en el producto mejor diseñado del campo.

**LangChain no tiene ningún presupuesto de tokens ni de coste.** \`ModelCallLimitMiddleware\` y \`ToolCallLimitMiddleware\` limitan recuentos de llamadas, ambos por defecto a \`None\`, y un mantenedor [confirmó la ausencia de presupuesto de tokens en julio de 2026](https://forum.langchain.com/t/a-proposal-to-add-token-usage-budgets-to-langchain-agents-via-a-new-middleware-since-the-existing-limiters-only-cap-call-count-not-tokens/4147). Su parámetro \`exit_behavior\` es, no obstante, el modo de fallo configurable más limpio del campo y merece copiarse.

**Pydantic AI es la única pila con una comprobación previa al vuelo**: \`count_tokens_before_request\` (por defecto \`False\`) llama a la API de recuento de tokens del proveedor para rechazar una solicitud fuera de presupuesto antes de que se facture. También trae una trampa: \`request_limit\` toma por defecto 50 en silencio, así que fijar solo \`input_tokens_limit\` hereda un tope de 50 solicitudes a menos que pases \`request_limit=None\`.

**La propagación es la forma número uno en que un techo se vuelve decorativo.** Dos casos documentados: [LangChain deepagents #1698](https://github.com/langchain-ai/deepagents/issues/1698), donde \`SubAgentMiddleware\` invocaba subagentes sin el parámetro \`config\` de modo que siempre corrían con el límite de recursión por defecto independientemente de un padre fijado a 150; y [langgraphjs #1524](https://github.com/langchain-ai/langgraphjs/issues/1524), donde el \`recursionLimit\` de \`withConfig\` se ignora en silencio y el mensaje de error resultante te dice que fijes precisamente la clave que se está ignorando.

Dos trampas de medición que derrotan silenciosamente el código de presupuesto ingenuo, ambas del [documento de seguimiento de costes de Anthropic](https://code.claude.com/docs/en/agent-sdk/cost-tracking): el campo \`usage\` cuenta solo el bucle de nivel superior y excluye los tokens de subagentes (mientras que \`total_cost_usd\` y \`model_usage\` los incluyen), y las llamadas a herramientas en paralelo emiten varios mensajes de asistente que comparten un mismo id de mensaje con un uso idéntico, así que un medidor que sume el uso por mensaje cuenta doble y salta antes de tiempo. Deduplica por id.

Los límites de tasa no son límites de gasto y pueden premiar la ruta cara: los tokens de entrada cacheados se facturan al 10% pero no cuentan para los límites de tokens de entrada por minuto en la mayoría de los modelos, y \`max_tokens\` no entra en absoluto en los límites de tokens de salida por minuto ([límites de tasa](https://platform.claude.com/docs/en/api/rate-limits)).

## Los guardas de bucle acotan n; los presupuestos acotan el coste dado n

Un detector de bucles y un presupuesto responden a preguntas distintas. El detector acota cuántas iteraciones ocurren; el presupuesto acota lo que esas iteraciones pueden costar. Ninguno sustituye al otro.

Umbrales reales de un detector ya en producción, con dos condiciones de disparo independientes:

| Condición | Clave | Peldaños de escalado | Parada dura |
|---|---|---|---|
| Llamadas idénticas | nombre de herramienta + argumentos ordenados, hasheados | aviso en 5 | 15 |
| Llamadas consecutivas | total de llamadas a herramientas, cualquier firma | 15, 25, 35 | 40 |

El techo de consecutivas es deliberadamente alto para que no se maten operaciones de lote legítimas. Ambas paradas duras son configurables por agente, y los peldaños intermedios son **derivados** (aviso de idénticas = \`ceil(stop/3)\` mín. 2; peldaños de consecutivas = \`ceil(stop * 3/8)\`, \`5/8\`, \`7/8\`) para que la escalera de severidad siga siendo monótona con cualquier valor personalizado, con paradas mínimas impuestas.

La escalera no es solo registro: cada peldaño inyecta un mensaje de vuelta al contexto del agente antes de la parada, escalando desde una nota informativa pasando por "1 iteration left, STOP tools, RESPOND NOW" hasta la terminación. La intención de diseño declarada es que los patrones repetitivos deberían automatizarse como flujos de trabajo en lugar de ejecutarse en bucle.

La laguna de cobertura que conviene nombrar: ese detector solo cuenta cuatro nombres de herramienta. Cualquier otra llamada a herramienta es invisible para ambos contadores, así que un bucle sobre una herramienta no rastreada nunca produce \`LOOP_DETECTED\`. Comprueba la cobertura equivalente en tu propia pila antes de confiar en un guarda de bucles.

No confíes en que el modelo detecte su propio desperdicio. RedundancyBench anotó 200 trayectorias (filtradas de las ejecuciones exitosas recopiladas) con más de 8,000 pasos anotados, y la mejor detección automática de pasos redundantes a nivel de paso obtuvo un 24.88% (72.50% a nivel de trayectoria) ([arXiv 2605.29893](https://arxiv.org/abs/2605.29893)). El tope tiene que ser mecánico.

Otros valores por defecto de acotación de ejecución de la misma implementación, como punto de referencia: máximo de iteraciones 100, tiempo límite de ejecución 3600 s, máximo de tokens 16,000 por turno, y un watchdog de inactividad de 5 minutos cuya sobrescritura por agente solo acepta 0 (desactivado) o de 10 a 7200 segundos, de modo que un valor errante no pueda armar un watchdog en la escala de segundos.

El tiempo de reloj merece una línea como tope de último recurso. Un incidente documentado consumió 4 millones de tokens en menos de 5 minutos ([claude-code #68619](https://github.com/anthropics/claude-code/issues/68619)), más rápido de lo que reaccionaría cualquier muestreo por turno o de refresco de saldo. Eso es una inferencia a partir de un solo incidente, no una buena práctica con fuente, pero la aritmética es difícil de rebatir.

## La prueba de un techo real

Seis puntos, cada uno respondible desde tus propios registros:

1. ¿Una denegación nombra el alcance que se disparó?
2. ¿La comprobación es síncrona y anterior a la siguiente llamada?
3. ¿La respuesta terminal es tipada, no reintentable, y lleva el libro mayor de costes más un identificador para reanudar?
4. ¿El tope se propaga a los subagentes, demostrado por una prueba que fija un límite en el padre y comprueba que un hijo lo hereda?
5. ¿Es la razón de granularidad \`g\`, el presupuesto dividido por la iteración acotada del peor caso, de al menos 3? El artículo complementario sobre dimensionamiento deriva ese suelo y muestra que la mayoría de los topes monetarios por paso no lo cumplen.
6. ¿Ha denegado realmente el tope alguna vez, en la ventana observada?

La garantía honesta: un presupuesto en ejecución acota el coste a **el presupuesto más una iteración**, no al presupuesto. Una reserva previa a la creación es el único mecanismo con exceso cero, y solo cubre al hijo.

Si la misma fórmula existe en dos runtimes, la paridad merece ingeniería. Un fichero compartido de fixtures con casos nombrados, consumido tanto por una prueba parametrizada de JUnit como por un ejecutor de pruebas de Node, es la forma más barata de evitar que ambas diverjan, y el redondeo debe coincidir subtérmino a subtérmino. Fíjate en el límite: un fixture solo cubre los casos que contiene. Un fixture que precarga tarifas explícitas nunca ejercita la ruta de repliegue para modelos desconocidos en ninguno de los dos lados, que es exactamente donde las dos implementaciones descritas aquí divergieron en un orden de magnitud, y un fixture que instancia solo el guarda de inquilino nunca se percata de que los dos guardas de agente usan operadores de comparación distintos.

Declara lo que no se sabe. No existe ninguna tasa base publicada sobre con qué frecuencia se descontrolan los agentes en producción. El catálogo más sólido descarta explícitamente la prevalencia y solo reivindica existencia y recurrencia a través de proyectos desarrollados de forma independiente. Razona a partir del mecanismo y la magnitud en lugar de inventarte una frecuencia.

Y sé realista sobre la magnitud. Según las filas de incidentes del mismo catálogo de 2026 ([arXiv 2606.04056](https://arxiv.org/abs/2606.04056)), los sobrecostes documentados se agrupan entre los cientos y los pocos miles de dólares: alrededor de $2,150 de gasto no intencionado en un caso, $235 en cuatro días por un solo usuario, un exceso del 70% sobre el presupuesto de un optimizador. Compara eso con la anécdota de descontrol más republicada del campo, ["We spent $47,000 running AI agents"](https://todatabeyond.substack.com/p/we-spent-47000-running-ai-agents), que no nombra ninguna empresa, no aporta ninguna factura, repositorio, configuración ni registros, y que luego se amplificó bajo una segunda firma y a través de una docena de artículos SEO que se citan entre sí. Sus propias cifras semanales son $127, $891, $6,240 y $18,400, que suman $25,658, no $47,000, y una escalada de coste de cuatro semanas contradice el "11-day loop" del mismo artículo. El perfil de riesgo real es silencioso, recurrente y de un orden de cuatro cifras medias.
`;

export default content;
