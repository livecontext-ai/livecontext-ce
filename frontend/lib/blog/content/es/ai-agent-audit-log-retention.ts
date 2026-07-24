// ai-agent-audit-log-retention - es
// Translated from the English body; structure identical to it. Legal scope
// statements and every "at least 6 months"/"out of scope"/"not legal advice" are
// load-bearing and must not be strengthened or softened. Fenced code stays fenced.
const content = `Comparing against source, the Spanish is largely faithful. Fixing the two issues I found (an awkward intro rendering and one units inconsistency), everything else (counts, tables, URLs, dates, legal force) checks out.

A continuación la versión corregida.

Un artículo complementario publica el esquema de campos a nivel de ejecución y a nivel de paso al que este artículo pone precio: los nombres de campo, tipos y clases de cardinalidad referenciados en las tablas de abajo se definen allí. Esta pieza responde las tres preguntas que el esquema deja abiertas. ¿Cuántos bytes cuesta realmente el rastro? ¿Durante cuánto tiempo debe conservarse cada campo? ¿Y algo de esto te aplica legalmente, lo cual para la mayoría de los lectores no ocurre?

La implementación de referencia citada a lo largo del texto es la propia plataforma de este blog: nombres de columna reales, migraciones reales, bugs reales.

## La aritmética, para que la escalonación sea derivada y no afirmada

Todo lo que sigue es un **modelo**, no una medición. Las entradas se indican para que puedas reejecutarlo con tus propias cifras. Los tamaños de fila son analíticos, derivados de los tipos de columna del DDL más la sobrecarga documentada de Postgres; las tablas reales ocupan aproximadamente entre un 10 y un 25% más una vez que se incluyen el fillfactor, el espacio libre y el bloat, así que lee cada cifra derivada de abajo como "+10 a 25% en producción" (la cifra plana de captura completa a siete años, 1.68 TB en el modelo, ronda los 2.1 TB en el extremo alto de ese rango).

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

La cifra de solo metadatos desde la que escala el resto del modelo es 9.05 KB/run, derivada así:

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

| Nivel de captura | Bytes/run | MB/día @10k runs | GB/año | GB en 7 años | GB/año comprimido |
|---|---|---|---|---|---|
| Solo metadatos | 9.05 KB | 88.38 | 31.50 | 220.51 | 31.50 (sin comprimir en PG; se archiva bien) |
| Metadatos + digests (~832 B/run) | 9.86 KB | 96.29 | 34.33 | 240.31 | 34.33 |
| Captura completa | 70.43 KB | 687.78 | 245.16 | 1,716 (1.68 TB) | 92.6-117 |

La captura completa es 7.8x la de solo metadatos. La compresión asume un factor de 2.5-3.5x sobre las cargas útiles por encima del umbral TOAST de Postgres (~2 kB, 2048 bytes), un rango publicado típico y no una medición sobre este corpus, así que la cifra comprimida de captura completa abarca de 92.6 a 117 GB/año según dónde caigas dentro de ese rango.

Una sola entrada domina el resultado:

| Resultado de herramienta medio | KB/run (captura completa) | GB/año @10k runs/día | Perfil de agente que vive aquí |
|---|---|---|---|
| 1 KB | 34.43 | 119.84 | Clasificación, enrutamiento, consultas API cortas |
| 4 KB | 70.43 | 245.16 | Uso mixto de herramientas, el modelo de arriba |
| 8 KB | 118.43 | 412.24 | Redacción de documentos, CRUD multirregistro |
| 20 KB | 262.43 | 913.50 | Búsqueda, lectura de archivos, agentes con mucho SQL |

Los prompts y las completions son el 20% de la carga útil con un resultado de herramienta medio de 4 KB (12.8 KB de 61.38 KB) y bajan a cerca del 5% a 20 KB (12.8 KB de 253.38 KB), así que los resultados de herramienta son donde la escalonación rinde. **Si escalonas una sola cosa, escalona los resultados de herramienta.**

Ahora la inversión que motiva toda la sección. 245 GB/año son alrededor de **$235/year** de almacenamiento en bloque gp3, **$68/year** en S3 Standard, **$12/year** en Glacier Instant Retrieval; solo metadatos son alrededor de $30/year. (Cifras de orden de magnitud de precios de lista en us-east-1, excluyendo cargos de solicitud y recuperación; los niveles fríos asumen un volumen de lectura casi nulo.) **Nadie recorta su rastro para ahorrar $200.**

Lo que la cifra en dólares oculta es el coste real: **98.55 millones de filas/año** (689.85 millones a lo largo de siete años) de superficie de borrado, mantenimiento de índices y tiempo de restauración, más el hecho de que cada byte retenido de prompt y de resultado de herramienta es responsabilidad legal. Diseña la escalonación en torno al radio de impacto y al recuento de filas.

A 1M runs/día el techo operativo muerde mucho antes que la factura de almacenamiento: ~54M inserciones de índice/día, 9.86 mil millones de filas/año, 23.94 TB/año de captura completa, y aproximadamente 140 horas para restaurar lógicamente un año a 50 MB/s. El nivel esqueleto es lo que mantiene un rastro *restaurable*, no solo asequible.

Un ahorro gratuito, encontrado leyendo el esquema en vez del código: **el resultado de herramienta suele persistirse dos veces**, una como el contenido de la fila de la llamada a herramienta y otra como el contenido de la fila de mensaje de rol de herramienta correspondiente. Almacena la carga útil una vez y haz que la fila de mensaje lleve el mismo \`payload_ref\`, y la carga útil baja de 61.38 KB a 37.38 KB por run, de 245.16 GB/año a 161.61 GB/año. Cualquier rastro que tenga tanto una tabla de llamadas a herramienta como una tabla de mensajes tiene esta forma. (La observación a nivel de esquema es sólida; la tasa exacta de solapamiento en producción no se midió.)

## Niveles de retención, cada uno justificado por la decisión que respalda

| Nivel | Contenido | Ventana | GB/año | Pregunta que responde | ¿Muestreado o degradado? |
|---|---|---|---|---|---|
| 0 Esqueleto | Cabecera de run sin nada de texto; metadatos de paso (\`step_seq\`, \`tool_name\`, \`branch_taken\`, status, \`stop_reason\`, duraciones, recuentos de tokens, \`content_length\`, todos los digests) | Ventana completa de obligación (7 años modelados) | 31.50 | ¿Ocurrió este run, cuándo, quién lo disparó, qué hizo, por qué rama fue, cuánto costó | **Nunca** |
| 1 Digests y códigos | \`args_digest\`, \`result_digest\`, \`error_code\`, \`redaction_applied\`, \`model_snapshot\` | 12-24 meses | 34.33 | Probar o refutar que el agente vio un documento producido; recalcular el coste de un run en disputa a los precios vigentes | **Nunca** |
| 2 Argumentos y resultados de herramienta | \`content\`, \`payload_ref\` para pasos de herramienta | 30-90 días en caliente, luego muestreado | ~80% de los bytes de carga útil | Depurar una regresión en vivo; responder la queja de un cliente | Sí, tras la ventana en caliente |
| 3 Prompts y completions | Contenido de los mensajes | 30 días, **más el 100% de los runs fallidos o que activaron una guardrail a cualquier edad** | ver abajo | Reconstruir el razonamiento de una decisión en disputa | Solo de forma no uniforme |
| 4 Plantillas de prompt | System prompts, texto del prompt por versión | Para siempre (kilobytes) | ~0 | Qué versión de prompt se ejecutó | Nunca con un reloj por run |

El nivel 0 a lo largo de siete años son 220.51 GB, alrededor de **$10.60/year** en Glacier Instant Retrieval (220.51 GB x $0.004/GB-month x 12). Eso responde la mayoría de las preguntas de un auditor sin retener ni un byte de datos personales.

La regla de muestreo del nivel 3 es la que vale la pena discutir, y la perilla solo toca los niveles 2 y 3 (invariante 1: los registros de auditoría nunca se muestrean). A una tasa de fallo asumida del 8%, conservar todos los fallos más el 5% de los éxitos retiene el 12.6% de los runs (0.08 + 0.92 x 0.05 = 0.126). Aplicado solo a los niveles de carga útil (captura completa menos el esqueleto de 31.50 y los niveles de digest de 2.83, es decir 210.83 GB/año), eso conserva 26.56 GB/año de carga útil; con los niveles 0 y 1 mantenidos al 100%, el detalle completo residente baja de 245.16 a cerca de **60.9 GB/año** (31.50 + 2.83 + 26.56), conservando cada run por el que alguien realmente va a preguntar. El muestreo uniforme optimiza para los runs que nadie investiga.

Plan combinado, por nivel:

\`\`\`
30 days full capture:   20.15 GB gp3           $19.34
365 days digests:       34.33 GB S3 Standard    $9.47
7 years skeleton:      220.51 GB Glacier IR    $10.58
resident total:        274.99 GB             ~ $39/year
\`\`\`

Eso son 274.99 GB residentes frente a 1.68 TB de captura completa plana mantenida siete años, una reducción de 6.2x, aproximadamente $39/year frente a $1,647/year de gp3 plano. El ahorro que importa no es el dinero: **solo 30 días de carga útil de datos personales entran en el alcance de una solicitud de supresión, en lugar de siete años.**

Caliente-más-frío es la forma que los reguladores ya codifican. El requisito 10.5.1 de PCI DSS 4.0 pide 12 meses con los 3 más recientes disponibles de inmediato; SEC Rule 17a-4 pide seis años con los dos primeros fácilmente accesibles. (Ambos confirmables tal como se enuncian.)

El antipatrón que hay que nombrar: la muy difundida **escalera de degradación progresiva** que descarta el contenido de prompt y completion tras el primer año y conserva solo metadatos a partir del tercero. Degrada el contenido precisamente en la ventana donde un auditor lo necesita, y deja que una empresa afirme "siete años de logs de auditoría" mientras no retiene nada que explique una sola decisión.

## Lo que realmente debes, y por qué probablemente no es nada

| Instrumento | Artículo / control | A quién obliga | Qué exige en realidad | Retención | ¿Especifica campos? |
|---|---|---|---|---|---|
| EU AI Act | [Art. 12(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-12) | **Sistemas** de alto riesgo (requisito de diseño) | Los sistemas "shall technically allow for the automatic recording of events (logs) over the lifetime of the system" | n/a | **No** |
| EU AI Act | Art. 12(2)(a)-(c) | como arriba | Solo los *fines*: riesgo bajo Art. 79(1) o modificación sustancial; monitoreo poscomercialización bajo Art. 72; monitoreo de operación bajo Art. 26(5) | n/a | **No** |
| EU AI Act | Art. 12(3)(a)-(d) | **Solo Annex III point 1(a)** (identificación biométrica remota) | Período de cada uso; base de datos de referencia consultada; datos de entrada cuya búsqueda condujo a una coincidencia; identificación de las personas que verifican los resultados | n/a | **Sí, el único lugar** |
| EU AI Act | [Art. 19(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-19) | **Proveedores** | Conservar los logs del Art. 12(1) "to the extent such logs are under their control" | **al menos 6 meses** | No |
| EU AI Act | [Art. 26(6)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-26) | **Implementadores** | El mismo deber, el mismo límite, un reloj separado | **al menos 6 meses** | No |
| EU AI Act | [Art. 18(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-18) | Proveedores | Documentación técnica, documentación del SGC, decisiones del organismo notificado, declaración UE de conformidad | **10 años** tras la introducción en el mercado o la puesta en servicio | n/a |
| EU AI Act | [Art. 86](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-86) | Implementadores | "Clear and meaningful explanations of the role of the AI system in the decision-making procedure and the main elements of the decision taken" | n/a | **No** |
| ISO/IEC 42001 | el control de registro de eventos del Anexo A | Voluntario | Logs de eventos más registros de monitoreo que muestren que el registro está operativo | ninguna prescrita | **No** |
| NIST AI RMF | MEASURE 2.8, MANAGE 2.4, MANAGE 4.3 | Voluntario | Instrumentar y mantener históricos y logs de auditoría; preservar materiales para revisión forense, regulatoria y legal; mantener bases de datos de incidentes y cambios del sistema | ninguna prescrita | **No** |
| SOC 2 | 2017 TSC (puntos de enfoque revisados en 2022) | Contractual | Evidencia genérica del entorno de control aplicada a tu agente | basado en criterios, sin período | **No** |
| HIPAA | [45 CFR 164.316(b)(2)(i)](https://www.govinfo.gov/content/pkg/CFR-2023-title45-vol2/xml/CFR-2023-title45-vol2-sec164-316.xml) | Entidades cubiertas | Conservar la documentación requerida | **6 años** | No |

Tres distinciones que la mayoría de los resúmenes equivocan.

**El Art. 12(1) es un requisito de diseño sobre el sistema. El Art. 19(1) pone un piso de seis meses al proveedor. El Art. 26(6) pone un piso de seis meses separado y paralelo al implementador.** Los seis meses se deben dos veces por dos partes distintas, no es un único reloj compartido, y ambos llevan el mismo límite, "to the extent such logs are under their control".

**Seis meses es el piso de los LOGS; diez años es el piso de la DOCUMENTACIÓN.** El Art. 18(1) y el Art. 19(1) son dos regímenes distintos, confundidos habitualmente.

**La obligación que realmente fuerza la explicabilidad por decisión es el Art. 86, no el Art. 12.** Una persona afectada, sujeta a una decisión tomada por el implementador sobre la base de la salida de un sistema de alto riesgo del Annex III (excepto el point 2), que produzca efectos jurídicos o la afecte de forma significativamente similar de un modo que considere que tiene un impacto adverso en su salud, seguridad o derechos fundamentales, tiene derecho a explicaciones sobre el papel del sistema de IA y los elementos principales de la decisión. El Art. 86(3) lo hace subsidiario respecto de otra normativa de la Unión.

**Y ahora la respuesta honesta para la mayoría de los lectores: fuera del alcance del Art. 12/19/26(6) por completo.** Alto riesgo significa Art. 6(1) (componente de seguridad de un producto del Annex I que requiere evaluación de conformidad por terceros) o Art. 6(2) (las ocho áreas del [Annex III](https://ai-act-service-desk.ec.europa.eu/en/ai-act/annex-3)). Un asistente de programación, un agente interno de investigación o de soporte, un agente de redacción de documentos no está en ninguna de ellas.

El "salvo que" que atrapa a la gente es el Annex III **point 4** (contratación y selección, anuncios de empleo dirigidos, filtrado de candidaturas, evaluación de candidatos, decisiones sobre condiciones de trabajo, ascensos, despidos, asignación de tareas basada en comportamiento o rasgos, supervisión del rendimiento) y el **point 5** (una lista parcial de sus cuatro subpuntos, los dos que más a menudo atrapan a los constructores: (b) evaluación de solvencia y calificación crediticia excluyendo la detección de fraude, y (c) evaluación de riesgos y fijación de precios en seguros de vida y de salud; los otros dos, (a) evaluación por autoridad pública de la elegibilidad para prestaciones y servicios esenciales de asistencia pública incluida la sanidad, y (d) triaje y despacho de llamadas de emergencia, atrapan a agentes de govtech y adyacentes a prestaciones).

Incluso un sistema del Annex III puede escapar mediante la excepción del [Art. 6(3)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-6) (tarea procedimental limitada; mejorar el resultado de una actividad humana ya completada; detectar patrones sin reemplazar la evaluación humana previa; una tarea preparatoria), pero **nunca si realiza elaboración de perfiles de personas físicas**. Y el Art. 6(4) hace que la vía de escape genere su propio papeleo: documentar la evaluación antes de la introducción en el mercado, más una obligación de registro bajo el Art. 49(2).

Dos trampas para los constructores. Construir un agente puramente para uso interno no te convierte en un mero implementador: el [Art. 3(11)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-3) define la puesta en servicio como suministro para primer uso "or for own use", así que un sistema de alto riesgo interno puede deber el Art. 19, el Art. 26(6) y el Art. 18 simultáneamente. El [Art. 25(1)(c)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-25) hace lo mismo con cualquiera que modifique la finalidad prevista de un modelo de propósito general de modo que el sistema pase a ser de alto riesgo.

La exposición a sanciones por los deberes de registro es el nivel intermedio, no el titular: el [Art. 99(4)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-99) es de hasta EUR 15,000,000 o el 3% del volumen de negocios anual mundial, lo que sea mayor. Cubre los Arts. 16, 22, 23, 24, 26, 31, 33, 34 y 50; el Art. 19 no figura por sí mismo en la lista, así que el incumplimiento de conservación de logs de un proveedor se alcanza vía el Art. 16(e), que importa el deber del Art. 19, mientras que el del implementador es el Art. 26 directamente. El nivel de 35 millones / 7% se reserva para las prácticas prohibidas del Art. 5.

**El calendario se ha movido.** El Digital Omnibus on AI aplaza las fechas de aplicación de alto riesgo hasta el **2 December 2027** para los sistemas de alto riesgo autónomos (Annex III) y el **2 August 2028** para la IA de alto riesgo integrada en productos regulados, según el [Council of the EU](https://www.eeas.europa.eu/delegations/chile/artificial-intelligence-council-gives-final-green-light-simplify-and-streamline-rules_en). Estado procedimental a finales de julio de 2026: aprobación del pleno del PE el 16 June 2026, adopción del Consejo el 29 June 2026, firmado el 8 July 2026, a la espera de publicación en el Diario Oficial ([EP Legislative Train](https://www.europarl.europa.eu/legislative-train/package-digital-package/file-digital-omnibus-on-ai)). Cualquier artículo que siga citando el 2 August 2026 para alto riesgo está desactualizado. El Omnibus no modifica los Artículos 12, 19 o 26(6) en el texto acordado según todo análisis publicado del mismo; el piso de seis meses no cambia. Confirmar contra el texto del OJ una vez publicado.

Los sistemas heredados pueden escapar por completo: el [Art. 111(2)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-111) aplica el Reglamento a los sistemas de alto riesgo introducidos en el mercado antes de la transición solo si posteriormente son objeto de cambios significativos en su diseño; los implementadores de autoridades públicas tienen hasta el 2 August 2030.

Dos deberes sí muerden con independencia del nivel de riesgo: el [Art. 4](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-4) (alfabetización en IA, aplicable desde el 2 February 2025, sobre proveedores e implementadores) y el [Art. 50(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-50) (los proveedores deben diseñar los sistemas de modo que se informe a las personas físicas de que interactúan con una IA, salvo que sea obvio), que aplica desde el 2 August 2026, diez días después de la publicación de esta pieza. El marcado de contenido del Art. 50(2) tiene un período de gracia hasta el 2 December 2026 para los sistemas ya en el mercado. El Omnibus suaviza el Art. 4 de garantizar un nivel suficiente de alfabetización a apoyar su desarrollo entre el personal; la fecha del 2 February 2025 no cambia, y hasta la publicación en el OJ la redacción original es la que sigue vinculando.

Y las normas que especificarían *cómo* satisfacer el Art. 12 aún no existen: el [CEN-CENELEC JTC 21](https://www.cencenelec.eu/news-events/news/2025/brief-news/2025-10-23-ai-standardization/) sigue desarrollando las normas del Chapter III Section 2, con medidas de aceleración adoptadas en octubre de 2025 que apuntan a su disponibilidad alrededor del Q4 2026. Hasta entonces es una obligación legal sin especificación técnica detrás.

Los marcos voluntarios tampoco te dan un esquema. [ISO/IEC 42001](https://www.iso.org/standard/81230.html) es voluntario (ISO no certifica organizaciones; lo hacen organismos acreditados), y su control A.6.2.8 del Anexo A, "AI system recording of event logs", no prescribe ni una duración de retención ni una lista de campos. [NIST AI RMF](https://www.nist.gov/itl/ai-risk-management-framework) es explícitamente voluntario y conductual. SOC 2 usa los 2017 Trust Services Criteria con puntos de enfoque revisados en 2022, y no se han emitido criterios específicos de IA, así que un auditor prueba evidencia genérica del entorno de control aplicada a tu agente.

Colorado merece una línea si tocas contratación o decisiones consecuentes. SB 26-189, según la [bill page](https://leg.colorado.gov/bills/sb26-189), se firmó el 14 May 2026, con efecto el 1 January 2027; deroga y reexpide la 2024 Colorado AI Act. El alcance es la tecnología de toma de decisiones automatizada utilizada en decisiones consecuentes (educación, empleo, vivienda, financiero/préstamos, seguros, atención sanitaria, servicios gubernamentales esenciales). Los desarrolladores e implementadores deben conservar los registros de cumplimiento durante al menos tres años, para los implementadores contados desde la fecha de la decisión consecuente.

**La conclusión anti-teatro.** Si estás fuera de alcance, construye el rastro para las preguntas que realmente te van a hacer: una disputa de un cliente, una revisión de incidente, una disputa de facturación, una investigación de seguridad. Dimensiona el nivel esqueleto para la obligación futura plausible más larga, porque cuesta 31.50 GB/año. Luego deja que los seis meses sean un piso que da la casualidad de que superas, en vez de un programa de trabajo. Esto no es asesoramiento jurídico, y ninguno de los regímenes de retención anteriores debería aplanarse en una única cifra que te aplique a ti.

## Datos personales: el rastro que conservas durante años y la solicitud de supresión que te llega mañana

**Una referencia de actor seudónima no saca al rastro del alcance del GDPR.** El Recital 26 trata como datos personales aquellos que podrían atribuirse a una persona usando información adicional. Almacena un token que resuelve a una identidad solo a través de una tabla de mapeo controlada por separado, y no afirmes que el rastro es anónimo.

**El piso de seis meses tiene un techo en la misma frase.** El Art. 19(1) y el Art. 26(6) ambos terminan con "unless provided otherwise in the applicable Union or national law, in particular in Union law on the protection of personal data". Conservarlo todo para siempre no es la respuesta conforme, es una infracción aparte.

**La respuesta de diseño es el pivote de digest:** el nivel largo guarda hashes, códigos, recuentos y clasificaciones, sin carga útil. Eso es lo que hace que un esqueleto de siete años sea defendible en lugar de una responsabilidad de siete años.

**Pon \`tenant_id\` y \`organization_id\` en cada fila hija, no solo en la padre.** El borrado se ejecuta como DELETEs por tabla con alcance de organización; las filas que llevan solo un \`execution_id\` necesitan un join, y cualquier fila cuyo padre ya haya desaparecido sobrevive como un huérfano inalcanzable que aún retiene datos personales. El \`WorkspaceDataPurger\` de esta plataforma emite un DELETE con alcance de organización contra \`agent_execution_tool_calls\` con clave en \`organization_id\` (y equivalentes), lo cual solo funciona porque \`V210\` añadió la columna a las cinco tablas de runtime de agente y rellenó cuatro de ellas (las filas de \`agent_tasks\` se quedan NULL por diseño, un alcance personal).

**Divide el rastro en una capa operativa borrable y una capa de libro mayor no borrable**, y deja que la supresión tome solo la primera. La implementación de referencia borra 31 tablas declaradas con alcance de organización (\`PURGED_ORG_SCOPED_TABLES\`) más las tablas hijas de ejecución de agente que toca directamente (messages, tool calls, iterations), sin tocar nunca \`auth.credit_ledger\`, \`auth.usage_cycle\`, \`auth.credit_reconciliation_log\` ni \`auth.organization_audit_event\`, y mantiene la fila de la organización como lápida para que las referencias del libro mayor sigan siendo válidas. Un test de cobertura verifica tanto el alcance de organización de cada sentencia como la no supresión de las tablas conservadas. El límite honesto: el libro mayor que sobrevive todavía prueba que los runs de un sujeto existieron y cuánto costaron, así que esto satisface la minimización solo si el libro mayor no lleva carga útil y solo identificadores seudónimos.

**Un borrado que no borra.** Cuando las cargas útiles grandes se descargan a almacenamiento de objetos y la fila conserva un puntero, borrar la fila **deja huérfano al blob**. Los datos personales sobreviven a la solicitud de supresión, sin referencias y por tanto invisibles a cualquier auditoría posterior de lo que conservas. El purgador anterior documenta exactamente este huérfano en su propio javadoc: borra las filas de \`storage.storage\` pero no los objetos S3/MinIO subyacentes. Solución: haz que el almacén de la carga útil sea el objetivo del borrado y que la fila sea el puntero, y reconcilia los huérfanos según un calendario.

**Decide si la redacción ocurre en escritura o en lectura, y registra cuál.** Un redactor que se ejecuta solo al mostrar filas a un revisor deja credenciales en crudo en los argumentos de herramienta almacenados (el estado actual aquí: \`ToolCallRedactor\` es un filtro de la ruta de lectura). Un redactor en tiempo de escritura destruye evidencia que puedas necesitar. Elijas la que elijas, \`redaction_applied\` es lo que hace que la elección sea auditable.

**El patrón sin resolver que vale la pena implementar:** poner una lápida al contenido borrado conservando su digest, de modo que la cadena a prueba de manipulaciones sobreviva a un borrado y un lector posterior aún pueda saber que algo estuvo ahí, qué tamaño tenía, y que se eliminó bajo una solicitud de derechos en vez de perderse.

## Dos fallos que hay que diseñar para evitar, y qué hacer con OpenTelemetry

**Retención que no puedes alargar retroactivamente.** El día que descubres que la ventana es más larga que tu cron de purga, los datos ya no están. Un equipo aquí que subió un log de auditoría de ciclo de vida de 30 a 365 días topó con un backlog de 12x en la primera purga posterior, y esa era la dirección *afortunada*. Fija el nivel esqueleto en la obligación plausible más larga el día uno; a 31.50 GB/año es el seguro más barato del sistema. (Relacionado: un comentario de retención documentado que decía "30d default" mientras el default del \`@Value\` del servicio era 365 es cómo la retención documentada y la configurada divergen en silencio.)

**Errores de la ruta de consulta que hacen que un rastro sea inutilizable en vez de incorrecto.** Las filas de detalle no son la ruta de consulta: preagrega las dimensiones de baja cardinalidad en rollups con clave en \`(tenant, date, provider, model)\` y \`(tenant, tool_name)\`. Postgres no indexa automáticamente las claves foráneas: una tabla de tool-calls de 18k filas y 39 MB aquí, cuyo único índice era su clave primaria, hacía full-scan en cada lectura agregada hasta que \`V341\` añadió un btree \`CONCURRENTLY\` sobre \`execution_id\`. Y las lecturas sin paginar de filas de carga útil a escala de MB son una forma de OOM: limita la página (100 es un máximo estricto razonable) y devuelve \`total\` / \`shown\` / \`truncated\` para que a un lector se le avise cuando se descartaron filas más antiguas en lugar de ver en silencio un rastro parcial.

La regla de cardinalidad que se desprende de las tablas del esquema: **los campos de baja cardinalidad** (\`status\`, \`stop_reason\`, \`provider\`, \`model\`, \`tool_name\`, \`trigger_source\`, \`branch_taken\`) son por lo que agrupa cada pregunta y pertenecen a los rollups; **los campos de alta cardinalidad** (\`run_id\`, \`tool_call_id\`, digests) son claves de join que necesitan índices btree y nunca deben entrar en una clave de rollup.

### El veredicto sobre OpenTelemetry

**No fijes todavía un esquema de auditoría a él.** Cero atributos \`gen_ai.*\` son Stable (99 Development, 0 Stable en el registro en vivo), el [GenAI semconv repo](https://github.com/open-telemetry/semantic-conventions-genai) no tiene releases ni tags, y las convenciones salieron del repo principal de semantic-conventions, que ahora renderiza cada atributo \`gen_ai.*\` en la [legacy registry page](https://opentelemetry.io/docs/specs/semconv/registry/attributes/gen-ai/) como "Deprecated" como artefacto de la mudanza. Una señal falsa en ambas direcciones.

Los renombrados ya han roto esquemas una vez:

\`\`\`
gen_ai.system              -> gen_ai.provider.name (now absent)
gen_ai.usage.prompt_tokens -> gen_ai.usage.input_tokens
gen_ai.usage.completion_tokens -> gen_ai.usage.output_tokens
gen_ai.prompt / gen_ai.completion
   -> gen_ai.input.messages / gen_ai.output.messages
\`\`\`

OTel **no tiene atributo** para una aprobación humana, una identidad de actor o principal, una decisión de política o guardrail, una clase de retención, ni el coste monetario (solo recuentos de tokens, sin \`gen_ai.cost.*\`). Esos son precisamente los campos que soportan la auditoría, que es por lo que el rastro es tu tabla y no tu backend de trazas.

Dos campos vale la pena adoptar textualmente porque son baratos y responden preguntas de auditoría reales: **\`gen_ai.prompt.name\` más \`gen_ai.prompt.version\`** prueban qué versión de prompt se ejecutó sin almacenar su texto, y **\`gen_ai.conversation.compacted\`** responde si el modelo vio el historial completo o un resumen. Nota también que \`gen_ai.provider.name\` es un discriminador de formato de telemetría que puede apuntar a un proxy, no una prueba de qué proveedor procesó los datos, y que \`gen_ai.conversation.id\` no debe fabricarse a partir de un UUID, un trace id o un hash de contenido, así que está legítimamente ausente en muchos rastros.

Los límites de span truncan un rastro en silencio: \`OTEL_SPAN_ATTRIBUTE_COUNT_LIMIT\` tiene un valor por defecto de 128. Los atributos indexados por mensaje aplanados (la forma OpenInference \`llm.input_messages.<i>.message.*\`) pueden superarlo en una conversación larga, mientras que un único \`gen_ai.input.messages\` estructurado cuesta un atributo. Eso es aritmética derivada, no un incidente documentado. Los valores de atributo estructurados tampoco están todavía soportados universalmente en los spans, así que el mismo campo lógico es una cadena JSON en un backend y un objeto en otro.

La propia recomendación de producción de la especificación es la arquitectura que se defiende aquí: almacena el contenido en almacenamiento externo con controles de acceso separados y registra referencias en los spans, e invoca el hook de subida "regardless of the span sampling decision". **Muestrea las trazas, nunca muestrees la evidencia.** Eso es \`payload_ref\` más digest con otro nombre.

Regla de cierre: **emite OTel para el dashboard, ten una tabla propia para el rastro, únelos por \`run_id\`, y mantén los dos relojes de retención separados.**
`;

export default content;
