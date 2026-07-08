// cap-ai-agent-cost-budgets - es
const content = `La mayoría de las historias de terror sobre costes de IA comparten una misma causa raíz: un agente sin techo. Entró en bucle, reintentó, arrastró un contexto enorme, y nadie se enteró hasta que lo hizo la factura. El arreglo no es un modelo más listo. Es un presupuesto firme en cada agente, aplicado antes de que ocurra el gasto, no después.

## Por qué los agentes sin límite son un riesgo financiero

Un agente autónomo decide su propio siguiente paso. Esa es la característica, y también es la exposición. Tres modos de fallo convierten una tarea normal en un grifo abierto.

**Bucles.** El agente intenta algo, no funciona, intenta una variación, y sigue adelante. Sin un límite quemará llamadas persiguiendo un objetivo que no puede alcanzar.

**Reintentos.** Una herramienta inestable o un límite de tasa dispara un reintento. Los reintentos se apilan. Lo que parecía una llamada se vuelve veinte, cada una pagando el coste completo del contexto.

**Contextos largos.** Cada llamada al modelo reenvía toda la conversación hasta el momento. Una tarea que acumula historial paga más en cada paso que en el anterior. La última llamada de una ejecución larga puede costar muchas veces la primera.

Ninguno de estos es raro. Son el comportamiento normal de un sistema al que se le da un objetivo y ningún techo. Un presupuesto convierte ese riesgo abierto en un número conocido y limitado.

## Qué debería limitar un presupuesto por agente

Un presupuesto solo es útil si detiene el trabajo cuando se alcanza. Debería limitar las cosas que de verdad impulsan el coste y el tiempo de ejecución:

- **Gasto total.** Un techo firme en créditos o tokens. Cuando el agente lo alcanza, se detiene. Sin excederse, sin "solo un poco más".
- **Número de llamadas al modelo.** Limita el bucle directamente. Un agente que no puede hacer una vigésimo primera llamada no puede entrar en bucle para siempre.
- **Llamadas a herramientas.** Algunas herramientas cuestan dinero o topan con cuotas externas. Limita cuántas veces un agente puede recurrir a ellas.
- **Tiempo de reloj.** Un agente atascado no debería correr durante una hora. Ponle un tiempo límite.

La regla que hace real un presupuesto: cuando se alcanza el tope, el agente se detiene y el flujo de trabajo lo maneja. No sigue en silencio, y no falla en silencio. Se detiene, y la ejecución registra que se detuvo porque alcanzó su presupuesto.

## Acota las herramientas y los datos que un agente puede tocar

El presupuesto es la mitad de la respuesta. El alcance es la otra mitad, y reduce el coste antes de que haga falta ningún tope.

Un agente que puede verlo todo intentará usarlo todo. Dale toda la base de datos y razonará sobre toda la base de datos, y pagas por los tokens. Dale solo las herramientas y los datos que el paso necesita, y se mantiene pequeño por construcción.

Para un paso de clasificación, eso significa el texto del mensaje y una herramienta para devolver una etiqueta. Nada más. Para un paso de borrador, el mensaje y la categoría. Un agente estrictamente acotado es más barato en cada llamada porque su contexto es pequeño, y es más seguro porque no puede vagar hacia datos o acciones que no son su tarea.

El alcance estrecha el radio de impacto. El presupuesto limita lo que queda. Quieres ambos.

## Fija presupuestos por agente y por ejecución

Un solo número no basta. Necesitas presupuestos en dos niveles.

**Por agente.** Cada paso obtiene su propio techo dimensionado a su tarea. Una clasificación rápida debería tener un presupuesto diminuto. Un paso de investigación que lee varios documentos obtiene más. Dimensionar cada agente a su trabajo real significa que un paso codicioso no puede gastarse toda la asignación.

**Por ejecución.** El flujo de trabajo completo también obtiene un techo. Aunque cada agente individual se mantenga dentro de su propio presupuesto, una ejecución que se abre en cientos de ramas paralelas puede sumar. Un tope a nivel de ejecución protege contra la suma, no solo contra las partes.

Juntos te dan un sobre predecible: un peor caso conocido por paso y un peor caso conocido para la ejecución. Eso es lo que convierte el "coste de IA" de una sorpresa en una partida que puedes planificar.

## Monitoriza el gasto por agente y por herramienta

Los presupuestos detienen el coste descontrolado. La monitorización te dice dónde vive de verdad el coste para que puedas afinarlo.

Rastrea el gasto con grano fino:

- **Por agente.** ¿Qué paso cuesta más? A menudo es un nodo que hace más de lo que necesita, que carga demasiado contexto, o que usa un modelo más grande del que la tarea requiere.
- **Por herramienta.** ¿Qué llamadas a herramientas dominan? Una única API externa cara llamada en cada elemento puede convertirse en silencio en el grueso de la factura.
- **Por ejecución.** ¿Cuánto cuesta una ejecución típica, y cuánto cuesta una mala? La brecha entre ambas es donde se esconden tus bucles y reintentos.

Con esta vista afinas de forma deliberada. Recorta el contexto de un paso. Bájalo a un modelo más barato donde la calidad lo permita. Añade una salvaguarda de deduplicación para que una herramienta no se llame dos veces para el mismo elemento. Cada cambio es medible porque puedes ver moverse el número.

## Súmalo todo

El coste de IA descontrolado es un problema de diseño, no un problema de modelado. Lo resuelves estructuralmente.

Acota cada agente a las herramientas y los datos que su tarea necesita. Dale a cada agente un presupuesto firme que no pueda superar. Pon un techo a la ejecución completa. Vigila el gasto por agente y por herramienta, y afina donde de verdad va el dinero.

Haz eso y el coste deja de ser lo que te impide lanzar. Se convierte en un número que fijas a propósito, aplicas de forma automática, y puedes defender línea por línea.
`;

export default content;
