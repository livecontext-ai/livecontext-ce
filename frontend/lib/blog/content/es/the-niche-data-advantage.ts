// the-niche-data-advantage - es
// Translated from the English body; structure identical. The evidence-register
// markers (cited / derived / "my judgment") and every concession are load-bearing:
// do not turn a hedge into a confident claim. Fenced formulas stay fenced.
const content = `## La selección de datos como obligación de actualización, con precio

No estás adquiriendo filas. Estás asumiendo una obligación de actualización. Un único parámetro medido, r, la fracción anual de tus registros que se vuelven incorrectos, fija cuatro cosas a la vez: el coste de mantenimiento, la cadencia de actualización, el término de mantenimiento en el punto de equilibrio entre construir y comprar, y cuánto tiempo sigue siendo útil la copia robada de un competidor. Un parámetro gobierna cuatro decisiones, pero el Resultado 4 más abajo muestra que debes medirlo por campo, y solo después de comprar dos requisitos previos: un oráculo independiente y un coste de verificación por registro conocido, k.

Este artículo pone precio a la tesis de los datos de nicho en lugar de elogiarla, y defiende primero el caso contrario, tan a fondo como la evidencia lo permite. También corrige el eslogan retirado del blog, «cien filas que entiendes ganan a un millón en las que confías a medias», que tal como está escrito es falso: la última sección da la condición bajo la cual se sostiene, y muestra que esa condición normalmente se desvanece.

Contrato de evidencia. Toda afirmación es una de tres cosas: citada con un enlace, derivada con la aritmética en la página, o etiquetada como mi juicio. Los números trabajados (N, k, r, las exactitudes) son supuestos ilustrativos, señalados en cada uso, no mediciones. Donde la investigación no arrojó nada, el artículo lo dice en lugar de estimar.

Delimitación de alcance. El coste del contexto, la aplicación y el dimensionamiento del presupuesto, los esquemas de registro de auditoría, la retención de auditoría, y convertir un conjunto de datos cualificado en un flujo de trabajo en funcionamiento son artículos complementarios. Este trata sobre la selección de datos y su economía, y se detiene en la decisión de adquirir.

Lector objetivo: un fundador o responsable técnico que elige en qué datos invertir antes de construir un agente o una automatización sobre ellos.

## El caso más sólido en contra (lee esto primero)

La tesis del foso de los datos propietarios está en disputa, y los escépticos tienen la mejor base de evidencia.

Lambrecht and Tucker, [Can Big Data Protect a Firm from Competition?](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2705530) (2015), someten los datos al test VRIN y encuentran que normalmente no lo supera: los grandes datos rara vez son inimitables o raros, existen sustitutos, y el recurso escaso es el conjunto de herramientas de gestión en torno a los datos, no los datos. Sus contraejemplos son entrantes (Airbnb, Uber, Tinder) que vencieron a incumbentes que ya poseían los datos relevantes.

Casado and Lauten, [The Empty Promise of Data Moats](https://a16z.com/the-empty-promise-of-data-moats/) (a16z, 2019), sostienen que los efectos de red de los datos suelen ser efectos de escala de los datos, y los efectos de escala se saturan. En su caso del chatbot de soporte, más allá de aproximadamente el 40% de las consultas recopiladas más datos no añaden ninguna ventaja, y la cobertura de intención tiende asintóticamente a cerca del 40%: nunca llega a la automatización completa en absoluto.

Varian, [NBER WP 24839](https://www.nber.org/papers/w24839) (2018), señala que la precisión estadística escala con la raíz cuadrada del tamaño de la muestra, de modo que necesitas cuatro veces los datos para reducir a la mitad tu error, y que los conjuntos de entrenamiento y prueba de ImageNet estuvieron fijos durante los años de mayores ganancias de exactitud, por lo que esas ganancias no pueden atribuirse a más datos.

Hestness et al., [arXiv:1712.00409](https://arxiv.org/abs/1712.00409), encuentran que el error de generalización cae como una ley de potencias en el tamaño del conjunto de datos con exponentes entre -0.07 y -0.35. Dado que el múltiplo de datos para reducir a la mitad el error es 2^(1/beta):

\`\`\`
beta = 0.07 -> 10x data cuts error 14.9%; halving needs ~19,972x data
beta = 0.15 -> 10x data cuts error 29.2%; halving needs ~102x data
beta = 0.35 -> 10x data cuts error 55.3%; halving needs ~7x data
\`\`\`

Esto corta en ambos sentidos: los exponentes planos significan que la ventaja de 100x de un competidor compra poco, pero tu 3x no compra casi nada.

Chiou and Tucker, [NBER WP 23815](https://www.nber.org/papers/w23815) (2017), aprovechan los recortes de retención impulsados por la UE (Bing de 18 meses a 6, Yahoo de 13 a 3) y encuentran poca degradación medible en la exactitud de búsqueda, concluyendo que «la posesión de datos históricos confiere menos ventaja en cuota de mercado de lo que a veces se supone». Allcott, Castillo, Gentzkow, Musolff and Salz, [NBER WP 33410](https://www.nber.org/papers/w33410) (2025), encuentran que eliminar las fricciones de demanda duplica la cuota de Bing mientras que los mandatos de compartición de datos tienen efectos pequeños. El foso era la distribución y los valores por defecto.

Cara a cara, el gran corpus genérico sigue ganando. [Li et al.](https://arxiv.org/html/2305.05862) informan de que GPT-4 supera a BloombergGPT (50B de parámetros, 363B de tokens financieros propietarios más 345B generales, según el [BloombergGPT paper](https://arxiv.org/pdf/2303.17564)) en ConvFinQA 0-shot 76.48% frente a 43.41%, FiQA-SA 5-shot 88.11% frente a 75.07%, y Financial PhraseBank 5-shot 0.97 frente a 0.51 F1. [Nori et al.](https://arxiv.org/abs/2311.16452) superan a Med-PaLM 2 en los nueve conjuntos de datos de MultiMedQA usando GPT-4 genérico más prompting, sin preentrenamiento ni ajuste fino específicos del dominio. Y Ovadia et al., [Fine-Tuning or Retrieval?](https://arxiv.org/html/2312.05934v3), encuentran que RAG supera consistentemente al ajuste fino para la inyección de conocimiento (Mistral 7B en una tarea de actualidad: base 0.481, RAG 0.875, ajuste fino 0.504). Si el valor de tus datos se materializa en una ventana de contexto, quien obtenga los documentos captura el mismo valor sin ninguna ejecución de entrenamiento.

Dos resultados de robustez atacan la mitad del eslogan del «millón de filas en las que confías a medias». [Subramanyam, Chen and Grossman](https://arxiv.org/abs/2510.03313) miden exponentes de calidad de alrededor de 0.173 (traducción automática) y 0.401 (modelado causal del lenguaje), ambos muy por debajo de 1, de modo que el tamaño efectivo del conjunto de datos decae sublinealmente con la calidad. [Muennighoff et al.](https://arxiv.org/abs/2305.16264) (NeurIPS 2023) encuentran que bajo un presupuesto de cómputo fijo con datos restringidos, hasta cuatro épocas de tokens repetidos son casi indistinguibles de datos únicos frescos.

El lado pequeño falla por su propia aritmética. El 95% CI sobre una proporción en p=0.5 es 1.96*sqrt(p(1-p)/n): n=100 da más o menos 9.80 puntos, n=1,000 da 3.10, n=1,000,000 da 0.098. Y P(zero occurrences) = (1-rate)^n, de modo que 100 filas curadas tienen un 36.6% de probabilidad de contener cero instancias de un modo de fallo con frecuencia del 1%. Necesitas alrededor de 299 filas para tener un 95% de confianza de ver una vez un evento de 1 entre 100, alrededor de 2,995 para uno de 1 entre 1,000. Los datos pequeños que entiendes no pueden ver su propia cola.

Detrás de todo esto está el [Bitter Lesson](http://www.incompleteideas.net/IncIdeas/BitterLesson.html) (2019) de Sutton, y dos fracasos costosos. IBM reunió uno de los mayores corpus de salud propietarios mediante unos $4B en adquisiciones (Merge alrededor de $1B, [Truven $2.6B](https://techcrunch.com/2016/02/18/ibm-acquiring-truven-health-analytics-for-2-6-billion-and-adding-it-to-watson-health), más Phytel and Explorys) y [vendió Watson Health a Francisco Partners en 2022](https://www.fiercehealthcare.com/tech/ibm-sells-watson-health-assets-to-investment-firm-francisco-partners) por unos ~$1.065B reportados. Zillow cerró Zillow Offers en November 2021 tras una pérdida de $422M del segmento Homes en el Q3 2021 (Q3 2021 8-K), con el CEO citando la imprevisibilidad al pronosticar los precios de la vivienda ([AI Incident Database 149](https://incidentdatabase.ai/cite/149/)).

| Argumento | Resultado medido | Fuente | Lo que no zanja |
|---|---|---|---|
| Los datos no superan VRIN | Los datos rara vez son raros o inimitables; los entrantes vencen a incumbentes que poseen datos | Lambrecht and Tucker 2015 | Si los eventos no publicados de origen propio tienen sustitutos |
| Los efectos de escala se saturan | Cobertura marginal plana más allá del ~40% de las consultas recopiladas; la cobertura de intención tiende asintóticamente a cerca del 40% | Casado and Lauten 2019 | Conjuntos de datos cuyo valor es la frescura, no la cobertura |
| Precisión de raíz cuadrada | 4x datos para reducir a la mitad el error de estimación | Varian, NBER 24839 | Recuperación, donde la precisión no es el mecanismo |
| Rendimientos de ley de potencias | Exponentes de error -0.07 a -0.35 | Hestness et al. 2017 | Cualquier cosa fuera del entrenamiento de modelos |
| Recortes de retención inofensivos | Bing de 18 a 6 meses, sin pérdida de exactitud medible | Chiou and Tucker, NBER 23815 | Corpus operativos pequeños sin sustituto de escala |
| La distribución es el foso | Eliminar las fricciones de demanda duplica la cuota de Bing | Allcott et al., NBER 33410 | Mercados sin un canal de colocación por defecto |
| Lo genérico gana al dominio | GPT-4 sobre BloombergGPT en 3 de 3 tareas citadas | Li et al. 2305.05862 | Extracción estructurada, donde el mismo artículo muestra que los modelos con ajuste fino ganan |
| La recuperación gana al ajuste fino | Mistral 7B: 0.875 RAG vs 0.504 ajuste fino | Ovadia et al. 2312.05934 | Si los propios documentos son obtenibles |

## Lo que esa evidencia no cubre

Casi toda la base anti-foso se refiere al preentrenamiento de modelos a escala de frontera. El lector objetivo no está entrenando nada; está seleccionando datos para una ventana de contexto o una respuesta de herramienta. Que 363 mil millones de tokens financieros propietarios no lograran superar a GPT-4 dice poco sobre si 40,000 filas internas bien estructuradas constituyen una buena entrada para un agente.

El problema espejo actúa contra mi tesis con igual fuerza: casi todas las grandes victorias medidas de curación son también un resultado de corpus de entrenamiento. [FineWeb-Edu](https://arxiv.org/abs/2406.17557) eliminó aproximadamente el 91% de FineWeb (de 15T a 1.3T tokens) y elevó MMLU del 33% al 37% y ARC del 46% al 57% con un presupuesto fijo de 350B tokens, igualando el MMLU del corpus completo con unos 10x menos tokens que C4 y Dolma. [LIMA](https://arxiv.org/abs/2305.11206), [AlpaGasus](https://arxiv.org/abs/2307.08701) y DataComp son también resultados de entrenamiento. Transferirlos a la recuperación es un supuesto, y ningún estudio localizado mide ambos regímenes en la misma tarea.

El único estudio a gran escala del lado de la recuperación apunta en la dirección contraria, y este artículo no puede pasarlo de largo. [Nourbakhsh et al., "When Retrieval Doesn't Help"](https://arxiv.org/abs/2606.04127), un estudio biomédico de RAG a través de 5 modelos, 10 conjuntos de datos de QA, 4 métodos de recuperación y 4 corpus, encontró que la recuperación aportaba solo de 1 a 2 puntos sobre una base sin recuperación, y las fuentes curadas por expertos no rindieron mejor que las fuentes de legos. La restricción vinculante era la capacidad limitada del modelo para usar la evidencia recuperada, no la calidad del corpus. Es la única medición localizada en el régimen real del lector, es específica de la curación, y su hallazgo es que la curación no compró nada. El dominio es biomédico, por lo que su transferencia a otras tareas de recuperación está a su vez sin medir, pero es evidencia dentro del régimen y la tesis debe ser degradada a una hipótesis frente a ella.

Un resultado del lado de la recuperación sí apoya la curación. La exactitud de RAG sigue una U invertida, con un pico en torno a 10 a 20 pasajes en Natural Questions y cayendo más allá de 40 en Gemma-7B, Gemma-2-9B, Mistral-Nemo-12B y Gemini-1.5-Pro ([arXiv:2410.05983](https://arxiv.org/html/2410.05983v1), ICLR 2025). El daño proviene de los negativos difíciles, documentos casi acertados que puntúan alto y no contienen la respuesta. La curación se gana su sustento eliminando vecinos plausibles pero erróneos. Si ese mecanismo se transfiere es un juicio sin comprobar, no una respuesta a Nourbakhsh.

La brecha que el lector más desea cerrar está vacía. No encontré ninguna medición pública y metodológicamente transparente de lo que compra curar un corpus privado. El contenido de los proveedores afirma una exactitud del 95 al 99% sin base de referencia, metodología ni tamaño de muestra, algo que este artículo no citará. Tampoco encontré ni un solo caso medido del conjunto de datos de nicho de una organización pequeña superando a un corpus genérico en un entorno de agente en producción.

La Superficial Alignment Hypothesis de LIMA es un arma contra mi tesis: el conocimiento proviene casi por completo del preentrenamiento, y los conjuntos curados pequeños enseñan formato y estilo. Bajo esa lectura, un corpus de nicho curado compra formato, no comprensión. Así que la tesis no puede defenderse por volumen ni por conocimiento. Si sobrevive, sobrevive por la frescura, la cobertura de una superficie de decisión específica, y el coste, que son medibles, y que el resto de este artículo instrumenta.

## El único parámetro que importa: r, y lo que te cuesta

Mídelo, no lo cites, y mídelo como un diseño de dos puntos temporales: muestrea registros verificados en t0, vuelve a comprobarlos en t0+delta contra un oráculo independiente, cuenta los campos cambiados, r = -ln(1-p)*365/delta_days. Informa del intervalo de confianza: en p=0.3, n=100, el CI sobre r va aproximadamente del 21% al 39%, lo que se propaga a cada cifra derivada más abajo (Mb de unos $7,400 a $15,400, un umbral de construir-gana-a-nada de aproximadamente 723 a 1,059 en lugar de un único número seguro). La crítica de muestra pequeña de arriba se aplica también a tu propio r.

Modelo, derivado aquí. Bajo un riesgo constante, un registro verificado en t=0 sigue siendo correcto con probabilidad A(t) = e^(-lambda*t), donde lambda = -ln(1-r). Para mantener un suelo de exactitud en el peor caso A_floor, actualiza cada T años:

\`\`\`
lambda        = -ln(1 - r)
T             = ln(1/A_floor) / lambda
passes / year = lambda / ln(1/A_floor)
maintenance   = N * k * lambda / ln(1/A_floor)
\`\`\`

Una comprobación de consistencia: el decaimiento de contactos mensual citado del 2.1% da 12 * -ln(1-0.021) = 0.2547, y -ln(1-0.225) = 0.2549. Circulan como cifras separadas y son la misma cifra compuesta, hasta tres decimales.

**Resultado 1.** Si actualizas exactamente a la cadencia que mantiene A_floor, la exactitud media a lo largo del ciclo es (1-A_floor)/ln(1/A_floor), que depende solo del suelo, no de r. Un suelo del 95% siempre promedia 97.48%, un suelo del 90% 94.91%, un suelo del 99% 99.50%. La tasa de cambio fija el precio del suelo, nunca la calidad que obtienes por él.

**Resultado 2.** Los pases por año son lambda/ln(1/A_floor), así que respecto a un suelo del 90% un suelo del 95% cuesta 2.05x, uno del 99% 10.48x, uno del 99.9% 105.31x. Elige el suelo a partir del coste de una decisión equivocada.

**Resultado 3.** La reverificación continua debe hacerse por orden de antigüedad (los más antiguos primero). La reverificación aleatoria a una tasa v alcanza una media en estado estacionario de v/(v+lambda) pero no tiene suelo alguno: las edades de los registros se distribuyen exponencialmente, de modo que una cola de registros está arbitrariamente desactualizada sin importar cuánto gastes. Empezar por los más antiguos equivale a por lotes y acota el peor caso; la aleatoria no.

**Resultado 4, la palanca mayor.** Mide r por campo. Un conjunto de datos 80% estable (r=2%) y 20% volátil (r=30%) cuesta 6.954 pases completos/año de manera uniforme, frente a 0.2*6.954 + 0.8*0.394 = 1.706 equivalentes de pase segmentado, un ahorro de 4.08x con un suelo idéntico del 95%. Esto supone que el coste de verificación escala con la fracción de campos tocados; un componente fijo por registro (obtención, emparejamiento, cambio de contexto) reduce el ahorro hacia 1x.

Advertencia sobre el modelo: el riesgo constante es una simplificación, y es comprobable. Traza la curva de supervivencia en un eje logarítmico; si no es recta, ajusta una Weibull S(t) = exp(-(t/eta)^k), lo que da T = eta*(ln(1/A_floor))^(1/k). Los datos de deterioro de enlaces de Pew están cargados al inicio, que es el caso k<1 (un riesgo decreciente, con fuerte pérdida temprana). Bajo k<1 la exponencial subestima la pérdida temprana y sobreestima la supervivencia tardía, por lo que la primera actualización debe llegar antes de T.

Para las fuentes que no controlas, [When Online Content Disappears](https://www.pewresearch.org/data-labs/2024/05/17/when-online-content-disappears/) (2024) de Pew es el único anclaje externo limpio que encontré: el 38% de las páginas que existían en 2013 habían desaparecido para October 2023, pero el 8% de las páginas de 2023 ya habían desaparecido en el plazo de un año. El riesgo medio a diez años es -ln(0.62)/10 = 0.0478/yr, pero el riesgo del primer año observado directamente es del 8%. Usa el 8% para fijar la cadencia en fuentes frescas.

Una advertencia de procedencia: la cifra de contactos B2B del 22.5% anual se remonta a MarketingSherpa a través de [HubSpot's Database Decay Simulation](https://www.hubspot.com/database-decay), replicada por proveedores de generación de leads con un interés comercial y sin metodología ni tamaño de muestra publicados. Aplícala a las listas de contactos B2B y a nada más. Las tasas de decaimiento para catálogos de productos, precios, corpus regulatorios, documentación geoespacial y técnica parecen no estar publicadas. La tabla es un modelo en el que introducir tu propio r.

| Tasa de cambio anual r | lambda | Días entre actualizaciones, suelo 95% | Días, suelo 90% | Pases completos/año, suelo 95% | Semivida de una copia única |
|---|---|---|---|---|---|
| 2% | 0.0202 | 927 | 1,904 | 0.39 | 34.3 yr |
| 5% | 0.0513 | 365 | 750 | 1.00 | 13.5 yr |
| 10% | 0.1054 | 178 | 365 | 2.05 | 6.58 yr |
| 22.5% (solo contactos B2B) | 0.2549 | 73.5 | 151 | 4.97 | 2.72 yr |
| 30% | 0.3567 | 52.5 | 108 | 6.95 | 1.94 yr |
| 60% | 0.9163 | 20.4 | 42.0 | 17.87 | 0.76 yr |

## El cuadro de puntuación: siete filas, dos compuertas

Símbolos usados más abajo y definidos aquí: D son las decisiones por año, v es el valor neto por decisión correcta (la diferencia entre acertar y fallar, así que el coste del error ya está dentro), y D_be es el volumen de equilibrio entre construir y no hacer nada (Cb/H+Mb)/(v*(Ab-A0)) derivado en la siguiente sección. El umbral de la fila 3 usa el valor anual de la decisión, escrito D por v.

| Criterio | Prueba que puedes ejecutar esta semana | Umbral | Puntuación 0-3 |
|---|---|---|---|
| 1. Enumerabilidad | Dos muestras independientes por dos rutas, solapamiento m, Chapman estimator | 3 si la cobertura >=95%; 2 si 90-95%; 1 si 75-90% y puedes nombrar el segmento excluido; 0 si no hay N-hat | |
| 2. Verificabilidad (COMPUERTA) | Nombra el oráculo independiente; mide k y los minutos por registro | Aprueba si k <= 1% de v y <= 10 min/registro | aprobado/fallido |
| 3. Asequibilidad del decaimiento | Reverifica los registros en dos puntos temporales, anualiza a r, calcula el mantenimiento como % de D por v | 3 si <=5%; 2 si 5-15%; 1 si 15-30%; 0 si >30% o r sin medir | |
| 4. Pulso | Últimas 12 versiones publicadas, coeficiente de variación de los intervalos entre publicaciones | 3 si CV <=0.25 y el intervalo máximo <=2x la mediana; 2 si CV <=0.5 o controlas la extracción; 1 si CV <=1.0 o los intervalos son irregulares pero acotados; 0 si no hay historial de versiones | |
| 5. Vínculo con la decisión (COMPUERTA) | Nombra la decisión, el actor, el valor por defecto, D por año; mide la tasa de divergencia a 90 días | Aprueba si D >= D_be y la divergencia >= 2% | aprobado/fallido |
| 6. No sustituibilidad | Pon precio a la replicación completa más barata en días de trabajo cualificado | 3 si la replicación está bloqueada legalmente (nombra el derecho de acceso); 2 si >180 días; 1 si 30-180 días; 0 si <30 días o un proveedor lo lista como un SKU | |
| 7. Integridad de la unión | Intenta la unión en una muestra de 500 filas, mide la tasa exacta de coincidencia de clave primaria | 3 si >=98%; 2 si 95-98%; 1 si 90-95%; 0 si <90% | |

**Fila 1** usa el Chapman estimator N-hat = ((n1+1)(n2+1)/(m+1)) - 1: n1=300, n2=250, m=180 da 416, de modo que tener 380 filas es una cobertura del 91.3%. Chapman supone igual capturabilidad, pero las entidades faltantes son sistemáticamente las más nuevas y remotas, lo que sesga N-hat a la baja. Así que N-hat es una cota inferior del universo y la cifra de cobertura una cota superior. Vuelve a ejecutar la recaptura restringida a las entidades vistas por primera vez en los últimos 12 meses como un segundo número obligatorio.

**La fila 2 es una compuerta** porque sin un oráculo no puedes medir r, así que las filas 1 y 3 quedan sin respuesta. k es también el multiplicando en la fórmula de mantenimiento, de modo que este único número pone precio a toda la obligación. Ten en cuenta que k = $0.40 en el ejemplo trabajado implica una verificación casi automatizada (aproximadamente un minuto por registro a $25.23/hr); la propia compuerta tolera 10 min/registro, que son $4.20, un orden de magnitud más.

**Fila 3, trabajada:** N=4,000, k=$0.40, r=30%, un suelo del 95% da 6.95 pases y $11,126/yr; segmentar al 20% de campos volátiles da $2,729. Ambos escalan linealmente con la k supuesta.

**La fila 4** hace medible el «cambia con un ritmo que puedes aprender» del artículo retirado: una fuente cuyo propio intervalo de publicación es más variable que tu T requerida hace que el suelo sea inaplicable a cualquier gasto.

**La fila 5 mata a la mayoría de los candidatos.** Nombra la decisión, el actor, el valor por defecto y D por año, y mide la tasa de divergencia a 90 días (con qué frecuencia los datos habrían cambiado la decisión). Por debajo del 2% los datos no están moviendo decisiones, un fallo rotundo. No encontré ningún estudio que midiera la divergencia en producción, así que el 2% es mi juicio.

**La fila 6** se empareja con la semivida de la copia única ln2/lambda, pero calcúlala por segmento de campo: un competidor copia el 80% estable (semivida de 34.3 años a r=2%) y vuelve a derivar el quinto volátil, de modo que la semivida a nivel de conjunto de datos exagera la defensibilidad. Informa del número del segmento estable.

**La fila 7** importa porque las exactitudes se multiplican: un conjunto de datos con 95% de exactitud unido al 90% entrega un 85.5% de exactitud efectiva. Aplica el factor de unión tanto a tu construcción como a cualquier conjunto de proveedor, ya que degrada cualquier conjunto de datos externo unido a tus claves.

Regla de puntuación, mi juicio: dos compuertas aprobado/fallido, cinco filas puntúan 0-3 para un máximo de 15, invierte en 11 con ambas compuertas aprobadas. Las pruebas son ejecutables y la aritmética detrás de las filas 1, 3 y 7 está en la página. Cada punto de corte numérico en la columna de umbral es mi juicio, calibrado con la experiencia, no derivado ni citado; muévelos. La función real del instrumento es forzar siete mediciones que llevan aproximadamente una semana.

Aplicado a los ejemplos intercambiables de los artículos retirados: el rendimiento de puntualidad de un transportista en una ruta, cada permiso comercial en un área metropolitana, las tarifas de reembolso de un pagador, y el precio y el stock de 40 SKUs comprobados dos veces al día difieren enormemente en las filas 3, 4 y 6. El conjunto de SKUs tiene un lambda de cientos por año (r fijado en esencialmente el 100%, porque r es una fracción acotada por debajo de 1; usa lambda directamente cuando el cambio es más rápido que anual) y una semivida de copia de días. El registro de permisos tiene un r cercano a cero y es trivialmente copiable.

## Lo que los datos cuestan realmente

Cada cifra lleva su grado de procedencia.

| Elemento | Proveedor | Precio publicado | Procedencia |
|---|---|---|---|
| Ancho de banda de proxy residencial | [Bright Data](https://brightdata.com/pricing/proxy-network/residential-proxies) | $8/GB PAYG, $5/GB en el nivel de $1,999/mo | Obtención de página primaria |
| Proxies de centro de datos / ISP | Bright Data | $1.30-$1.80 y $0.90-$1.40 por IP por mes | Obtención de página primaria |
| Scraping por nivel de dificultad | [Zyte](https://docs.zyte.com/zyte-api/pricing.html) | 5 niveles HTTP y 5 de navegador; ~$0.13-$1.27 y ~$1.01-$16.08 por 1,000 | Estructura de niveles primaria; tarifas reportadas por agregador |
| Complemento de captura de pantalla | Zyte | $0.002 cada uno | Documentación primaria |
| Herramientas de etiquetado | SageMaker Ground Truth | $0.08 / $0.04 / $0.02 por objeto (niveles 1-50k / 50-100k / >100k); 500 objetos/mo gratis los dos primeros meses | Reportado por agregador, posiblemente heredado, no publicado actualmente por AWS |
| Herramientas de etiquetado | Labelbox | $0.10 por Labelbox Unit, 1 LBU por fila etiquetada | Reportado por agregador |
| Etiquetado | [Scale AI](https://scale.com/pricing) | Sin tarifa empresarial publicada; solo nivel gratuito | Obtención de página primaria |
| Mano de obra de anotación en EE. UU. | [ZipRecruiter](https://www.ziprecruiter.com/Salaries/Data-Annotation-Salary) | ~$25.23/hr ($52,488/yr); deslocalizada ~$2 a $5-12/hr | Primaria; deslocalizada reportada por agregador |
| Datos de contacto B2B | [Vendr](https://www.vendr.com/buyer-guides/zoominfo) | Mediana de ZoomInfo $33,500/yr sobre 1,566 compras, rango $7,200-$155,550 | Datos de transacción verificados |
| Datos de mercado | [Databento](https://databento.com/pricing) | $199 / $1,750 / $4,500 por mes | Obtención de página primaria |
| Fuentes estrechas de un solo propósito | [Massive](https://massive.com/pricing) | NYSE Order Imbalances $49/mo; European Consumer Spending by Merchant $99/mo | Obtención de página primaria |
| Anuncios de marketplace | [AWS Data Exchange](https://aws.amazon.com/data-exchange/pricing/) | Fijado por el proveedor; $0.023/GB/mo de almacenamiento, $0.04167/hr de concesiones de datos | Obtención de página primaria |
| Anuncios de marketplace | Snowflake Marketplace | Por mes, por consulta o híbrido; anuncios reales $100-$1,500/mo | Documentación del proveedor más secundaria |
| Licencia de datos de entrenamiento | News Corp / OpenAI; Reddit / Google | >$250M en 5 años; ~$60M/yr (Reddit S-1: $203M agregados) | Información de prensa corroborada |
| Revisión legal del método de adquisición | Tu asesoría jurídica | Indicativo: revisión más DPIA, entre cinco cifras bajas y medias de pago único más gestión continua | Mi juicio, ninguna cifra transaccionada localizada |

Dos cifras derivadas, con los supuestos a la vista. Un corpus de nicho de un millón de páginas a unos 200KB por página supuestos (mi supuesto) son 200GB: unos $1,600 de ancho de banda residencial de Bright Data a precio de lista, frente a unos $130 a través de Zyte en el nivel HTTP 1 o unos $16,080 en el nivel de navegador 5, con dos órdenes de magnitud de diferencia, decidido por el nivel en el que caiga el objetivo. Etiquetar 100,000 registros en los niveles de Ground Truth de arriba son 50,000*$0.08 + 50,000*$0.04 = $6,000 en herramientas (la asignación de 500 gratis es irrelevante, y estos niveles por objeto posiblemente estén heredados, ya no publicados por AWS), o 100,000*$0.10 = $10,000 en unidades de Labelbox, ambos excluyendo la mano de obra humana, la línea mayor.

El agujero honesto: no encontré ningún precio transaccionado de mercado medio para licenciar o construir un conjunto de datos de dominio de 10,000 a 100,000 filas. El rango publicado va desde unos $0.01 por etiqueta hasta $250M por acuerdo, aproximadamente diez órdenes de magnitud, con el medio sin documentar. Tampoco hay un punto de referencia público para k, el coste por registro verificado, el insumo al que el punto de equilibrio de abajo es más sensible.

## Construir, comprar o no hacer nada

El género compara construir frente a comprar y nunca pone a prueba la tercera opción. No hacer nada tiene un valor neto positivo, D*v*A0, y el modelo de abajo vence a ambas alternativas a cada volumen por debajo del punto de equilibrio con estas entradas.

\`\`\`
Nothing = D*v*A0
Buy     = D*v*Av - L
Build   = D*v*Ab - (Cb/H + Mb)

Build beats buy     when D > (Cb/H + Mb - L) / (v*(Ab - Av))
Build beats nothing when D > (Cb/H + Mb)     / (v*(Ab - A0))
Buy   beats nothing when D > L               / (v*(Av - A0))
\`\`\`

Cb es la adquisición única, H el horizonte de amortización, Mb el mantenimiento por periodo, L la licencia por periodo, v el valor neto por decisión correcta (ya es la diferencia entre acertar y fallar, así que el coste del error está dentro; si prefieres el valor bruto más un coste de error separado c, sustituye v por v+c).

Entradas trabajadas, todas supuestos ilustrativos, no mediciones: N=4,000, Cb=$30,000, H=3 años, L=$18,000/yr, v=$60, Ab=0.95, Av=0.78, A0=0.55, r=30%, k=$0.40. Mb=$11,100/yr se deriva de ellas (4,000*$0.40*6.95 pases a un suelo del 95%), lo que no es lo mismo que conocido de forma independiente: hereda la k supuesta, y k no tiene un punto de referencia público. Una brecha Ab-A0 de 40 puntos es optimista; una brecha más pequeña y realista eleva los tres puntos de equilibrio y amplía el rango en el que no hacer nada gana.

Puntos de equilibrio en k=$0.40: construir gana a comprar por encima de (10,000+11,100-18,000)/(60*0.17) = 304/yr; construir gana a no hacer nada por encima de 21,100/(60*0.40) = 879; comprar gana a no hacer nada por encima de 18,000/(60*0.23) = 1,304.

Estos se invierten según k. En k=$0.40 la banda de compra está vacía (cota superior 304 por debajo de la cota inferior 1,304), así que comprar queda dominado. Pero la compuerta de la fila 2 tolera 10 min/registro, que a $25.23/hr son $4.20. En k=$4.20, Mb=$116,800: construir-gana-a-nada pasa de 879 a 5,283, y la banda de compra se abre a aproximadamente 1,304 a 10,667. La banda se abre en cuanto k supera unos $0.75. Así que «comprar queda dominado a cualquier volumen» solo se sostiene bajo una verificación casi automatizada. No es un resultado general, y se retracta para la verificación manual.

| Decisiones/yr | No hacer nada | Comprar a $18,000/yr | Construir ($30k en 3yr + $11.1k/yr) | Ganador |
|---|---|---|---|---|
| 294 | $9,702 | -$4,241 | -$4,342 | Nada |
| 879 | $29,007 | $23,137 | $29,003 | Nada (punto de cruce) |
| 1,304 | $43,032 | $43,027 | $53,228 | Construir |
| 2,000 | $66,000 | $75,600 | $92,900 | Construir |

| Exactitud del proveedor Av | Cota inferior de la banda de compra | Cota superior de la banda de compra | Banda (k=$0.40) |
|---|---|---|---|
| 0.78 | 1,304 | 304 | Vacía |
| 0.85 | 1,000 | 517 | Vacía |
| 0.90 | 857 | 1,033 | Abierta, 857-1,033 |
| 0.93 | 789 | 2,583 | Abierta, 789-2,583 |

Comprar es lo correcto precisamente cuando el proveedor es casi tan exacto como lo serías tú en tu propia superficie, lo cual es una cuestión sobre tu subconjunto, no sobre su marketing. Muestrea 200 registros del proveedor dentro de tu nicho y mide Av antes de firmar. La sensibilidad sobre v escala como 1/v: a $6 en lugar de $60, construir gana a comprar solo por encima de 3,100/(6*0.17) = unos 3,040/yr.

El modelo también omite la opción que suele dominar en el régimen de este lector: compra el grueso copiable y construye solo la columna de resultado que nadie puede raspar. En una ventana de contexto tienes ambos, así que rara vez hay razón alguna para elegir un conjunto de datos sobre el otro.

Ahora el bucle de cadencia. Tu ventaja sobre un competidor es la brecha de exactitud media derivada de una cadencia más rápida, donde la exactitud media a lo largo del intervalo T es (1-e^(-lambda*T))/(lambda*T). En r=30%, la actualización mensual da una media del 98.53%, la anual da 84.11%, una brecha de 14.4 puntos. El coste incremental de mensual sobre anual son 11 pases extra, 11*4,000*$0.40 = $17,600/yr, así que la brecha de cadencia solo compensa por encima de 17,600/(60*0.144) = unas 2,034 decisiones/yr, por encima de ambos puntos de equilibrio trabajados. Y no es un foso de datos: un competidor que dote de personal la misma tubería de actualización lo borra. Lo defensible es una cadencia operativa, un hecho de contratación y herramientas, no un hecho de datos.

## Cuatro formas en que esto sale mal después de comprar

| Modo de fallo | Síntoma que verás realmente | Método de detección | Umbral de disparo |
|---|---|---|---|
| Ilusión de cobertura | El backtest bien, el rendimiento en vivo en casos nuevos malo, con la brecha ampliándose | Captura-recaptura (fila 1) en entidades vistas por primera vez en los últimos 12 meses | Cobertura de entidades nuevas más de 15pp por debajo del total |
| Obsoleto pero confiable | Respuestas seguras construidas sobre campos que nadie ha tocado en años | Obsolescencia ponderada por lecturas: fracción de lecturas que caen en filas más antiguas que T | Más del 5% de las lecturas más allá de la cadencia del suelo |
| Deriva de decisión | Tubería en verde, datos actualizándose, la acción de nadie cambia | Tasa de divergencia a 90 días (fila 5) | Por debajo del 2%, elimina el conjunto de datos |
| Precipicio de mantenimiento | k salta, un pase de actualización falla en silencio, una fuente empieza a bloquearte, un campo significa algo nuevo | Concentración de fuentes, k interanual, y tasa de bloqueo de fuentes | Cualquier fuente individual >50% de las filas, k sube >25% interanual, o una fuente raspada que te rechaza |

A mi juicio, el déficit en una cifra de cobertura no es aleatorio: se concentra en las entidades más nuevas, más pequeñas y más remotas, exactamente el segmento del que trata la decisión. Si tus rutas de muestreo se correlacionan con la edad de la entidad, ejecuta la fila 1 por separado en los últimos 12 meses para averiguarlo.

La ponderación por lecturas importa porque el 5% caliente de las filas suele ser el que se consulta (mi supuesto, comprobable mediante la propia medida ponderada por lecturas); si también son las volátiles, la frescura ponderada por registros te halaga. Añade una columna verified_at o no se podrá ejecutar nada del modelo de este artículo. La deriva de decisión sobrevive más tiempo porque todos los cuadros de mando se leen como sanos. Una fuente que empieza a rechazarte es a la vez un precipicio de mantenimiento y una señal legal. Los umbrales de estas filas son mi juicio; el riesgo base para las fuentes web no controladas es el 8% del primer año de Pew.

## Dónde se sostiene la tesis de nicho, y dónde no

Mi contribución, ofrecida como juicio: la defensibilidad es proporcional al coste de actualización, y los datos propietarios por sí solos no son un foso. El argumento de Lambrecht and Tucker se mantiene: el recurso escaso es el conjunto de herramientas operativas en torno a los datos. Lo que puede ser defensible es una cadencia de actualización mantenida envuelta alrededor de un bucle de decisión cerrado, y solo mientras ningún competidor dote de personal la misma tubería. Eso es una carrera de contratación, no una ventaja de datos. «Encuentra datos baratos de mantener» y «encuentra datos que sean defensibles» son, por tanto, instrucciones opuestas, y a la mayoría de los fundadores se les entregan ambas.

Di el marcador claramente. La antítesis tiene dos fracasos documentados (Watson Health, Zillow) y seis hilos empíricos. La pro-tesis tiene cero casos de producción nombrados en el régimen de este lector: ninguna medición transparente de lo que compra curar un corpus privado, y el único estudio de recuperación en el objetivo encontró que no compra nada. Los fracasos de esta clase quedan sin publicar, de modo que la muestra está seleccionada por supervivencia. Trata la tesis como una hipótesis que este artículo instrumenta, no como un resultado que demuestra. Su prueba de falsación: mide la divergencia y el incremento de exactitud efectiva en tu propia superficie; si el incremento está dentro del ruido, la tesis ha fracasado para ti.

Cuatro condiciones bajo las cuales podría sobrevivir.

1. **Los datos registran una decisión que solo tú tomas, no un cuerpo de conocimiento.** El objeto defensible es el bucle cerrado de decisión, resultado, registro etiquetado, porque la columna de resultado no puede rasparse, solo ganarse. Esta es la única condición coherente con toda la evidencia anterior: sin pretensión de hechos raros, sin dependencia de la escala, no inferible a partir de texto público.
2. **Observación de origen propio de eventos que no dejan un rastro público unido.** Un evento que observas todavía deja una huella con tu contraparte, un intermediario o un procesador (la fuente de gasto de comerciantes de $99/mo de arriba es exactamente datos de transacción revendidos). Pero nadie más posee el registro unido de evento, contexto y resultado bajo tu clave. Esa unión es el objeto defensible, no el evento.
3. **Alto decaimiento, entendido como un coste recurrente en lugar de una barrera.** Un conjunto que decae rápido no puede robarse una sola vez, solo mantenerse, así que es defensible solo mientras conserves la brecha de cadencia, que un competidor puede arrebatar contratando. En r=30% una instantánea única está un 23.5% equivocada en el plazo de 9 meses, pero un competidor que también construye una máquina de actualización no pierde nada.
4. **Suficientemente pequeño para verificar exhaustivamente.** Con 4,000 registros y k=$0.40 un suelo del 99% a r=30% cuesta unos $56,800/yr; con 400,000 registros son unos $5.68M y nadie lo compra. Ambos escalan con la k supuesta.

Dónde no se sostiene: (a) un proveedor lo vende como un SKU (alquílalo, mira las fuentes de $49 a $99/mo de arriba); (b) bajo decaimiento más fuentes públicas (tu copia y la suya envejecen juntas, así que compites en distribución, donde los experimentos naturales dicen que el foso realmente estaba); (c) por debajo del volumen de decisiones de equilibrio; (d) sin oráculo independiente (no puedes medir r, así que no puedes poner precio a nada aquí); (e) la tarea es razonamiento o semántica en lugar de búsqueda y extracción estructurada (GPT-4 sobre BloombergGPT, prompting genérico sobre Med-PaLM 2); (f) divergencia por debajo del 2%; (g) el método de adquisición está contractual o legalmente prohibido en la fuente, así que pon precio a la asesoría jurídica antes que al raspador.

Finalmente, el eslogan retirado, conservado como una condición. La exactitud efectiva es c*A_small + (1-c)*A0 solo si recurres a la base de referencia fuera de la superficie curada. En una ventana de contexto normalmente tienes ambos conjuntos, así que la exactitud efectiva es c*A_small + (1-c)*A_big, que es al menos A_big para todo c>0: el conjunto pequeño y limpio nunca es peor, y no hay umbral alguno. Un umbral existe solo donde los dos son mutuamente excluyentes, lo cual para la recuperación rara vez ocurre. Bajo esa exclusividad, con A_small=0.99, A0=0.55 y A_big=0.60 (todo supuesto), la cobertura de equilibrio es (0.60-0.55)/(0.99-0.55) = 11.4%; en A_big=0.65 se duplica a 22.7%. Así que la respuesta es la cobertura, no el número de filas, y está dominada por lo buena que ya sea la base de referencia genérica en tu superficie, lo cual puedes medir.

El trabajo de la semana: compra los dos requisitos previos, un oráculo y un coste por registro k; mide r por campo con su intervalo de confianza; ejecuta las siete filas; calcula tus tres puntos de equilibrio con k variada a lo largo del rango que impliquen tus propios costes de mano de obra. Solo entonces decide. Si el conjunto de datos cualifica, [from-dataset-to-live-workflow](/blog/from-dataset-to-live-workflow) cubre lo que sucede a continuación.
`;

export default content;
