// ai-agent-audit-trail - es
// Translated from the English body; structure identical to it. Legal scope
// statements and every "at least 6 months"/"out of scope"/"not legal advice" are
// load-bearing and must not be strengthened or softened. Fenced code stays fenced.
const content = `Reviewing against the source, the translation is accurate throughout; the one defect is a gender-agreement error ("el único forma" → "la única forma"). Everything else (counts, tables, code fences, legal force, untranslatables, dashes) is intact. Corrected body:

An audit trail no es un log más largo. Es un artefacto distinto, con un lector distinto, un contrato de escritura distinto y un reloj distinto. Este texto publica un esquema copiable a nivel de ejecución (run) y a nivel de paso (step) en el que cada campo carga cuatro cosas a la vez: su tipo de dato, su clase de cardinalidad, si puede contener datos personales y la razón por la que existe. Un artículo complementario hace la aritmética de almacenamiento que convierte el escalonado de retención en una decisión derivada, mapea las obligaciones legales reales y gestiona la solicitud de supresión que choca con un trail conservado durante años.

La implementación de referencia citada en todo el texto es la propia plataforma de este blog. Nombres de columna reales, migraciones reales, bugs reales.

## El lector para el que escribes no eres tú, y no es ahora

Un dashboard lo lee su autor, a los pocos minutos, con el incidente todavía en la memoria de trabajo. Un trail lo lee un tercero indiferente u hostil, meses después, que no puede hacer una pregunta de seguimiento. Esa diferencia genera cada decisión de abajo.

Se siguen dos invariantes, y casi nadie las escribe:

1. **Los registros de auditoría nunca se muestrean.**
2. **Los campos de contenido nunca se degradan dentro de su ventana de retención.**

El resto es criterio de diseño, y el coste de almacenamiento de ese criterio es aritmética.

Digamos lo incómodo de entrada: **ningún instrumento especifica este esquema.** Fuera del EU AI Act Art. 12(3), que se aplica a exactamente un subpunto del Annex III (punto 1(a), identificación biométrica remota, y no a la verificación biométrica), nada de lo revisado aquí (el AI Act, ISO/IEC 42001, NIST AI RMF, SOC 2) especifica un esquema de log, tipos de campo, límites de cardinalidad o una estrategia de muestreo. El esquema de abajo es criterio de ingeniería orientado a satisfacer los *fines* que la ley nombra en el Art. 12(2)(a) a (c) y el derecho a la explicabilidad del Art. 86. No es un artefacto de cumplimiento y no lo voy a vender como tal.

Cada campo de un trail utilizable carga cuatro cosas a la vez: su tipo de dato y nulabilidad, la pregunta u obligación que lo fuerza, su clase de cardinalidad y su clase de retención incluyendo si puede muestrearse o degradarse. Ninguna fuente publicada cubre las cuatro esquinas. [Las convenciones GenAI de OpenTelemetry](https://github.com/open-telemetry/semantic-conventions-genai) tienen tipos pero ninguna obligación y ningún contenido por defecto; [el audit trail mínimo viable de ARMO](https://www.armosec.io/blog/minimum-viable-audit-trail/) tiene obligaciones y nombres de campo pero ningún tipo; el conjunto del AI Act tiene la ley y admite que no especifica ningún campo.

Dos notas contra el solapamiento. El trail es **lineal en pasos, no cuadrático**: pagas al modelo por reenviar el contexto acumulado en cada turno, pero almacenas cada mensaje una sola vez, así que una ejecución de seis pasos son ~27 filas independientemente del crecimiento del contexto (el lado cuadrático pertenece al artículo del modelo de coste). Y \`stop_reason\` y \`terminal_category\` aparecen aquí solo como campos a registrar; la taxonomía y el comportamiento del tope (cap) pertenecen al artículo sobre imposición de presupuesto.

## An observability dashboard is not an audit trail

La confusión se divide limpiamente por el título del artículo: las piezas tituladas de observabilidad venden las trazas como el audit trail; las tituladas de auditoría rara vez mencionan que el esquema estándar no registra ningún contenido por defecto.

Ese default es el hallazgo titular. Las convenciones semánticas GenAI hacen que los prompts, las completions, las instrucciones de sistema, los argumentos de herramienta y los resultados de herramienta tengan todos el nivel de requisito \`Opt-In\`, y la posición de la especificación es que las instrumentaciones "SHOULD NOT capture them by default", siendo la opción 1 "[Default] Don't record instructions, inputs, or outputs." Así que "tenemos trazado OTel, por lo tanto tenemos un audit trail" es falso de fábrica: lo que tienes es modelo, recuentos de tokens, latencia y finish reason, nada del material que reconstruye una decisión.

Activarlo es más difícil de lo que parece. En [opentelemetry-python-contrib](https://github.com/open-telemetry/opentelemetry-python-contrib/blob/main/util/opentelemetry-util-genai/src/opentelemetry/util/genai/utils.py), el interruptor de captura no es un booleano:

\`\`\`
OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT
  = NO_CONTENT | SPAN_ONLY | EVENT_ONLY | SPAN_AND_EVENT
  unset            -> NO_CONTENT
  invalid value    -> warning, then NO_CONTENT

# second gate, barely documented:
OTEL_SEMCONV_STABILITY_OPT_IN must select the GenAI
experimental mode, or get_content_capturing_mode() raises.
\`\`\`

Poner solo la variable de captura no basta. (Verificado únicamente para los paquetes contrib de Python; otros SDK de lenguaje pueden diferir en el nombre del flag, los valores de enum, o en si la segunda barrera existe siquiera.)

Mientras tanto, el consejo habitual de observabilidad es fatal para la auditoría de dos maneras independientes. Para funciones de alto volumen por encima de ~1,000 requests/second, [reduce el muestreo del sobre de llamada al 10-20% y reserva la captura completa a nivel de token para sesiones de depuración explícitas](https://www.braintrust.dev/articles/llm-call-observability); y [limpia o enmascara el contenido antes de que llegue al backend](https://mlflow.org/articles/setting-up-llm-observability-pipelines-in-2026/). Una muestra del diez por ciento es inútil cuando la decisión que debes defender está en el noventa por ciento que descartaste.

| Dimensión | Dashboard de observabilidad | Audit trail |
|---|---|---|
| Consumidor | El autor, minutos después | Un tercero indiferente u hostil, meses después |
| Latencia de lectura | Segundos a horas | Meses a años |
| Muestreo | Esperado (10-20%, o basado en cola) | Prohibido |
| Contenido por defecto | Desactivado (el contenido GenAI de OTel es Opt-In) | Activado, dentro de su ventana de retención |
| Contrato de escritura | Fire-and-forget, fallo registrado | Misma transacción, el fallo hace fallar la operación |
| Fuente de ordenación | Timestamps, remuestreados | Secuencia asignada por el escritor |
| Mutabilidad | Mutable por diseño (reprocesado, campos descartados al actualizar el backend) | Append-only, idealmente encadenado por hash |
| Motor de retención | Cuánto sigue siendo interesante una regresión (días) | Una obligación o un horizonte de disputa (meses a años) |
| Modo de fallo | Depuras más lento | No puedes responder la pregunta |

El contrato de escritura es lo más barato de equivocar. Esta plataforma sostiene ambas posturas, cada una correcta para su artefacto. La escritura de observabilidad del agente es un HTTP POST fire-and-forget (\`AgentClient.recordObservability\`) cuyo fallo se captura y registra en WARN como "non-critical": la ejecución igual factura y retorna, la fila de auditoría simplemente se pierde. La auditoría de feature-flag (\`V173__flag_flip_audit.sql\`) declara el contrato opuesto en la cabecera de su migración: misma transacción, sin \`REQUIRES_NEW\`, sin async, sin listener \`AFTER_COMMIT\` (eso competiría con un kill de la JVM), y si el insert de auditoría lanza, el flag no se cambia.

La consecuencia de la elección best-effort es el modo de fallo que parece correcto hasta que lo necesitas: **la cobertura del trail queda correlacionada con la salud del sistema**, así que se adelgaza precisamente durante los incidentes que te pedirán explicar.

## El esquema a nivel de ejecución

Una fila por ejecución. Esta es la cabecera que un auditor lee primero.

| Campo | Tipo | Null | Cardinalidad | Datos personales | Por qué existe |
|---|---|---|---|---|---|
| \`run_id\` | uuid | no | alta | no | Clave de join para toda fila hija. Acuñar en el **dispatch**, no en el INSERT. |
| \`trail_seq\` | bigint (secuencia dedicada) | no | alta | no | Ordenación que sobrevive al desfase de reloj y a escrituras del mismo milisegundo. |
| \`prev_row_hmac\` | bytea(32) | sí | alta | no | Evidencia de manipulación: cubre el propio contenido más el HMAC de la fila anterior. |
| \`tenant_id\`, \`organization_id\` | text / uuid | no | media | indirecto | Clave de alcance para supresión y control de acceso. |
| \`actor_subject_ref\` | text (token pseudónimo) | sí | alta | **sí** | "Quién lo pidió." Se resuelve a identidad solo mediante un mapeo guardado por separado. |
| \`parent_run_id\` | uuid | sí | alta | no | Qué ejecución engendró esta. |
| \`caller_agent_id\` | uuid | sí | media | no | Qué agente la engendró. |
| \`depth\` | int2 | no | baja | no | Detección de ciclos y ordenación del árbol. |
| \`caller_tool_call_id\` | text | sí | alta | no | La llamada exacta en el padre que engendró al hijo. |
| \`trigger_source\` | enum | no | **baja** | no | manual / chat / webhook / schedule / datasource / workflow / error. Decide si un humano es responsable de que la ejecución exista. |
| \`started_at\`, \`ended_at\` | timestamptz | no / sí | alta | no | Dos timestamps, no uno más una duración. |
| \`status\` | enum | no | baja | no | La afirmación que te pedirán defender: ¿tuvo éxito esta ejecución? |
| \`stop_reason\` | text (cadena de enum en bruto) | sí | baja | no | Almacenado literal para forense. |
| \`terminal_category\` | enum | sí | baja | no | Materializado, no derivado en tiempo de lectura. |
| \`billed_provider\`, \`billed_model\` | text | no | baja | no | Por lo que se te cobró. |
| \`executed_provider\`, \`executed_model\` | text | sí | baja | no | Lo que realmente se ejecutó. Pueden diferir. |
| \`model_snapshot\` | jsonb (indexado por \`_v\`) | sí | media | no | Lista de precios y config del modelo congeladas al inicio de la ejecución. |
| \`prompt_tokens\`, \`completion_tokens\`, \`cache_creation_tokens\`, \`cache_read_tokens\`, \`reasoning_tokens\` | int4 x5 | no (default 0) | alta | no | Cinco contadores, no un total: se tarifan de forma distinta. |
| \`cost_settled\` | numeric(15,4) | sí | alta | no | El importe realmente cobrado, materializado en tiempo de escritura. |
| \`system_prompt_hash\` | bytea(32) | sí | alta | no | Referencia, nunca el texto. |
| \`build_sha\` | text(40) | sí | baja | no | ¿Esta ejecución es anterior al fix? |
| \`config_snapshot\` | jsonb | sí | media | quizá | Política en vigor, incluyendo si se requería aprobación. |
| \`approval_ref\` | uuid | **sí** | alta | no | NULL significa "la política en vigor no requería aprobación". |
| \`iteration_count\`, \`tool_call_count\` | int4 | no | alta | no | Forma de la ejecución sin leer sus pasos. |

Once de esos necesitan más que una frase.

**Acuña \`run_id\` en el dispatch.** Un bug real: las filas de reclamo de tarea del lado MCP se escribían antes de que existiera la fila de ejecución, así que un id generado por Hibernate dejaba \`task_id\` en NULL silenciosamente. El fix pasa un id de ejecución explícito a través de la llamada de dispatch y lo usa como clave primaria (\`AgentObservabilityRequest.executionId\`, documentado en el código como "stable correlation ID minted at dispatch").

**El árbol de llamadas de subagentes necesita cuatro campos, no uno:** ejecución padre, agente llamante, profundidad y la llamada de herramienta exacta en el padre. Quita cualquiera y una ejecución multiagente se lee como un montón plano no ordenable.

**Dos timestamps, no uno más una duración.** Una duración no se puede reconciliar contra una línea de tiempo de eventos externa. Esta es además la única forma de campo que el propio AI Act nombra: el Art. 12(3)(a) exige "recording of the period of each use of the system (start date and time and end date and time of each use)".

**El modelo facturado y el ejecutado pueden diferir.** Una capa de enrutado puede enviar un par facturado \`(provider, model)\` a un objetivo de ejecución distinto conservando la identidad facturada en la respuesta (\`V365__create_model_execution_links.sql\`). Un trail que registra solo uno se equivoca sobre qué produjo la salida.

**\`model_snapshot\`** congela la lista de precios al inicio de la ejecución:

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

Aproximadamente 260 bytes, unos 905 MB/year con 10k runs/day, alrededor de un dólar al año de almacenamiento en bloque. Existe para que el coste sobreviva a la deprecación de un modelo a mitad de ejecución y a ediciones retroactivas de precio, y es el campo que los ingenieros recortan primero y del que más se arrepienten.

**\`cost_settled\` se materializa en tiempo de escritura.** Recalcular a partir de tokens por precio en tiempo de lectura es el *fallback* que \`model_snapshot\` habilita, no el registro; cualquier divergencia posterior es en sí misma un hallazgo.

**\`terminal_category\` se almacena materializado aunque es derivable** de \`stop_reason\`, actualmente por código de contrato generado (\`AgentStopReason.valueOfOrError(x).terminal()\`). El codegen cambia; un trail legible dentro de siete años no puede depender del build de este mes, o las filas antiguas se reclasifican solas en silencio.

**\`build_sha\`** (~40 bytes) es el campo que más a menudo falta y más a menudo se necesita. Trampa: \`.git\` normalmente no está en el contexto de build de Docker, así que la versión en ejecución reporta un placeholder estático a menos que el commit se pase como build arg.

**Nunca almacenes el texto del system prompt por ejecución.** Con 10k runs/day un system prompt de 6 KB son 20.89 GB/year de pura duplicación, y esta plataforma lo almacena hasta tres veces por ejecución (la columna \`agent_executions.system_prompt TEXT\`, una copia en el JSONB \`agent_config_snapshot\`, y otra vez como fila de rol SYSTEM en \`agent_execution_messages\`), así que 20.89 GB/year es el suelo, no el total. Almacena cada prompt distinto una vez por versión, referencia por hash. No es, sin embargo, la mayor partida evitable: el almacén duplicado de resultados de herramienta (cuantificado en el artículo complementario sobre retención) son 83.55 GB/year, cuatro veces mayor. Esos dos, 83.55 GB/year de resultados de herramienta y luego 20.89 GB/year de system prompts, son las únicas partidas evitables por encima de 10 GB/year en este modelo.

**\`trail_seq\` proviene de una secuencia dedicada, no de \`created_at\`.** Sobrevive al desfase de reloj, a una restauración en otra zona horaria y a dos filas escritas en el mismo milisegundo. Los huecos son aceptables y deben documentarse como tales; la monotonicidad es la propiedad afirmada. \`V169__trigger_lifecycle_invariants.sql\` muestra el patrón: ordena el historial por \`(trigger_id, trigger_type, seq DESC)\` y mantiene un índice \`created_at DESC\` solo para la consulta de operaciones por ventana de tiempo.

**\`prev_row_hmac\` es la frontera** entre un log de observabilidad y un audit trail. El HMAC de cada fila cubre su propio contenido más el de la fila anterior, así que una edición o eliminación silenciosa rompe la cadena. La cabecera de \`V195__create_organization_audit_event.sql\` de esta plataforma lo lista como deliberadamente omitido de ese MVP, junto con una purga de retención bajo un lock distribuido, un espejo WORM y la separación de roles append-only. Esa lista sirve también como checklist de madurez.

## El esquema a nivel de paso

Una fila por turno de LLM, llamada de herramienta, decisión o señal. Las filas de paso superan a las de ejecución en torno a 26 a 1 y cargan todo el payload, así que su perfil de retención y de datos personales es completamente distinto.

| Campo | Tipo | Null | Cardinalidad | Datos personales | Por qué existe |
|---|---|---|---|---|---|
| \`run_id\` | uuid | no | alta | no | Clave de join del padre. |
| \`tenant_id\`, \`organization_id\` | text / uuid | no | media | indirecto | En **cada** fila hija, para supresión con alcance de org. |
| \`step_seq\` | int4 (asignado por el escritor) | no | alta | no | Orden determinista. Nunca derivado de \`created_at\`. |
| \`iteration_seq\` | int4 (asignado por el escritor) | no | media | no | A qué turno de LLM pertenece. |
| \`parallel_index\` | int2 | **sí** | baja | no | NULL significa secuencial. Distingue un lote concurrente de una cadena causal. |
| \`step_kind\` | enum | no | baja | no | llm_turn / tool_call / decision / signal / message. |
| \`tool_name\` | text | sí | **baja** | no | El GROUP BY de "qué hace realmente este agente". |
| \`tool_call_id\` | text | sí | alta | no | Correlaciona la petición con el resultado a través de reintentos y reordenamientos. |
| \`args_digest\` | bytea(32) | sí | alta | no* | Probar o refutar un payload producido sin retenerlo. |
| \`result_digest\` | bytea(32) | sí | alta | no* | Lo mismo, para resultados. |
| \`content_length\` | int4 | sí | alta | no | Cuán grande **era** el payload, retenido después de que desaparece. |
| \`payload_ref\` | uuid | sí | alta | solo puntero | Blob descargado por encima del umbral inline. |
| \`content\` | text | sí | alta | **sí** | Payload inline, en el reloj corto. |
| \`error_code\` | enum | sí | baja | no | Clase de fallo legible por máquina. Ventana completa. |
| \`error_message\` | text | sí | alta | **sí** | Texto libre. Reloj del payload. |
| \`branch_taken\` | text (etiqueta de puerto) | sí | baja | no | Qué arista saliente siguió la ejecución. |
| \`skip_reason\` | text | sí | baja | no | Por qué un nodo **no** se ejecutó. |
| \`skip_source_node\` | text | sí | media | no | Qué decisión aguas arriba lo saltó. |
| \`redaction_applied\` | int2 (bitmask) | no | baja | no | Qué reglas de redacción se dispararon. |
| \`prompt_tokens\`, \`completion_tokens\`, ... | int4 | **sí** | alta | no | Escrito solo cuando es distinto de cero, así NULL conserva su significado. |
| \`duration_ms\` | int8 | sí | alta | no | Atribuye un timeout a nivel de ejecución al paso que consumió el presupuesto. |

\\* Un digest no es dato personal solo cuando el espacio del payload no es enumerable (ver la salvedad más abajo).

Los cinco contadores de tokens son NOT NULL default 0 en la cabecera de ejecución (una ejecución siempre tiene un total) pero nulables en las filas de paso, donde NULL significa "no aplica" (una fila de llamada de herramienta no tiene tokens), no cero. Suma los pasos contra la cabecera teniendo esa regla en mente, o los dos discreparán.

**\`parallel_index\` cuesta cuatro bytes** y previene el peor fallo de un trail: reconstruir una cadena causal a partir de un lote paralelo, lo cual es peor que un hueco porque está equivocado con confianza.

**\`args_digest\` y \`result_digest\` son el eje del diseño de retención.** 32 B por digest; las 6 filas de llamada de herramienta cargan dos, las 14 filas de mensaje cargan uno, así que 832 bytes por ejecución, 2.83 GB/year con 10k runs/day. Conserva el digest durante toda la ventana de obligación, el payload en un reloj corto: cuando alguien produce un documento y afirma que el agente lo vio, el digest lo prueba o lo refuta sin payload retenido alguno.

La salvedad, sin rodeos: **para un espacio de entrada pequeño y enumerable (un código postal, una fecha de nacimiento) el digest es reidentificable** y debe salarse con una clave guardada por separado. La regla es "nunca publiques un digest sin sal de un campo de baja entropía", no "los digests no son personales". Las [directrices de pseudonimización del EDPB](https://www.edpb.europa.eu/system/files/2025-01/edpb_guidelines_202501_pseudonymisation_en.pdf) sostienen que el simple hashing sin separación de dominio ni control de acceso es insuficiente (borrador de consulta de enero de 2025).

**\`content_length\` se establece incondicionalmente antes de la decisión de inline, descargar o truncar**, que es lo que le dice a un futuro lector que hubo truncamiento y cuánto no está viendo (\`AgentObservabilityService\`, \`CONTENT_INLINE_THRESHOLD = 8192\`):

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

**Separa \`error_code\` de \`error_message\`.** Los mensajes de texto libre son inconsultables, inestables entre actualizaciones de librerías, y rutinariamente reflejan la entrada que causó el fallo, convirtiéndolos en el campo de datos personales de mayor riesgo del trail mientras parecen diagnósticos. El código se retiene durante toda la ventana; el mensaje va en el reloj del payload.

**\`branch_taken\` hace el trail reproducible sobre papel** en lugar de por reejecución; en un motor de workflow los puertos son un conjunto cerrado de baja cardinalidad por tipo de nodo (\`if\` / \`else\` / \`elseif_N\`, \`case_N\` / \`default\`, \`body\` / \`iterate\` / \`exit\`, \`branch_N\`). Registra también por qué un nodo **no** se ejecutó: \`skip_reason\` más \`skip_source_node\` hacen del negativo un hecho de primera clase, así que una rama saltada es distinguible de una nunca alcanzada.

**\`redaction_applied\` son dos bytes** que separan tres estados que un trail escueto confunde: payload limpio, payload redactado, o redactor deshabilitado. Sin él, un trail de aspecto limpio carece de valor probatorio. El \`ToolCallRedactor\` de esta plataforma es de dos capas (una regex de nombres de campo secretos más una allowlist de herramientas de credenciales que borra todo el cuerpo del argumento) y no persiste ningún marcador de qué capa se disparó; esa es la brecha que este campo cierra.

## El registro de aprobación es su propia fila, y su campo más difícil es lo que el humano vio

El human-in-the-loop es lo único que el AI Act enumera para los sistemas que cubre, y lo único para lo que OTel no tiene atributo. El Art. 12(3)(d) exige, para los sistemas del Annex III punto 1(a), "the identification of the natural persons involved in the verification of the results" referidos en el Art. 14(5).

Un registro de aprobación utilizable (el \`orchestrator.workflow_signal_waits\` de esta plataforma):

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

**El campo que nadie registra es lo que el aprobador realmente vio.** \`approval_context\` es la plantilla de contexto del nodo renderizada contra el contexto de ejecución **congelado en el momento de la pausa**, persistido con la señal, y luego reemitido literalmente en la salida del nodo resuelto para que sobreviva a la transición de awaiting a resolved (migración \`V373\`, que añade \`approval_context\` a la tabla de signal-wait).

**\`approval_ref\` en la fila de ejecución es nulable, y NULL debe significar "la política en vigor no requería aprobación"**, un hecho distinto de "estado de aprobación desconocido". Eso requiere que la versión de la política sea recuperable desde \`config_snapshot\`.

**Los valores por defecto de identidad deben ser visiblemente distinguibles de identidades reales.** Aquí \`resolved_by\` recurre al literal \`"system"\` cuando es null en la salida del nodo, y a \`"api"\` cuando falta la cabecera de usuario aguas arriba. Bien, siempre que ningún humano pueda llamarse jamás \`api\`.

**Dimensionar una columna de identidad es un asunto de auditoría.** \`resolved_by\` era \`VARCHAR(100)\` hasta que identificadores federados de la forma \`b:org:user\` (~120 chars) lo desbordaron, revirtiendo la transacción de resolución y dejando aprobaciones atascadas en \`CLAIMED\` para siempre, indistinguibles de las genuinamente pendientes (\`V191__signal_waits_widen_resolved_by.sql\`).

**Las aprobaciones delegadas necesitan su propio libro de entregas.** \`orchestrator.approval_channel_deliveries\`: un token de callback de un solo uso (\`VARCHAR(64) UNIQUE\`), estado (\`PENDING\`, \`SENT\`, \`FAILED\`, \`RESOLVED\`, \`CANCELLED\`), el texto del mensaje realmente enviado, una allowlist de usuarios permitidos, y \`UNIQUE (signal_wait_id, channel)\` como guardia contra reenvíos. La identidad es entonces una cadena con espacio de nombres tal como \`telegram:<fromId>\`.

**La intención registrada no es control impuesto, y el trail no debería dar a entender lo contrario.** Aquí \`approverRoles\` se registra en la config de la señal y se muestra al aprobador, pero el endpoint de resolución en la app impone solo el alcance de ejecución, no la pertenencia al rol. Si tu trail registra un rol que no comprobó, dilo en la documentación del campo.
`;

export default content;
