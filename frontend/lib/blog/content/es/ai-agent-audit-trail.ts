// ai-agent-audit-trail - es
const content = `Un agente de IA que funciona en la demostración ha probado exactamente una cosa: puede funcionar una vez. La producción hace una pregunta más dura. Cuando haga algo mal, y lo hará, ¿puedes averiguar qué pasó y por qué? Si la respuesta es no, no tienes un sistema que puedas ejecutar. Tienes un sistema sobre el que albergas esperanzas.

Lo que convierte la esperanza en operación es un registro de auditoría. Un registro completo de lo que hizo el agente, en cada ejecución, que puedas leer después de los hechos.

## Por qué "funcionó en la demo" no basta

Una demo es un único camino feliz bajo una entrada amable. La producción son miles de ejecuciones bajo entradas que nunca anticipaste. Una fracción sale mal: una clasificación equivocada, una herramienta que devolvió basura, una acción tomada sobre el registro equivocado.

Cuando una de esas aflora, normalmente como una queja, necesitas responder tres preguntas rápido. ¿Qué vio el agente? ¿Qué hizo? ¿Por qué eligió eso? Sin un rastro estás reconstruyendo una decisión de un sistema probabilístico después de los hechos, lo que equivale a decir que estás adivinando.

Un rastro reemplaza la suposición por un registro. Esa es toda la diferencia entre un agente que operas y uno que meramente despliegas.

## Qué registrar

Un registro de auditoría es tan bueno como lo que captura. Registra lo suficiente para que una ejecución pueda reproducirse por completo sobre el papel, sin volver a ejecutarla.

- **Entradas.** Lo que de verdad entró en el agente o en el paso. No un resumen, la entrada real. La mayoría de los informes de "la IA está rota" resultan ser una entrada mala o sorprendente, y no puedes verlo a menos que la hayas registrado.
- **Cada llamada a herramienta y su resultado.** Cada herramienta que el agente invocó, con lo que pasó y lo que le devolvió. Los resultados de herramientas son donde la realidad entra en la ejecución, y donde empiezan muchos fallos.
- **Salidas.** Lo que el agente produjo en cada paso y al final. La respuesta final, y las intermedias que llevaron a ella.
- **Coste.** Tokens y gasto por paso. Esta es tu factura y tu aviso temprano de un paso que está haciendo más de lo que debería.
- **La rama o decisión tomada.** Qué camino siguió la ejecución. Un elemento de facturación bajó por la rama de facturación: registra que lo hizo, para que puedas confirmar que el encaminamiento fue correcto.
- **Quién aprobó.** Para cualquier paso con compuerta humana, registra quién aprobó, cuándo, y qué vio al hacerlo. Las aprobaciones son la columna vertebral de la rendición de cuentas.

Captura eso y cualquier ejecución se convierte en una historia que puedes leer de principio a fin.

## Cómo el rastro te ayuda a depurar

Depurar sin un rastro es mirar fijamente una salida mala y teorizar. Depurar con uno es seguir un camino.

Abres la ejecución fallida. Lees la entrada y parece normal. Pasas al paso de clasificación y ves que devolvió la etiqueta equivocada. Compruebas lo que recibió, y el mensaje era ambiguo de una forma que no habías considerado. El arreglo ahora es obvio: afina las instrucciones de clasificación o añade una rama para ese caso. Lo encontraste leyendo, no reejecutando todo veinte veces con la esperanza de reproducirlo.

Un rastro por paso también localiza el problema. Sabes qué nodo falló, así que cambias ese nodo y dejas el resto en paz. El rastro convierte un vago "el agente está equivocado" en un paso específico y arreglable.

## Cómo el rastro ayuda al cumplimiento y a la confianza

Parte del trabajo tiene que ser explicable ante alguien de fuera del equipo: un cliente, un auditor, un regulador, tu propia dirección. "La IA lo decidió" no es una respuesta aceptable para ninguno de ellos.

Un rastro te permite responder como es debido. Aquí está la entrada que recibió el agente. Aquí está la regla que aplicó la rama. Aquí está la persona que aprobó antes de que se enviara nada. Eso es una explicación defendible de una decisión, y es la misma evidencia tanto si la pregunta viene de un cliente curioso como de una auditoría formal.

La confianza dentro del equipo funciona igual. La gente le da más responsabilidad a una automatización una vez que puede ver exactamente qué hizo la semana pasada. El rastro es lo que se gana esa confianza.

## Retención y revisión de ejecuciones

Un rastro que no puedes encontrar o no puedes conservar no es mucho rastro. Unas cuantas notas prácticas.

**Retención.** Conserva las ejecuciones lo suficiente para cubrir las preguntas que de verdad recibirás. Las quejas y las auditorías llegan semanas o meses después de la ejecución, así que una ventana que solo guarda los últimos días es demasiado corta. Ajusta la retención a cuánto tiempo permanece viva una decisión y a las reglas que rijan tus datos.

**Revisión.** No esperes a una queja para mirar. Revisa una muestra de ejecuciones normales con un calendario. Estás comprobando que las ramas encaminan como pretendías, que los costes se sitúan donde esperas, y que las aprobaciones ocurren donde deberían. Así es como cazas la deriva mientras es pequeña.

**Grano fino.** Mantén el registro por paso, no solo por ejecución. Un único estado final te dice que falló. Un registro por paso te dice dónde y por qué. El detalle extra es exactamente lo que necesitas el día en que algo sale mal.

## La conclusión

Un agente de IA de producción no se define por lo bien que rinde en un buen día. Se define por si puedes explicar qué hizo en uno malo. Registra las entradas, cada llamada a herramienta y su resultado, las salidas, el coste, la rama tomada, y quién aprobó. Consérvalo lo suficiente para que importe, y revísalo antes de verte obligado a ello.

Haz eso y tus agentes dejan de ser una caja negra que defiendes con una demo. Se convierten en sistemas que puedes depurar, de los que puedes responder, y en los que puedes confiar, que es la única clase que vale la pena ejecutar.
`;

export default content;
