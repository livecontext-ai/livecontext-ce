// workflow-beats-do-everything-agent - es
// Translated from the English body; keep the structure identical to it
// (10 h2, 6 tables, 5 fenced formula blocks, 21 source links). The formulas are
// fenced on purpose: an inline formula over ~45 chars overflows the page on a phone.
const content = `## El número que borré

Una versión anterior de este artículo decía que un flujo de trabajo acotado sale "unas diez veces más barato" que un agente que lo hace todo. Ese número no tenía derivación, ni supuestos, ni fuente detrás, así que ha desaparecido.

No hay ninguna fuente publicada con la que sustituirlo. Ningún benchmark de proveedor, artículo ni traza mide el mismo trabajo implementado una vez como pipeline acotado y otra vez como agente autónomo, con el coste y el éxito instrumentados en ambos casos. La pieza canónica de esta categoría, [Building Effective Agents](https://www.anthropic.com/engineering/building-effective-agents) de Anthropic, no contiene ni una sola cifra de coste; su tratamiento del asunto son dos frases: "los sistemas agénticos a menudo intercambian latencia y coste por un mejor rendimiento en la tarea, y conviene considerar cuándo ese intercambio tiene sentido" y "la naturaleza autónoma de los agentes implica costes más altos y la posibilidad de errores que se acumulan". La segunda frase afirma la tesis de este artículo sin ningún número asociado.

Los multiplicadores que circulan no son intercambiables. "3-10x" circula como afirmación sobre el número de llamadas al LLM y "5-30x" como afirmación sobre tokens por tarea, ninguna de las dos con una fuente primaria rastreable. La única cifra con supuestos visibles, [12.4x de un post en dev.to](https://dev.to/awxglobal/why-your-llm-agent-costs-10x-more-than-your-estimate-4o78), se construye a partir de un prompt de sistema de 800 tokens reenviado en cada llamada, 4 turnos por petición y 3.5 llamadas a herramientas con 250 tokens de sobrecarga cada una, frente a una línea base que solo cuenta el propio prompt del usuario y la respuesta del modelo. Su 12.4x es, por tanto, una afirmación sobre el ratio de sobrecarga de prompt y herramientas a un número de turnos fijo, no sobre un trabajo completo; cambia el número de turnos y el múltiplo se mueve. Esa es la única derivación del género que puedes auditar, y auditarla demuestra que no mide lo que miden los otros dos rangos. Los posts de frameworks que sí comparan formas ([Sashido](https://www.sashido.io/en/blog/agentic-workflows-roi-without-expensive-agents), [LindleyLabs](https://lindleylabs.com/blog/agent-or-pipeline-a-decision-framework-for-ai-engineers), [Retool](https://retool.com/resources/ai-workflows-vs-agents)) imprimen fórmulas y árboles de decisión sin ninguna variable poblada.

Sí existe un 10x auténtico y con fuente en este terreno, y no es la afirmación que borré: el [multiplicador de lectura de caché de Anthropic es exactamente 0.1x la entrada base](https://platform.claude.com/docs/en/about-claude/pricing), de modo que un token de entrada cacheado es precisamente diez veces más barato que uno sin cachear. Eso se aplica al componente de prefijo cacheado de los tokens de entrada, nada más.

Regla para el resto de esta pieza: cada ratio impreso aquí se deriva en la propia página a partir de supuestos declarados. Ninguno se cita.

## Adónde va el dinero: dos funciones de coste, una cuadrática

Primero el mecanismo, para que cada número posterior sea falsable por inspección.

La Messages API no tiene estado. La llamada \`i\` de un agente transporta por tanto \`In_i = B + (i-1)g\`, donde \`B = S + T + P0\` es el prompt de sistema, las definiciones de herramientas y la carga inicial, y \`g = a + r\` es el crecimiento por turno (la salida del asistente más el resultado de la herramienta que se vuelve a añadir). Sumando sobre N llamadas:

\`\`\`
I(N) = N*B + g*N*(N-1)/2
\`\`\`

El primer término es el impuesto de prefijo, lineal en N. El segundo es el impuesto de acumulación, cuadrático en N. Un token producido en el turno \`i\` se vuelve a leer como entrada \`(N - i)\` veces más.

El coste del flujo de trabajo acotado es:

\`\`\`
C_wf = sum over k of [ p_in^k*(s_k + t_k + d_k) + p_out^k*o_k ]
\`\`\`

Lineal en K, porque el paso k recibe sus entradas declaradas \`d_k\` y nunca la transcripción de los pasos 1 a k-1. Fíjate en el \`^k\` del precio: un flujo puede enrutar cada paso a un modelo distinto sin penalización. Un agente de bucle único paga una reescritura completa de caché de su prefijo acumulado cada vez que cambia de modelo a mitad de conversación, así que en la práctica fija un solo modelo para todo el bucle. El enrutamiento por llamada dentro de una arquitectura de agente exige una frontera de subagente, que es en sí misma una decisión de acotamiento y cuesta un prefijo nuevo por subagente.

La cota de descomposición es exacta, no retórica. Dividir un bucle de N llamadas en K segmentos acotados divide el término de acumulación exactamente entre K, dado que \`K * g*(N/K)^2/2 = g*N^2/(2K)\`. Una división en tres pasos limita el ahorro de acumulación a 3x. Cualquier 10x reclamado a partir de un flujo de tres pasos viene del impuesto de prefijo, del ancho de la carga útil o del enrutamiento de modelos, no de romper la cuadrática.

Las definiciones de herramientas son un componente real de B. Anthropic informa de que una configuración de cinco servidores MCP (GitHub, Slack, Sentry, Grafana, Splunk) [consume aproximadamente 55,000 tokens de definiciones](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool) antes de que el modelo haga trabajo alguno. A precio de lista, eso son $0.275 por llamada sin cachear en Opus 4.8, y esa cifra mantiene constante el recuento de tokens a través de la frontera de tokenizador que se describe al final de esta pieza, así que trátala como un suelo y no como una estimación.

## El ejemplo desarrollado: triaje de soporte, con todos los supuestos impresos

El trabajo: clasificar un ticket, consultar la cuenta, buscar en la base de conocimiento, redactar una respuesta y revisarla.

| Parámetro | Símbolo | Valor del agente | Valor del flujo | De dónde sale |
|---|---|---|---|---|
| Prompt de sistema | S | 1,500 tok | 250-600 tok por paso | Supuesto: un único prompt que lo hace todo frente a cuatro acotados |
| Definiciones de herramientas | T | 6 herramientas, 900 tok | 30 tok por paso, 120 tok en total | Supuesto; ningún proveedor publica una cifra por herramienta |
| Carga inicial | P0 | 600 tok | 600 tok (texto del ticket) | El mismo ticket en ambos casos |
| Prefijo | B = S+T+P0 | 3,000 tok | n/a (por paso) | Suma de lo anterior |
| Crecimiento por turno | g = a + r | 1,000 tok | 0 (no arrastra transcripción) | a=300 de salida, r=700 de resultado de herramienta |
| Llamadas / pasos | N / K | 8 llamadas | 4 pasos con LLM + 2 deterministas | Juicio sobre lo que el trabajo necesita |
| Precio | p_in / p_out | $3.00 / $15.00 por MTok | igual | [Precio de lista de Sonnet 4.6](https://platform.claude.com/docs/en/about-claude/pricing), verificado el 2026-07-22 |

Cada uno de esos recuentos de tokens es un supuesto declarado, no una medición. Ninguno procede de un endpoint de conteo de tokens.

**Libro mayor del agente, N=8.** La entrada crece exactamente en g = 1,000 por llamada, así que la tabla es una progresión aritmética de 3,000 a 10,000; solo los extremos y la segunda fila son informativos.

| Llamada | Tokens de entrada | Entrada acumulada | Tokens de salida | Coste acumulado |
|---|---|---|---|---|
| 1 | 3,000 | 3,000 | 300 | $0.0135 |
| 2 | 4,000 | 7,000 | 300 | $0.0300 |
| ... | +1,000 cada una | | 300 | |
| 8 | 10,000 | 52,000 | 300 | $0.1920 |

El total de 52,000 coincide con la forma cerrada: \`8*3,000 + 1,000*(8*7/2) = 24,000 + 28,000\`. Coste: 52,000 x $3/MTok = $0.156 de entrada, más 2,400 x $15/MTok = $0.036 de salida. **$0.192 por ticket.**

**Libro mayor del flujo, el mismo trabajo.** Las columnas de prefijo y carga útil aparecen separadas, porque de esa separación salen las dos palancas de más abajo.

| Paso | ¿LLM? | Modelo | Sistema | Defs. herramientas | Datos declarados | Entrada | Salida | Coste del paso |
|---|---|---|---|---|---|---|---|---|
| Clasificar | sí | Sonnet 4.6 | 250 | 30 | 600 | 880 | 60 | $0.00354 |
| Consulta de cuenta | no | (determinista) | 0 | 0 | 0 | 0 | 0 | $0 |
| Recuperación en KB | no | (determinista) | 0 | 0 | 0 | 0 | 0 | $0 |
| Construcción de consulta KB | sí | Sonnet 4.6 | 250 | 30 | 70 | 350 | 25 | $0.00143 |
| Redacción | sí | Sonnet 4.6 | 600 | 30 | 1,810 | 2,440 | 400 | $0.01332 |
| Revisión | sí | Sonnet 4.6 | 600 | 30 | 450 | 1,080 | 80 | $0.00444 |
| **Total** | | | **1,700** | **120** | **2,930** | **4,750** | **565** | **$0.02273** |

Los datos declarados del paso de redacción son ticket 600 + etiqueta 60 + datos de cuenta 250 + los 3 mejores extractos de KB 900 = 1,810.

Este libro mayor solo pone precio a los tokens de modelo. En el producto alojado, un nodo terminal de flujo lleva además una tarifa plana de 1 crédito ($0.001), lo que suma $0.006 para estos 6 nodos y lleva el flujo a $0.0287. Todos los ratios siguientes son la comparación solo de tokens; la versión con coste alojado del titular se indica donde importa por primera vez.

**Titular para esta configuración: 8.4x** ($0.192 / $0.02273), o 6.7x una vez incluida la tarifa alojada por nodo. El ratio solo de tokens de entrada es 10.9x (52,000 / 4,750). El ratio de tokens de salida es apenas 4.2x (2,400 / 565), y eso es lo que arrastra la cifra combinada por debajo de 10x: ambas formas tienen que escribir de verdad la misma respuesta de unos 400 tokens.

Dos palancas sobreviven al cacheo, pero encogidas por él. El **impuesto de prefijo**: sin caché, el agente reenvía ocho veces su prefijo de 3,000 tokens que lo hace todo (24,000 tokens) frente a los 1,820 tokens totales de prompts de sistema y definiciones de herramientas del flujo, 13.2x. Con cacheo incremental, el componente de prefijo del agente cae a \`1.25B + 0.1(N-1)B\` = 5,850 tokens efectivos, y la palanca baja a 3.2x. El **ancho de la carga útil**, sobre una base instantánea: en su última llamada, la entrada del agente contiene 7,600 tokens de carga acumulada y transcripción de herramientas (600 iniciales más 7 x 1,000 de crecimiento) frente al paso individual más ancho del flujo, cuya entrada declarada son 1,810 tokens, 4.2x. Esa comparación cambia de base contable en cuanto el cacheo está activo, porque los deltas más antiguos del agente se releen a 0.1x: sobre una base acumulada de tokens efectivos, el crecimiento de carga útil del agente cuesta \`1.25*1,000*7 + 0.1*1,000*21\` = 10,850 tokens frente a los 2,930 tokens de datos declarados del flujo, 3.7x. El mecanismo detrás de ambas es que una entrada declarada es una proyección sobre la observación bruta.

## El ratio es una función de N, y N es todo el argumento

Bajo los supuestos del ejemplo, el coste del agente es \`0.0015N^2 + 0.012N\` dólares. Comprobación con N=8: $0.096 + $0.096 = $0.192.

| N (llamadas del agente) | Coste del agente | Coste del flujo | Ratio | Qué representa esta N |
|---|---|---|---|---|
| 2 | $0.030 | $0.0227 | 1.3x | Cortocircuito: "es spam, escalar" |
| 4 | $0.072 | $0.0227 | 3.2x | Uso mínimo de herramientas, sin reintentos |
| 6 | $0.126 | $0.0227 | 5.5x | Una consulta reintentada |
| 8 | $0.192 | $0.0227 | 8.4x | El ejemplo desarrollado |
| 12 | $0.360 | $0.0227 | 15.8x | El agente explora la KB |
| 20 | $0.840 | $0.0227 | 37.0x | Divagación, o un ticket genuinamente difícil |

Resolviendo \`0.0015N^2 + 0.012N = 0.022725R\`: el ratio vale 10 con N = 8.94 llamadas y vale 3 con N = 3.84 llamadas. Citar un ratio de coste sin citar N no significa nada.

Una restricción de honestidad sobre esa tabla: las filas de N alta solo son legítimas si el trabajo necesita de verdad tantas llamadas. Un agente que emplea 20 llamadas para hacer lo que un flujo hace en 4 está divagando, lo cual es un hallazgo sobre competencia y hay que argumentarlo como tal, no colarlo dentro de una tabla de costes.

## Cachea bien el agente, y solo entonces compara

Los ratios publicados de flujo frente a agente comparan por lo general contra un agente sin caché. El cacheo es donde se va la mayor parte de la brecha, así que ponle precio antes de comparar.

Los [multiplicadores de cacheo](https://platform.claude.com/docs/en/build-with-claude/prompt-caching) de Anthropic son exactos: escritura en caché de 5 minutos = 1.25x la entrada base, escritura de 1 hora = 2x, lectura de caché = 0.1x. El punto de equilibrio también está publicado: la caché de 5 minutos se amortiza tras una sola lectura (1.25 + 0.1 = 1.35x frente a 2x sin cachear); la de 1 hora necesita dos lecturas (2 + 0.2 = 2.2x frente a 3x).

Con cacheo incremental multiturno, la entrada efectiva del agente pasa a ser:

\`\`\`
1.25B + 0.1(N-1)B + 0.1g(N-2)(N-1)/2 + 1.25g(N-1)
\`\`\`

El coeficiente cuadrático cae de \`p_in*g/2\` a \`0.1*p_in*g/2\`, un descuento exacto de 10x sobre precisamente el término al que el flujo estaba ganando.

Con N=8, la entrada efectiva por llamada queda en 3,750 / 1,550 / 1,650 / 1,750 / 1,850 / 1,950 / 2,050 / 2,150 = 16,700 tokens frente a 52,000 sin cachear. Eso son $0.0501 de entrada más $0.036 de salida = **$0.0861**. El cacheo recorta el coste del agente un 55% y hunde el titular de 8.4x a **3.8x**.

Fíjate en qué domina una vez activo el cacheo: la escritura a 1.25x del delta de cada turno, \`1.25 * 1,000 * 7 = 8,750\` de los 16,700 tokens efectivos, un 52%. La ventaja del flujo que sobrevive es el impuesto de prefijo y el ancho de carga útil, no la cuadrática del reenvío.

El cacheo aplana la brecha sin cerrarla. Con N=20 el agente cacheado cuesta $0.241 frente a $0.840 sin caché, todavía 10.6x el flujo, porque 19 escrituras de caché a 1.25x más 20 turnos de contenido generado son irreducibles.

Aquí el flujo apenas captura nada del mismo beneficio. El prefijo cacheable mínimo en Sonnet 4.6 es de 1,024 tokens (verificado contra la documentación de cacheo el 2026-07-22; son 512 en Fable 5 y Mythos 5, 2,048 en Opus 4.7, y 4,096 en Opus 4.6, Opus 4.5 y Haiku 4.5). El prefijo estable de cada paso del flujo aquí es su prompt de sistema más las definiciones de herramientas, de 280 a 630 tokens, por debajo del umbral en todos y cada uno de esos modelos. Los prefijos por debajo del mínimo fallan en silencio: no se devuelve error alguno y tanto \`cache_creation_input_tokens\` como \`cache_read_input_tokens\` marcan 0. Ten en cuenta que enrutar un paso a Haiku 4.5 eleva su umbral a 4,096, así que la configuración enrutada de más abajo queda más lejos de ser cacheable, no más cerca.

El arreglo accionable tiene un punto de equilibrio publicado. Consolida el prefijo de un paso de alto volumen por encima del mínimo cacheable del modelo en el que corre y coloca el punto de corte después, para que cada ejecución de ese paso lea a 0.1x. Con el TTL de 5 minutos eso se amortiza a partir de la segunda petición, y [las lecturas de caché refrescan el TTL gratis](https://platform.claude.com/docs/en/build-with-claude/prompt-caching), así que un paso al que se llame al menos cada cinco minutos se mantiene caliente indefinidamente al precio de escritura.

Una cosa que el cacheo no hace: los tokens cacheados [siguen ocupando la ventana de contexto](https://platform.claude.com/docs/en/build-with-claude/context-windows). Cambia lo que pagas por esos tokens, no si cuentan o no. No rescata a nadie del agotamiento del contexto ni de la degradación del contexto.

## La rejilla de cuatro celdas, y dónde vive realmente un 10x auténtico

Enrutar la clasificación, la consulta de KB y la revisión a Haiku 4.5 ($1/$5) y solo la redacción a Sonnet 4.6 baja el flujo a $0.0165 por ticket (clasificar $0.00118 + consulta KB $0.000475 + redacción $0.01332 + revisión $0.00148).

| | Flujo, mismo modelo ($0.0227) | Flujo, con enrutamiento ($0.0165) |
|---|---|---|
| **Agente, sin caché ($0.192)** | 8.4x | 11.7x |
| **Agente, con caché ($0.0861)** | 3.8x | 5.2x |

Mi opción por defecto es la celda superior derecha invertida: cachea el agente, enruta el flujo y espera 5.2x. Por debajo de aproximadamente N=4 yo no construiría el flujo en absoluto, porque el ratio queda por debajo de 3x y el coste de construcción no se amortiza (ver la sección final); por encima de aproximadamente N=12 la cuadrática decide por ti.

Un agente de bucle único tiene que ejecutar su único modelo fijado en cada llamada. Un agente con Opus 4.8 ($5/$25) no es un cambio equivalente con los mismos recuentos de tokens, porque Opus 4.7 y posteriores usan un tokenizador más nuevo que produce aproximadamente un 30% más de tokens para el mismo texto. Aplicando ese incremento: unos 67,600 de entrada y 3,120 de salida, es decir $0.338 + $0.078 = $0.416, frente a los $0.0165 del flujo enrutado, o 25.3x. Ese es un argumento de enrutamiento, no un argumento de ventana de contexto.

La afirmación general que sale solo de la disciplina de contexto, derivada: con el agente cacheado y ambas formas sobre un mismo modelo, el ratio queda en 2.8x con N=6, 3.8x con N=8 y 5.8x con N=12. O sea, aproximadamente de 3x a 6x en un rango plausible de N, y cualquier cosa por encima de eso es una decisión de cacheo o de enrutamiento que hay que declarar como tal.

La estructura de precios de los proveedores hace predecible el enrutamiento. Todos los modelos actuales de Anthropic ponen precio a la salida exactamente a 5x la entrada (Opus 4.8 $5/$25, Sonnet 4.6 $3/$15, Haiku 4.5 $1/$5). Todos los modelos actuales de OpenAI ponen precio a la salida a 6x la entrada (gpt-5.6-sol $5.00/$30.00, gpt-5.4 $2.50/$15.00, gpt-5.4-mini $0.75/$4.50), con gpt-5.4-nano como única excepción a 6.25x ($0.20/$1.25). [DeepSeek](https://api-docs.deepseek.com/quick_start/pricing) pone precio a la salida a exactamente 2x la entrada sin acierto de caché (deepseek-v4-flash $0.14/$0.28, deepseek-v4-pro $0.435/$0.87). Dentro de un mismo proveedor, la mezcla entrada:salida determina el perfil de coste más que la elección de modelo. Y el nivel por lotes es una quinta palanca, exclusiva del flujo, para pasos no sensibles a la latencia: un descuento plano del 50% sobre entrada y salida en Anthropic y OpenAI, la mitad de la tarifa estándar en Gemini, y acumulable con los multiplicadores de cacheo en Anthropic.

## Dónde se rompe este modelo

| Condición | Efecto sobre el ratio | Magnitud aquí | Por qué ocurre |
|---|---|---|---|
| Trabajos cortos (N<4) | Se desploma, puede invertirse | 1.3x con N=2 | El agente hace cortocircuito; el flujo siempre recorre su ruta fija |
| Trabajo dominado por la salida | Tiende a 1 | 2.2x para un informe de 5,000 palabras con N=8 | Ambas formas escriben el mismo entregable |
| Gran contexto compartido | Puede invertirse | 5D frente a 1.95D en un documento de 50k | El flujo lo reenvía en cada paso salvo que cachee antes el documento |
| Investigación en anchura paralelizable | Favorece el multiagente | +90.2% en una evaluación de un proveedor | La autonomía compra una cobertura que el pipeline no puede enumerar |
| Búsqueda de herramientas (carga diferida) | Reduce la ventaja de prefijo | el proveedor afirma un recorte de definiciones >85% | El agente captura el ahorro de prefijo sin rearquitecturarse |
| Conjunto de más de 30-50 herramientas | Favorece al flujo, por corrección | sin precio calculado | La precisión de selección de herramientas se degrada |
| Recargo por herramientas dependiente del modelo | Desplaza B | de 290 a 804 tok según el modelo | Coste fijo de prompt de sistema antes de cualquier esquema |
| Cargos por herramientas del lado del servidor | Fuera del modelo de tokens | $10 por 1,000 búsquedas web | Facturación por llamada, no por token |
| Cambio de tokenizador / reversión de precio | Invalida cifras con fecha | ~30% más tokens; de $2/$10 a $3/$15 | Nuevo tokenizador desde Opus 4.7 en adelante; el precio introductorio de Sonnet 5 termina el 31 ago 2026 |

Cuatro de esas merecen desarrollo.

**Trabajos dominados por la salida.** Un informe de 5,000 palabras son unos 6,700 tokens de salida, $0.1005 a $15/MTok en ambos lados. Manteniendo las entradas del ejemplo (agente $0.156, flujo $0.01425), el ratio es $0.2565 / $0.1145 = 2.2x, y sigue cayendo a medida que crece el entregable.

**El gran contexto compartido** es el caso de inversión. Si cada paso necesita el mismo documento de 50k tokens, un flujo de cinco pasos lo reenvía cinco veces (5D) mientras que un agente cacheado de ocho llamadas paga 1.25D + 7 x 0.1D = 1.95D. El flujo solo gana si coloca el documento primero, antes de la instrucción específica del paso, y lo cachea (1.25D + 4 x 0.1D = 1.65D).

**La investigación paralelizable es el caso publicado más fuerte contra toda esta tesis.** Anthropic informa de que un sistema multiagente, un Claude Opus 4 líder orquestando subagentes Claude Sonnet 4, [superó al Claude Opus 4 de agente único en un 90.2%](https://www.anthropic.com/engineering/multi-agent-research-system) en su evaluación interna de investigación, y en el mismo post, de que los agentes usan aproximadamente 4x los tokens de una interacción de chat mientras que los sistemas multiagente usan aproximadamente 15x. Eso es autonomía comprando una gran ganancia de calidad a un gran múltiplo de coste, y es además una arquitectura de agente haciendo enrutamiento de modelos por paso a través de una frontera de subagente. La propia precondición de Anthropic es el encuadre honesto: solo compensa cuando el valor de la tarea cubre el multiplicador, y encaja mal cuando todos los agentes necesitan el mismo contexto o el trabajo tiene muchas dependencias.

**La búsqueda de herramientas** es el contraargumento más fuerte específicamente contra el caso del impuesto de prefijo. Anthropic afirma que la carga diferida de herramientas [suele recortar el contexto de definiciones de herramientas en más de un 85%](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool), cargando solo las 3-5 herramientas necesarias, lo que permite a un agente que lo hace todo capturar buena parte del ahorro de prefijo sin rearquitecturarse. Eso es una afirmación del proveedor sin metodología divulgada, y debe tratarse como tal. El propio disparador de Anthropic: usa búsqueda de herramientas a partir de 10 herramientas, o cuando las definiciones superen los 10k tokens. La misma página afirma que la precisión de selección de herramientas se degrada en cuanto superas las 30-50 herramientas disponibles, lo que da al argumento del acotamiento una pata de fiabilidad que no depende en absoluto de la aritmética de tokens.

## Coste por ejecución exitosa, y la condición que invierte el argumento

La comparación que de verdad importa es \`C/q\`: el coste dividido por la tasa de éxito de cada forma. Una tasa de reejecución del 20% en el flujo multiplica su coste por 1.2 y recorta el titular de 8.4x a 7.0x. Un agente que en cambio se recupera dentro del contexto paga unos cuantos turnos extra, con precio cuadrático.

La consistencia entre ejecuciones repetidas se desploma más rápido que la precisión titular. En el artículo original de 2024 de [tau-bench retail](https://arxiv.org/abs/2406.12045), los mejores agentes de llamada a funciones de entonces eran lo bastante inconsistentes como para que pass^8 cayera por debajo del 25%. Producción significa ejecutar el mismo trabajo muchas veces, así que pass^k es la métrica correcta, no pass@1, y es ese punto estructural el que sostiene el argumento aquí, no ninguna cifra absoluta de 2024.

El éxito además decae con la duración de la tarea de una forma que hace que la reducción de alcance sea superlineal en fiabilidad. El [modelo de vida media de Toby Ord](https://www.tobyord.com/writing/half-life) predice que el horizonte de éxito del 80% es aproximadamente 1/3 del horizonte del 50%, el del 90% aproximadamente 1/7 y el del 99% aproximadamente 1/70; el autor es explícito en que el modelo está ajustado a una sola batería de tareas y en que se desconoce su generalidad. [Las mediciones de METR](https://arxiv.org/abs/2503.14499) muestran horizontes temporales al 80% aproximadamente 5x más cortos que los del 50%, lo cual es más pronunciado que el 3x del modelo de vida media, de modo que ambos acotan el efecto en lugar de confirmarse mutuamente. Y el fallo es estructural, no simplemente una puntuación más baja: el [estudio HORIZON](https://arxiv.org/html/2604.11978v1), sobre más de 3,100 trayectorias, atribuye el 72.5% de los fallos a causas de proceso (error de entorno, error de instrucción, error de planificación, historial acumulado) y reporta una transición abrupta de robustez parcial a fallo casi sistemático. Ese mismo estudio sostiene que la descomposición por sí sola no es la solución: pide planificación jerárquica y verificación en tiempo de ejecución, no simplemente trocear la tarea.

El modelo opuesto más fuerte es el de [Zartis](https://www.zartis.com/ai-agent-cost-optimisation-why-token-cost-is-the-wrong-number-to-optimise/):

\`\`\`
total_cost_per_task = (token_cost + infrastructure_cost) / reliability_rate
                      + failure_rate * human_remediation_cost
\`\`\`

Su ejemplo desarrollado hace que una arquitectura 5x más cara por llamada ($0.05 frente a $0.01) resulte 5.7x más barata en conjunto (~$8,835/día frente a ~$50,100/día) una vez que la fiabilidad sube del 70% al 95%. Sus recuentos de tokens, tarifas por hora y minutos de remediación son supuestos declarados de ese artículo, no mediciones, y sus dos arquitecturas difieren en ancho de contexto más que en autonomía. La estructura del argumento sigue en pie.

Resuélvelo con estos números. Flujo $0.0227, agente cacheado $0.0861, diferencia $0.0634 por ticket. Si un fallo cuesta \`H\` en remediación humana y la tasa de éxito del agente supera a la del flujo en \`dq\`, el agente gana cuando \`dq * H > 0.0634\`. Con un analista a $100/hora y 10 minutos por remediación, H = $16.67, así que basta con una ventaja de tasa de éxito de 0.38 puntos porcentuales. Con 5 minutos y $80/hora, H = $6.67 y el umbral es de 0.95 puntos. Dicho sin rodeos: **con cualquier tasa de remediación humana no trivial, el ratio de tokens deja de ser el término decisivo.** El 3.8x sobre el que se construyó este punto de equilibrio es un error de redondeo frente a una diferencia de un punto en la tasa de éxito, e incluso el 8.4x sin caché solo necesita una ventaja de 1.02 puntos para darse la vuelta (diferencia de $0.1693 frente a H = $16.67). Eso corta en ambas direcciones, y es la razón por la que el argumento de fiabilidad a favor del acotamiento (horizontes más cortos, menos herramientas, contratos verificados entre pasos) importa más que el argumento de coste que todo este artículo acaba de derivar.

Gastar más tampoco compra precisión. En [GAIA](https://hal.cs.princeton.edu/gaia), un agente que usaba o3 Medium costó $2,828.54 para un 28.48% de precisión, mientras que Gemini 2.0 Flash costó $7.80 para un 32.73%. En el mismo [programa de evaluación](https://arxiv.org/abs/2510.11977), un mayor esfuerzo de razonamiento redujo la precisión en la mayoría de las 36 combinaciones de modelo y benchmark probadas.

## N es un resultado, no una entrada

Todo lo anterior trata N como un parámetro. En producción es emergente, y por eso el ratio tiene una cola larga.

La cola no es una subida gradual, es una función escalón, y eso es lo que la hace difícil de vigilar. Hay un incidente de producción con esa forma registrado: una ejecución avanzó en crucero durante varias iteraciones a unos 70k tokens de prompt y 700 de compleción cada una, lo bastante barata como para que una proyección basada en la media siguiera aprobando la siguiente, y entonces una sola iteración se disparó a unos 2M tokens. Una media móvil diluye precisamente eso.

La cota que sí lo atrapa no promedia en absoluto:

\`\`\`
projectedNext       = max(growth projection, last delta x 2, worstCaseSingleIter)
worstCaseSingleIter = cost(full context window, full max output)
\`\`\`

Ese segundo término es invariante al patrón de crecimiento, que es justamente el objetivo. Valorado sobre una fila de clase Opus con 200k de contexto y 64k de salida máxima a $15/$75 por MTok, una iteración en el peor caso son 200,000 x $15/MTok + 64,000 x $75/MTok = $3.00 + $4.80 = $7.80. Una sola iteración de ese modelo puede plausiblemente costar más que un saldo de cuenta pequeño, así que la barrera salta en la primera iteración de crucero en lugar de apostar por la media.

La estimación de coste falla por caro y no por barato por la misma razón: un modelo sin fila de precios cae de vuelta a $15/$75 por MTok, el nivel más alto de la instantánea, porque un fallback anterior cercano a cero se saltaba en silencio la barrera de presupuesto por completo.

El final de una ejecución tiene que clasificarse antes de poder calcular siquiera el coste por éxito. Una taxonomía de producción enumera exactamente 10 razones de parada en 3 categorías terminales: éxito (COMPLETED); parcial (MAX_ITERATIONS, TIMEOUT, BUDGET_EXHAUSTED, LOOP_DETECTED, STOPPED_BY_USER), definida como "terminó limpiamente pero no completó la tarea según lo planeado, la salida es utilizable pero truncada o prematura"; y fallo (CANCELLED, NO_TOOLS, ERROR, INACTIVITY_TIMEOUT). TIMEOUT e INACTIVITY_TIMEOUT caen en categorías distintas de forma deliberada: pasarse de un presupuesto de reloj es parcial, no producir ningún token, razonamiento, llamada a herramienta ni resultado de herramienta dentro de la ventana del watchdog es fallo.

El ancla del paso determinista hace concreta la comparación. Un nodo terminal de flujo, completado o fallido, cuesta una tarifa plana de 1 crédito ($0.001) en el producto alojado; solo los nodos omitidos son gratis, y la versión autoalojada registra la misma línea de 1 crédito en el libro mayor para observabilidad pero nunca la descuenta, porque el saldo es ilimitado. Al precio de lista de $3/MTok de Sonnet 4.6, un crédito equivale a 333 tokens de entrada a precio de lista; en el producto alojado, el margen de 1.8x del LLM en la nube lo deja en unos 185 tokens, así que cualquier prompt por encima de unos 186 tokens cuesta más que un paso determinista entero. Solo 4 de aproximadamente 60 tipos de nodo de la paleta (Agent, Classify, Guardrail, Browser Agent) invocan un LLM siquiera.

## Cómo ejecutar esto con tus propios números

1. **Mide los tokens.** Cada recuento del ejemplo anterior es un supuesto. Sustitúyelos por el endpoint de conteo de tokens del proveedor sobre texto real de tickets, esquemas reales de herramientas y un extracto real de KB.
2. **Mide N a partir de trazas existentes, no la estimes.** El ratio es aproximadamente cuadrático en N, así que una N equivocada es un error al cuadrado en el titular.
3. **Clasifica un mes de ejecuciones terminadas por razón de parada y categoría terminal** antes de citar cualquier cifra de coste por éxito. Los finales parciales y los de fallo tienen costes de remediación distintos y solo una de las tres categorías cuenta como ejecución exitosa.

Dos cosas que este modelo no contiene, y ninguna de las dos debería inferirse de él. No dice nada sobre la calidad de la salida: pone precio a tokens, y no hay ninguna medición de tasa de éxito detrás de ninguna cifra suya. Y ignora el coste de ingeniería, que es el término que decide la mayoría de estas cuestiones en la práctica. Mi propia estimación, declarada como supuesto igual que todo lo demás aquí: un flujo de cinco pasos con contratos declarados entre pasos cuesta aproximadamente tres días de ingeniero construirlo y medio día al mes mantenerlo, frente a medio día para conectar un agente a seis herramientas. Con los mismos $100/hora usados antes para la remediación, eso son unos $2,000 más por adelantado y unos $400/mes más de forma recurrente. Frente a la diferencia de $0.0634 por ticket del agente cacheado, solo la brecha inicial necesita aproximadamente 31,500 tickets para amortizarse, y la brecha de mantenimiento necesita aproximadamente 6,300 tickets al mes por encima. Por debajo de ese volumen, la fila de la tabla de amortización en la que estás es la que dice: construye el agente.
`;

export default content;
