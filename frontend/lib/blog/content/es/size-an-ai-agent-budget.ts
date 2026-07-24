// size-an-ai-agent-budget - es
// Translated from the English body; the structure must stay identical to it.
// Formulas and code samples are fenced on purpose: an inline code span over
// ~45 chars overflows the page on a phone. The hedges are load-bearing here.
const content = `Un artículo complementario sostiene que la mayoría de los presupuestos de agente son números que nunca han rechazado una sola llamada, y recorre la maquinaria de aplicación: de qué está hecho un objeto presupuesto, por qué un tope dentro de la ejecución solo puede detener la llamada posterior a la cara, y qué puede aplicar realmente cada stack. Este artículo responde la pregunta que viene después. Estás convencido de que el techo debe ser real. ¿Qué número pones en la casilla?

La respuesta corta es que no puedes elegirlo por intuición, porque la magnitud que estás acotando tiene sesgo a la derecha, es superlineal en el número de iteraciones y abarca tres órdenes de magnitud entre tipos de paso. La respuesta larga es el resto de este artículo: un modelo generador que puedes reproducir, un factor de seguridad derivado, un suelo por debajo del cual un tope de dinero no se puede aplicar en absoluto, y el tamaño de muestra que necesitas antes de que se te permita citar un cuantil de cola.

**Divulgación.** Las constantes de implementación y el mecanismo de reserva descritos abajo provienen del \`agent-service\` de LiveContext, la plataforma a la que pertenece este blog. Léelos como las decisiones de un solo sistema, verificables en su código de edición comunitaria, no como práctica de campo relevada. Los precios son instantáneas ilustrativas de catálogo; el método es independiente del precio.

## Dimensionar un presupuesto por paso que puedas calcular

Un paso que ejecuta \`n = k+1\` iteraciones de modelo con \`k\` llamadas a herramientas tiene un coste esperado gobernado por el prompt fijo \`P0\`, la carga de entrada \`I\`, los tokens \`r\` devueltos por cada resultado de herramienta, la salida \`O\` por turno y un término de acumulación proporcional a \`n(n-1)/2\`. El modelo generador de cada fila de abajo:

\`\`\`
prompt_i = (P0 + I) + (i-1) * (O_turn + r)      i = 1..n
\`\`\`

**Tabla 4a: Parámetros por arquetipo de paso.** Son **conjuntos de parámetros construidos, no trazas de producción medidas**, publicados para que cada columna derivada pueda reproducirse.

| Arquetipo de paso | P0 + I | r por resultado de herramienta | O por turno | n |
|---|---|---|---|---|
| Clasificar | 1,000 | n/a | 30 | 1 |
| Borrador con recuperación aumentada | 2,000 | 6,000 | 60 turno de herramienta, 500 final | 2 |
| Investigación multiherramienta | 2,500 | 3,000 | 80 turno de herramienta, 800 final | 7 |
| Resumen de documento largo | 120,300 | n/a | 1,500 | 1 |
| Paso de navegador | 1,800 | 8,000 | 120 turno de acción, 250 final | 13 |

**Tabla 4b: Dimensionado por paso.** Los precios provienen de una instantánea del catálogo del repo y son ilustrativos, no precios en vivo del proveedor. El método es independiente del precio. La última columna usa un S=3 plano como factor ilustrativo; la sección siguiente lo reemplaza por uno derivado.

| Arquetipo de paso | Clase de modelo, tarifa de lista ($/1M entrada, salida) | Tokens entrada / salida | Iteraciones n | Coste esperado | Mayor iteración individual (x la primera) | Presupuesto con S=3 (plano) |
|---|---|---|---|---|---|---|
| Clasificar | clase flash-lite, 0.25 / 1.50 | 1,000 / 30 | 1 | $0.00030 | 1.0x | $0.0009 |
| Borrador con recuperación aumentada | clase haiku, 1.00 / 5.00 | 10,060 / 560 | 2 | $0.01286 | 4.6x | $0.0386 |
| Investigación multiherramienta | clase sonnet, 3.00 / 15.00 | 82,180 / 1,280 | 7 (6 llamadas a herramientas) | $0.2657 | 8.6x | $0.797 |
| Resumen de documento largo | clase flash, 0.30 / 2.50 | 120,300 / 1,500 | 1 | $0.0398 | 1.0x | $0.1195 |
| Paso de navegador | clase gpt-5.4, 2.50 / 15.00 | 656,760 / 1,690 | 13 (12 acciones, instantáneas de 8,000 tokens) | $1.667 | 40x | $5.00 |

La proporción 40x entre la primera y la última iteración del paso de navegador condiciona el diseño: una proyección por media móvil subestima la iteración que mata por más de un orden de magnitud. Por eso una proyección necesita una rama de peor caso que ignore por completo el patrón de crecimiento, como deriva el artículo complementario.

### El factor de seguridad es derivado, no adivinado

\`\`\`
S = (n_q / n_p50) ^ alpha        where alpha = dlogC / dlogn

alpha in [1, ~2.3]: it approaches 1 for single-shot
steps, approaches 2 for accumulation-dominated steps,
and exceeds 2 when the first iteration is cheap
relative to the accumulated context.

alpha:  classify ~1.0 | long-doc ~1.0 | RAG draft 1.77
        multi-tool research 1.81 | browser 2.03
\`\`\`

Un paso cuyo p99 usa el doble de llamadas a herramientas que su p50 necesita un S de alrededor de 2.0 si es de un solo disparo, pero de 3.4 a 4.1 si es intensivo en herramientas. Adivinar "2x" subdimensiona sistemáticamente justo los pasos que necesitan margen. Estos alfas son tangentes en el punto de operación; quien mida la secante sobre un rango observado de n obtendrá un número algo mayor. Comprobación de la secante contra el modelo exacto: investigación de n 7 a 14 cuesta 3.66x (la tangente predice 2^1.81 = 3.51); navegador de n 13 a 26 cuesta 4.06x (la tangente predice 2^2.03 = 4.08).

Corolario: **duplicar las iteraciones permitidas cuadruplica aproximadamente el techo de dinero.** "Subamos un poco el máximo de iteraciones" es una decisión de presupuesto de 4x.

Eso también hace que un tope de iteraciones sea un mal tope de dinero. Con un valor por defecto de plataforma de 100 iteraciones máximas, el techo del arquetipo de navegador es de 40,374,000 tokens de prompt = $101.11 para un solo paso (frente a $1.667 esperados), y el del arquetipo de investigación es de 15,496,000 tokens = $46.62 (frente a $0.266). Como puntos de dato calculados y no como un rango: 7.7x la n esperada deja 61x de holgura de dinero en el paso de navegador; 14.3x deja 175x en el paso de investigación.

### El suelo de aplicabilidad

Como la proyección por agente necesita dos muestras, y se autodeniega cuando una iteración supera \`budget/3\`, la razón de granularidad debe satisfacer:

\`\`\`
g = B_step / cost_of_one_iteration  >=  3
\`\`\`

de modo que el suelo para un presupuesto por paso es 3x la iteración de peor caso. Contra la iteración de peor caso sin acotar del modelo, ninguno de los cinco presupuestos lo supera: clasificar $0.0009 frente a un suelo de $1.04 (g = 0.003), borrador RAG $0.0386 frente a $1.56 (g = 0.074), investigación $0.797 frente a $4.68 (g = 0.51), documento largo $0.1195 frente a $1.39 (g = 0.26), navegador $5.00 frente a $8.76 (g = 1.71).

**Tabla 5: El suelo de aplicabilidad** (precios de catálogo y ventanas de contexto ilustrativos; sustituye por los tuyos)

| Clase de modelo | Iteración de peor caso, contexto sin acotar | Presupuesto mínimo aplicable (3x) | Iteración de peor caso con topes de admisión de 30K/2K | Presupuesto mínimo aplicable con topes |
|---|---|---|---|---|
| flash-lite | $0.348 | $1.04 | $0.0105 | $0.032 |
| haiku | $0.520 | $1.56 | $0.040 | $0.120 |
| flash | $0.464 | $1.39 | $0.014 | $0.042 |
| sonnet | $1.560 | $4.68 | $0.120 | $0.360 |
| gpt-5.4 | $2.920 | $8.76 | $0.105 | $0.315 |

Cualquier tope de dinero por paso por debajo del suelo de la columna sin acotar es contabilidad, no aplicación. El arreglo son **topes de admisión sobre las entradas**, no un presupuesto mayor: limitar el prompt admitido a 30K tokens y \`max_tokens\` a 2K hunde el suelo entre 13 y 33x.

Pero los topes de admisión cambian el propio perfil de coste del paso, así que \`B_step\` tiene que re-derivarse bajo ellos, y los topes tienen que ser compatibles con el paso en primer lugar:

- **Paso de investigación**: compatible tal cual está. Su mayor prompt de iteración ronda los 21K, por debajo del tope de 30K, así que su presupuesto sobrevive sin cambios y g sube de 0.51 a 6.6.
- **Paso de navegador**: rompe los 30K aproximadamente en la iteración 4 (cada instantánea añade 8,120 tokens). Recorta a las últimas tres instantáneas y la mayor iteración cae a unos 26K, el coste esperado a $0.754, el presupuesto con S=3 a $2.26, y g a 21.5.
- **Paso de documento largo**: un tope de prompt de 30K lo rechaza de plano, ya que su única iteración es de 120K tokens. Limitar el prompt admitido a su propio tamaño de entrada aún deja g = 2.8, por debajo del suelo. Su n está fija en 1, así que el control ahí es el tamaño de entrada mismo, no un tope de dinero.

La regla de los dos regímenes: los pasos por debajo del cruce están acotados por construcción (n fija en 1 o 2, \`I\` pequeña) y deberían controlarse limitando entradas; los pasos por encima son los únicos donde un tope de dinero hace trabajo real. **Limita entradas en los pasos baratos, limita dinero en los caros.**

Una aclaración sobre lo que significa "desactivado por defecto" en esta implementación, porque es fácil entenderlo al revés. El techo de peor caso está **siempre activo** para cualquier modelo cuya fila de catálogo lleve una ventana de contexto y un máximo de tokens de salida: ambos guardas toman \`max(growth, lastDelta*2, worstCase)\` incondicionalmente. Lo que se entrega desactivado por defecto es el comportamiento separado de fallo **cerrado** para modelos a los que *falta* esa metadata (\`requireCtxWindow\`). La razón documentada es una ventana de migración: de lo contrario, las instantáneas de precios heredadas sin esas columnas denegarían cada turno de chat.

### Cuantiles, muestras y falsos cortes que se componen

Elegir el cuantil es elegir la tasa de falsos cortes. Si \`B_step\` es el cuantil q del coste legítimo observado, la tasa de falsos cortes por paso es exactamente \`1-q\` por construcción. Eso es una decisión de producto, no una decisión estadística.

Reconcilia eso con el factor de seguridad antes de usar ambos: la fórmula de S de arriba es el estimador que usas cuando la cola del *coste* no es medible pero la cola de *n* sí se conoce. La q que elijas para el cuantil y la \`n_q\` que alimentes a S deben ser el mismo cuantil. Para un paso con n fija, \`n_q / n_p50 = 1\` y S degenera a 1, así que el cuantil tiene que venir en cambio de la varianza del tamaño de entrada.

Tamaño de muestra antes de poder citar un cuantil de cola: \`1/sqrt(n(1-q))\` es el error estándar relativo del *recuento de excedencias* de la cola, así que una estimación de ese recuento con más o menos 30% necesita \`n ~ 11/(1-q)\`. p90 necesita unas 111 ejecuciones, p95 unas 220, p99 unas 1,100, p99.5 unas 2,200. Trata esos valores como cotas inferiores: el error sobre el *valor* en dólares del cuantil depende de la densidad en la cola, y para una distribución de coste sesgada a la derecha es materialmente peor. Por debajo de aproximadamente 200 ejecuciones no puedes afirmar honestamente un p99, y deberías dimensionar a partir del peor caso estructural.

Los falsos cortes por paso se componen. Con k pasos cada uno topado en su propio p99, y suponiendo costes por paso independientes y que cada ejecución recorre los k pasos, la fracción de ejecuciones que alcanza un tope en algún punto es \`1 - q^k\`:

\`\`\`
k = 3   ->  3.0%
k = 10  ->  9.6%
k = 20  -> 18.2%

A p95 per-step cap across 10 steps kills 40.1% of runs.

To hit a 1% run-level target:  q_step = (1 - target)^(1/k)
  k=3  -> p99.666 | k=10 -> p99.900 | k=50 -> p99.980
\`\`\`

La correlación positiva entre pasos (una entrada sobredimensionada a nivel de ejecución que infla varios a la vez) baja la tasa real, así que trata estos números como el extremo pesimista.

Dimensiona a partir de la distribución, no de la media: el coste por paso tiene sesgo a la derecha, la media se sitúa alrededor de p70, y dimensionar a partir de ella mata aproximadamente el 30% de las ejecuciones de paso legítimas, que es \`1 - 0.7^k\` de las ejecuciones.

Recoge cinco campos por ejecución de paso: tokens de prompt, tokens de completado, número de llamadas a herramientas, id de modelo, razón de parada terminal. Cuatro de los cinco son exactamente lo que un guarda pre-iteración ya consume, así que si puedes aplicar, puedes medir.

Para calibrar n, dos anclajes independientes: las propias reglas de escalado de Anthropic (búsqueda simple de datos 1 agente con 3 a 10 llamadas a herramientas; comparaciones directas 2 a 4 subagentes con 10 a 15 llamadas cada uno; investigación compleja más de 10 subagentes), y una trayectoria promedio de resolución de issues de GitHub que alcanza un contexto pico de 48.4K tokens tras 40 pasos, con alrededor de 1.0M tokens acumulados a lo largo de la trayectoria ([arXiv 2509.23586](https://arxiv.org/html/2509.23586v1)). Frente a eso, el ampliamente reportado valor por defecto de 25 superpasos (todavía el valor por defecto del esquema de langgraph-sdk, aproximadamente 12 llamadas a herramientas en un bucle ReAct) mata trabajo real, mientras que un tope de 200 pasos no hace nada.

**El procedimiento:**

1. Recoge ejecuciones de paso con los cinco campos.
2. Calcula el cuantil por paso requerido a partir de tu objetivo a nivel de ejecución: \`q_step = (1 - target)^(1/k)\`.
3. Comprueba si tu muestra lo soporta: necesitas al menos \`11/(1-q_step)\` ejecuciones, lo que con k=10 y un objetivo del 1% a nivel de ejecución son aproximadamente 11,000. **Si no lo soporta, detente aquí y dimensiona a partir del peor caso estructural acotado (Tabla 5).** Usa el cuantil que *sí* puedes estimar solo para detectar que el tope estructural es demasiado holgado, no para fijar el tope. Este es el caso común, y fingir lo contrario es cómo un tope dimensionado a p95 acaba matando el 40% de las ejecuciones.
4. Si la muestra sí lo soporta, mide alpha regresando \`log(cost)\` sobre \`log(n)\`, y fija \`B_step\` en \`q_step\`.
5. Comprueba \`g >= 3\` contra la iteración de peor caso **acotada**. Si falla, añade topes de admisión en lugar de subir el presupuesto, y re-deriva \`B_step\` bajo esos topes.
6. Fija el tope de ejecución (sección siguiente).
7. Sobrealimenta deliberadamente un paso y confirma que la razón de parada se dispara.

## Topes de ejecución, fan-out y por qué sumar los topes de paso está mal

La cota correcta de ejecución es sobre **ejecuciones** de nodo en el camino de peor caso, no sobre nodos:

\`\`\`
B_run = max over execution paths P of  sum over nodes v in P of  m_v * B_v

m_v = M inside a split of width M
    = L for a loop body
    = 1 otherwise

Exclusive branches contribute max, not sum.
\`\`\`

Sumar los topes por paso está mal de tres formas, y apuntan en direcciones opuestas:

1. **Subcuenta exactamente por M en el subgrafo abanicado.** Un pipeline de 3 nodos que suma $0.837 tiene un peor caso real de $41.78 cuando los dos últimos nodos están dentro de un split de ancho 50.
2. **Sobrecuenta las ramas exclusivas** que nunca pueden ejecutarse ambas.
3. **Es estadísticamente inalcanzable.** Para 10 pasos lognormales independientes con \`p99/p50 = 3\`, cada uno topado en 3x su mediana, la suma de topes se sitúa alrededor de 1.88x el p99 real del total de la ejecución. Trata ese multiplicador como direccional: los costes reales por paso están parcialmente correlacionados, lo que reduce la brecha. Un tope que esencialmente no puede dispararse tampoco puede atrapar un fallo estructural moderado.

**Agrupación.** Un fan-out independiente necesita un margen relativo *menor*, por \`sqrt(M)\`:

\`\`\`
S_run = 1 + (S_step - 1) / sqrt(M)

S_step = 3:  M=5 -> 1.89 | M=10 -> 1.63 | M=50 -> 1.28 | M=200 -> 1.14
\`\`\`

Trabajado: 50 ramas del paso de investigación (media $0.2657) dimensionadas ingenuamente como \`M * B_step\` = $39.85, pero el tope agrupado es $17.00, un tope de ejecución 2.3x más ajustado con el mismo riesgo. Declara el supuesto de independencia en voz alta: si el coste de rama está gobernado por una propiedad a nivel de ejecución, como una entrada sobredimensionada abanicada a todas las ramas, los costes están totalmente correlacionados y el ahorro desaparece por completo.

Los topes por paso y por ejecución tienen trabajos distintos, y eso es lo que fija sus tamaños. El tope de paso está afinado, se espera que se dispare ocasionalmente, y trunca la salida de un solo paso. El tope de ejecución es un cortacircuitos que debería dispararse prácticamente nunca, y cada disparo es un incidente que investigar (M explotó, un bucle volvió a entrar en el fan-out, una entrada fue 100x lo normal).

**El fan-out necesita control de admisión, no interceptación.** Un tope de ejecución aplicado como comprobación en curso mata ramas en pleno vuelo y produce un conjunto de resultados parcial no determinista: 50 ramas a $0.836 cada una bajo un tope de ejecución de $10 completan 11 de 50; bajo $20, 23 de 50; y cuáles ganan depende del orden de arranque. Reservar el \`M * b\` completo antes de generar las ramas convierte eso en "se negó a ejecutar", que es explícito y reintentable.

El mecanismo de reserva, tal como está implementado en \`BudgetReservationService\`:

- Al generar el hijo, la cantidad solicitada se reserva atómicamente en **cada ancestro** de la cadena de llamadas dentro de una sola transacción. La primera denegación lanza una excepción, así que la transacción revierte la actualización de cada ancestro anterior. No existe compensación manual.
- El invariante que se compra: la suma de \`consumed\` entre todos los descendientes de A se mantiene dentro del presupuesto de A a cualquier profundidad, sin recorrer el árbol en tiempo de ejecución en el camino caliente.
- Cada reserva por ancestro es un único UPDATE SQL condicional sin SELECT-luego-UPDATE, así que no hay TOCTOU. Incrementa la columna de reservado solo cuando el presupuesto libre del ancestro cubre la petición:

  \`\`\`
  free = credit_budget - credits_consumed - credits_reserved
  UPDATE ... SET credits_reserved = credits_reserved + :req
   WHERE id = :ancestor
     AND (credit_budget IS NULL OR free >= :req)
  \`\`\`

  El éxito lo decide que el número de filas devuelto sea 1. Un ancestro ilimitado coincide con una escritura sin efecto y también devuelve 1.
- Dimensionado de la reserva: una petición explícita gana (se rechaza si es negativa); en caso contrario el valor por defecto es el **presupuesto libre mínimo entre todos los ancestros**, o cero si todos los ancestros son ilimitados.
- La liquidación recorre la misma cadena una vez al terminar el hijo y, por ancestro, devuelve la reserva retenida y contabiliza el coste real en una sola actualización, escribiendo \`consumed\` y \`consumed_from_subagents\` con el mismo delta en la misma transacción, de modo que el invariante se cumple por construcción. Las columnas de reserva están marcadas como no actualizables a nivel del ORM para que un flush sucio no pueda reescribirlas en silencio.
- Las reservas fugadas se barren **al arranque**, no por un timeout: un worker sin estado no puede ser dueño de ninguna reserva anterior a él, así que toda reserva retenida no nula presente al arrancar está por definición huérfana y se limpia en una sola actualización. El barrido nunca debe hacer fallar el arranque.
- La cadena de llamadas misma vive en una clave reservada del mapa de credenciales, del más cercano al más lejano, ausente en las invocaciones raíz (de modo que la cascada no hace nada en la raíz) y antepuesta por el agente generador para cada hijo.

Evidencia independiente de que el candado, no el número, es lo que hace real a un tope: en un experimento controlado reportado en un preprint de 2026 que cataloga 63 incidentes ([arXiv 2606.04056](https://arxiv.org/abs/2606.04056)), un contador de presupuesto en Python con asyncio y condiciones de carrera se pasó del tope 30 de 30 veces, mientras que un contador de Python correctamente bloqueado y un presupuesto de tipos afines en Rust se pasaron 0 de 30 cada uno.

La restricción de dimensionado del padre que se deriva: para generar M hijos que retienen cada uno un tope b, el padre necesita presupuesto **libre** de al menos \`M * b\` en el momento de generarlos, no el total esperado. Para el arquetipo de investigación con M=50 y b=$0.797, el padre necesita $39.85 libres aunque la ejecución vaya a costar en realidad alrededor de $13.3. Dimensiona un padre por el gasto esperado y solo \`1/S\` de las ramas quedan financiadas: con S=3, un presupuesto libre de $13.29 dividido por una reserva de $0.797 financia 16 de 50 generaciones y rechaza 34.

## Leer los registros: síntoma a dimensión equivocada

**Tabla 6: Síntoma frente a la dimensión de presupuesto que está mal**

| Lo que ves | Qué dimensión está mal | Señal de confirmación | Dónde se trata |
|---|---|---|---|
| Corte invisible: finalizaciones de aspecto normal, salida sistemáticamente más corta | Respuesta terminal / observabilidad | La distribución de razones de parada muestra paradas parciales mientras el estado persistido dice COMPLETED | Artículo complementario, el momento del impacto |
| Demasiado ajustado: el presupuesto se detiene en la iteración 2 o 3 | Cuantil de dimensionado / factor de seguridad | Los recuentos de tokens de las ejecuciones cortadas están cerca del p50, las entradas parecen ordinarias | El factor de seguridad es derivado |
| Decorativo: cero paradas por presupuesto en una ventana larga | Magnitud del tope | Sin denegaciones en la ventana observada; el coste máximo de ejecución observado está muy por debajo del tope | Artículo complementario, la prueba de apertura |
| Sobrepaso / tope tardío: el coste realizado supera el tope por una cantidad consistente, del tamaño del modelo, en la cola | Punto de aplicación / proyección | Peor exactamente donde el paso es más caro | Artículo complementario, sobre la brecha de una iteración |
| Vinculación de alcance equivocada: las denegaciones son ~100% del nivel grueso | Alcance / orden de los guardas | Los topes por paso nunca actúan como restricción | Artículo complementario, las cinco partes de un objeto presupuesto |
| Inanición de fan-out: se generan N ramas, hay menos de N ejecuciones | Dimensionado del padre / política de reserva | Denegaciones atribuidas a la reserva del padre, no al presupuesto del hijo | Topes de ejecución y fan-out |
| Fuga de reservas: rechazos crecientes a lo largo de días, mismas entradas y misma M | Ciclo de vida de la reserva | Reservas retenidas que nunca se liquidan | Topes de ejecución y fan-out |
| Unidad equivocada: recuentos de iteraciones idénticos, dispersión de coste de órdenes de magnitud | Unidad | El coste medio por iteración abarca ~430x entre arquetipos ($0.00030 clasificar frente a $0.128 navegador) | Dimensionar un presupuesto por paso |

Un presupuesto configurado no es un presupuesto aplicado, y hay evidencia ya publicada. LiteLLM aceptaba \`max_budget\` y \`budget_duration\` en modelos añadidos dinámicamente a través de su API, persistía los valores, y nunca los aplicaba, mientras que la configuración idéntica en el archivo de arranque sí funcionaba ([issue #25799](https://github.com/BerriAI/litellm/issues/25799), cerrado por un PR posterior). Un defecto hermano cubría presupuestos que nunca se reiniciaban tras expirar su duración ([#25495](https://github.com/BerriAI/litellm/issues/25495)). Verifica la aplicación en un test. No la des por supuesta a partir de la presencia de un campo.
`;

export default content;
