// workflow-beats-do-everything-agent - es
const content = `La demostración de un único agente autónomo siempre impresiona. Le das un objetivo, piensa, llama a herramientas, y vuelve con una respuesta. Luego lo pones en producción y llega la factura, los resultados se tambalean, y nadie puede decirte por qué hizo lo que hizo.

El problema no es el modelo. El problema es la forma. Un agente que lo hace todo es la forma equivocada para la mayor parte del trabajo real.

## Coste: el contexto es el contador

Cada vez que un agente llama al modelo, reenvía su contexto. Las instrucciones, el historial, cada resultado de herramienta hasta el momento. Un agente que lo hace todo acumula todo eso en una sola conversación larga, y el contexto crece con cada paso.

Pagas por ese contexto en cada llamada. Una tarea de diez pasos no cuesta diez llamadas pequeñas. Cuesta diez llamadas que cada una arrastra una pila creciente de todo lo que vino antes.

Un flujo de trabajo descompone la tarea en pasos acotados y alimenta a cada uno solo con lo que necesita. El paso de clasificación ve el mensaje. El paso de borrador ve el mensaje y la categoría. El paso de envío ve el borrador aprobado. Ningún paso arrastra todo el historial consigo.

Alimenta a cada agente con una porción estrecha en lugar de con toda la transcripción y el recuento de tokens cae con fuerza. En la práctica la misma tarea se ejecuta unas diez veces más barata. Eso no es un truco. Es el resultado directo de no pagar por reenviar contexto que un paso dado nunca usa.

## Control: ramificación determinista frente a improvisación

Un agente que lo hace todo decide su propio camino en tiempo de ejecución. A veces toma el correcto. A veces inventa uno nuevo. Estás confiando en que un sistema probabilístico tome la misma decisión de encaminamiento cada vez, y no lo hará.

Un flujo de trabajo hace el encaminamiento explícito. Una consulta de facturación baja por la rama de facturación porque el grafo lo dice, no porque al modelo le apeteciera esta vez. El juicio difuso (¿esto es facturación o un error?) sigue ocurriendo dentro de un paso. La decisión estructural (qué le pasa a un elemento de facturación) está fija.

Esa separación es la clave de todo. Deja que el modelo haga lo que solo un modelo puede hacer, que es leer y juzgar. No dejes que improvise las partes que necesitas que sean fiables.

## Auditabilidad: un camino que puedes señalar

Cuando un solo agente lo hace todo en un único bucle, el registro es un muro de razonamiento y llamadas a herramientas. Reconstruir lo que realmente ocurrió es arqueología.

Un flujo de trabajo te da una ejecución que puedes leer. Aquí está la entrada. Aquí está la rama que tomó. Aquí está lo que cada paso recibió y devolvió. Aquí está el coste de cada paso. Aquí está quién aprobó antes del envío. Cuando alguien pregunta por qué un cliente recibió una respuesta concreta, respondes desde el rastro en lugar de adivinar.

## Depuración: una superficie acotada

Un gran agente que falla te da un único fallo gigantesco al que mirar. ¿Fue el plan, un mal resultado de herramienta, una instrucción perdida veinte turnos atrás? No puedes aislarlo, porque todo comparte un mismo contexto.

Un flujo de trabajo falla en un nodo. El paso de borrador produjo el tono equivocado, así que abres el paso de borrador. Sus entradas están ahí mismo. Cambias ese paso, reejecutas, y dejas el resto intacto. Pequeño, acotado y repetible, como funciona la depuración de software normal.

## Seamos justos: cuándo un solo agente es la decisión correcta

Los flujos de trabajo acotados no siempre son la respuesta, y fingir lo contrario es su propia clase de exageración.

Recurre a un único agente autónomo cuando:

- **La tarea es genuinamente abierta.** Investigación exploratoria, o depuración donde el siguiente movimiento depende por completo del último resultado. No puedes dibujar las ramas de antemano porque todavía no existen.
- **El camino es corto y barato.** Una consulta única o un borrador rápido no necesitan un grafo. Un grafo sería sobrecarga.
- **Todavía estás descubriendo la forma.** Al principio, deja que un agente deambule y observa qué hace en realidad. Las partes estables de ese comportamiento son exactamente lo que después elevas a un flujo de trabajo.

La regla honesta: si puedes dibujar los pasos, construye un flujo de trabajo. Si de verdad todavía no puedes dibujarlos, un agente es la herramienta adecuada, por ahora.

## El híbrido: el flujo de trabajo orquesta, los agentes hacen las partes difusas

Los mejores sistemas de producción no son uno u otro. Son un flujo de trabajo con agentes dentro.

El flujo de trabajo es dueño de la estructura: los disparadores, las ramas, las fusiones, las aprobaciones, los reintentos, el presupuesto de cada paso. Es determinista donde el determinismo importa.

Dentro de nodos individuales, los agentes hacen el trabajo que necesita criterio: clasificar este mensaje, redactar esta respuesta, extraer estos campos, resumir este documento. Cada uno de esos agentes está acotado. Recibe una entrada clara, un conjunto pequeño de herramientas, un presupuesto que no puede superar, y devuelve una salida clara al paso que sigue.

Obtienes el perfil de coste de los pasos acotados, la fiabilidad de la ramificación explícita, y el razonamiento de un modelo exactamente donde el razonamiento ayuda. El agente maneja la subtarea difusa. El flujo de trabajo maneja todo lo demás, y se entrega como una app que puedes ejecutar, monitorizar y pasar a otra persona.

Empieza preguntando qué partes de tu tarea necesitan de verdad criterio. Envuelve esas en agentes acotados. Conecta el resto como un grafo. Esa es la forma que sobrevive al contacto con la producción.
`;

export default content;
