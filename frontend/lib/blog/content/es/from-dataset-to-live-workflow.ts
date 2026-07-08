// from-dataset-to-live-workflow - es
const content = `Un conjunto de datos se vuelve útil en el momento en que algo ocurre gracias a él. Hasta entonces es un archivo. Esta es la forma que usamos para convertir una fuente de nicho estática en un flujo de trabajo que corre por sí solo y termina en una acción real.

Para mantenerlo concreto, un ejemplo recorre los cinco pasos: una lista semanal de precios de proveedores que debería disparar un flujo de revisión y alerta. Cada lunes tus proveedores envían una hoja de precios actualizada. Hoy alguien abre cada una, la revisa a ojo, y avisa al comprador si algo subió. Ese es exactamente el tipo de tarea rutinaria que debería correr sola.

## Paso 1: elige una fuente con latido

Elige datos que se actualicen con un ritmo que puedas predecir. Una exportación semanal, una página pública que se refresca cada mañana, una bandeja que recibe un informe cada lunes. El latido es lo que te permite automatizar el refresco en lugar de copiar filas a mano. Si la fuente nunca cambia, no necesitas un flujo de trabajo, necesitas una consulta. Ahórrate el esfuerzo.

Sé específico sobre el latido. No solo "semanal" sino "llega por correo cada lunes antes de las 9, un CSV por proveedor". Esa precisión decide tu disparador. Un archivo que cae en una bandeja sugiere un disparador de correo. Una página que se refresca sugiere una recuperación programada. Un sistema que puede notificar hacia fuera sugiere un webhook.

**Ejemplo trabajado.** Las listas de precios de los proveedores llegan como adjuntos de correo cada lunes por la mañana. Ese es un latido limpio y predecible. El disparador es "correo nuevo de un proveedor conocido con un adjunto de lista de precios". Nadie tiene que acordarse de iniciar nada.

## Paso 2: normaliza una vez, en el borde

Las fuentes en bruto son un desorden. Los nombres de columna varían, las fechas llegan en tres formatos, la misma entidad aparece con dos grafías, un proveedor lo llama "precio unitario" y otro lo llama "precio/ud". Haz la limpieza en un solo lugar, justo donde entran los datos, para que cada paso posterior vea una única forma consistente. Un pequeño paso de normalización al principio se paga muchas veces. Todo lo que viene después se vuelve más simple porque puede confiar en la entrada.

Decide primero la forma canónica, luego mapea cada fuente sobre ella. Para las listas de precios, la fila canónica podría ser: proveedor, sku, descripción, precio_unitario, moneda, fecha_efectiva. Sea cual sea el aspecto de la hoja de un proveedor, el paso de normalización emite esa forma y nada más. Los nodos posteriores nunca ven el desorden en bruto.

**Ejemplo trabajado.** El proveedor A envía un archivo Excel con una columna "Coste" en euros. El proveedor B envía un CSV con "Precio de lista" en dólares. El paso de normalización lee cada uno, convierte a una moneda común, analiza las fechas, y produce los mismos seis campos limpios para cada proveedor. A partir de aquí, el flujo de trabajo no sabe ni le importa de qué proveedor vino una fila.

## Paso 3: ramifica según la decisión, no según los datos

El objetivo del flujo de trabajo es una decisión. Modela esa decisión de forma explícita. Si un valor cruza un umbral, encamina por un lado. Si no, encamina por el otro. Divide una lista y maneja cada elemento en paralelo cuando los elementos son independientes. Bifurca en caminos separados cuando dos cosas deben ocurrir a la vez. Mantén la ramificación legible. Un grafo que todo tu equipo puede seguir de un vistazo vale más que uno ingenioso que solo entiende su autor.

La trampa aquí es ramificar según los datos en bruto en lugar de según la decisión. No te importa que el precio sea 12,40. Te importa si subió más de tu tolerancia desde la semana pasada. Así que calcula la decisión, luego ramifica según ella.

**Ejemplo trabajado.** Para cada fila normalizada, el flujo de trabajo busca el precio de la semana pasada del mismo sku, calcula el cambio porcentual, y ramifica: si el aumento supera el cinco por ciento, encamina al camino de "marcarlo"; si no, márcalo revisado y sigue. Como cada sku es independiente, la lista se divide y las filas se revisan en paralelo, de modo que una hoja de mil líneas se despacha igual en una sola pasada.

## Paso 4: termina en una acción, con una persona donde importa

El último nodo debería hacer algo: enviar la notificación, actualizar la fila, abrir el ticket, preparar la orden de compra. Cuando la acción es arriesgada o irreversible, haz una pausa para aprobación primero. La ejecución espera a que una persona dé el visto bueno, y luego retoma exactamente donde se detuvo. Las acciones baratas y reversibles pueden correr sin supervisión. Las acciones caras o de un solo sentido obtienen una compuerta humana.

**Ejemplo trabajado.** Los saltos de precio marcados se recopilan en un solo resumen y se envían al comprador: proveedor, sku, precio anterior, precio nuevo, cambio porcentual. Si un salto es lo bastante grande como para pausar automáticamente una orden ya en marcha, el flujo de trabajo se detiene en un paso de aprobación y espera a que el comprador confirme antes de tocar nada. Los rutinarios simplemente envían la alerta.

## Paso 5: registra el resultado para que la siguiente ejecución sea más inteligente

Escribe el resultado de vuelta. Una tabla que el flujo de trabajo lee y actualiza se convierte en una memoria compartida: recuerda lo que ya procesó, así que la siguiente ejecución salta los duplicados y solo toca lo que es nuevo. También es la fuente para la comparación de la semana siguiente.

**Ejemplo trabajado.** Cada fila procesada se escribe en una tabla de precios indexada por proveedor y sku, con la fecha efectiva. Esa tabla es exactamente lo que el Paso 3 lee para calcular el "cambio desde la semana pasada". El flujo de trabajo se alimenta a sí mismo. También te da un registro de auditoría limpio: qué precios cambiaron y cuándo, y quién aprobó la respuesta.

## Escollos comunes

- **Sin latido real.** Automatizar una fuente que rara vez cambia añade piezas móviles sin recompensa. Confirma la cadencia antes de construir.
- **Normalizar en tres lugares.** Si dos nodos limpian los datos cada uno a su manera, se desviarán y discreparán. Normaliza una vez, en el borde.
- **Ramificar según valores en bruto.** Calcula la decisión, luego ramifica según la decisión. Los umbrales enterrados dentro de cinco nodos distintos son imposibles de cambiar después.
- **Sin compuerta humana en acciones irreversibles.** Enviar automáticamente una orden de compra por un análisis erróneo es como la automatización se gana mala fama. Pon una compuerta en los pasos de un solo sentido.
- **Olvidar escribir de vuelta.** Sin una memoria, cada ejecución reprocesa todo y no puede detectar el cambio. El registro no es opcional, es lo que hace que el ciclo funcione.
- **Comparar texto como si fueran números.** Almacena los precios en una forma numérica consistente y compáralos como números, para que un salto de 9 a 100 se lea como una subida, no como una bajada.

Ese es todo el patrón. Una fuente con latido, un borde limpio, una decisión explícita, una acción real, y una memoria. Conecta esos cinco entre sí y el conjunto de datos deja de ser un archivo que revisas. Se convierte en un sistema que trabaja para ti.
`;

export default content;
