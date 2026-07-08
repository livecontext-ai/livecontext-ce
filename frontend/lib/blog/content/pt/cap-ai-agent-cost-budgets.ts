// cap-ai-agent-cost-budgets - pt
const content = `A maioria das histórias de terror sobre custos de IA partilha uma causa raiz: um agente sem teto. Entrou em ciclo, repetiu, arrastou um contexto enorme, e ninguém descobriu até a fatura descobrir. A solução não é um modelo mais inteligente. É um orçamento rígido em cada agente, imposto antes de o gasto acontecer, não depois.

## Porque é que agentes sem limite são um risco financeiro

Um agente autónomo decide o seu próprio passo seguinte. Essa é a funcionalidade, e é também a exposição. Três modos de falha transformam uma tarefa normal numa torneira aberta.

**Ciclos.** O agente tenta algo, não funciona, tenta uma variação e continua. Sem um limite, vai queimar chamadas a perseguir um objetivo que não consegue alcançar.

**Repetições.** Uma ferramenta instável ou um limite de taxa desencadeia uma repetição. As repetições empilham-se. O que parecia uma chamada torna-se vinte, cada uma a pagar o custo total do contexto.

**Contextos longos.** Cada chamada ao modelo reenvia toda a conversa até ao momento. Uma tarefa que acumula historial paga mais em cada passo do que no anterior. A última chamada de uma execução longa pode custar muitas vezes mais do que a primeira.

Nenhum destes é raro. São o comportamento normal de um sistema a que se deu um objetivo e nenhum teto. Um orçamento transforma esse risco aberto num número conhecido e limitado.

## O que um orçamento por agente deve limitar

Um orçamento só é útil se parar o trabalho quando é atingido. Deve limitar as coisas que realmente determinam o custo e o tempo de execução:

- **Gasto total.** Um teto rígido em créditos ou tokens. Quando o agente o atinge, para. Sem excessos, sem "só mais um bocadinho."
- **Número de chamadas ao modelo.** Limita diretamente o ciclo. Um agente que não consegue fazer uma vigésima primeira chamada não consegue entrar em ciclo para sempre.
- **Chamadas a ferramentas.** Algumas ferramentas custam dinheiro ou esgotam quotas externas. Limite quantas vezes um agente pode recorrer a elas.
- **Tempo de relógio.** Um agente encravado não deve correr durante uma hora. Ponha-lhe um tempo limite.

A regra que torna um orçamento real: quando o teto é atingido, o agente para e o workflow trata disso. Não continua silenciosamente, e não falha em silêncio. Para, e a execução regista que parou por ter atingido o seu orçamento.

## Delimite as ferramentas e os dados que um agente pode tocar

O orçamento é metade da resposta. A delimitação é a outra metade, e baixa o custo antes de qualquer teto ser preciso.

Um agente que consegue ver tudo vai tentar usar tudo. Dê-lhe toda a base de dados e ele raciocina sobre toda a base de dados, e você paga os tokens. Dê-lhe apenas as ferramentas e os dados de que o passo precisa, e ele mantém-se pequeno por construção.

Para um passo de classificação, isso significa o texto da mensagem e uma ferramenta para devolver uma etiqueta. Nada mais. Para um passo de rascunho, a mensagem e a categoria. Um agente estritamente delimitado é mais barato em cada chamada porque o seu contexto é pequeno, e é mais seguro porque não pode vaguear para dados ou ações que não são o seu trabalho.

A delimitação estreita o raio de impacto. O orçamento limita o que resta. Quer ambos.

## Defina orçamentos por agente e por execução

Um único número não chega. Precisa de orçamentos a dois níveis.

**Por agente.** Cada passo recebe o seu próprio teto dimensionado à sua tarefa. Uma classificação rápida deve ter um orçamento minúsculo. Um passo de investigação que lê vários documentos recebe mais. Dimensionar cada agente ao seu trabalho real significa que um passo ganancioso não pode gastar toda a dotação.

**Por execução.** O workflow inteiro também recebe um teto. Mesmo que cada agente individual se mantenha dentro do seu próprio orçamento, uma execução que se ramifica em centenas de ramificações paralelas pode somar. Um teto ao nível da execução protege contra a soma, não apenas contra as partes.

Juntos dão-lhe um envelope previsível: um pior caso conhecido por passo e um pior caso conhecido para a execução. É isso que transforma o "custo de IA" de uma surpresa numa rubrica em torno da qual consegue planear.

## Monitorize o gasto por agente e por ferramenta

Os orçamentos travam custos descontrolados. A monitorização diz-lhe onde o custo realmente vive para que o possa afinar.

Acompanhe o gasto a um grão fino:

- **Por agente.** Que passo custa mais? Muitas vezes é um nó a fazer mais do que precisa, a carregar contexto de mais ou a usar um modelo maior do que a tarefa exige.
- **Por ferramenta.** Que chamadas a ferramentas dominam? Uma única API externa cara chamada em cada item pode tornar-se silenciosamente o grosso da conta.
- **Por execução.** Quanto custa uma execução típica, e quanto custa uma má? A diferença entre elas é onde se escondem os seus ciclos e repetições.

Com esta visão, afina deliberadamente. Corte o contexto de um passo. Baixe-o para um modelo mais barato onde a qualidade o permita. Acrescente uma salvaguarda de desduplicação para que uma ferramenta não seja chamada duas vezes para o mesmo item. Cada alteração é mensurável porque consegue ver o número mexer.

## Junte tudo

O custo de IA descontrolado é um problema de desenho, não um problema de modelação. Resolve-se estruturalmente.

Delimite cada agente às ferramentas e aos dados de que a sua tarefa precisa. Dê a cada agente um orçamento rígido que não pode exceder. Ponha um teto na execução inteira. Vigie o gasto por agente e por ferramenta, e afine onde o dinheiro realmente vai.

Faça isso e o custo deixa de ser aquilo que o impede de lançar. Passa a ser um número que define de propósito, impõe automaticamente e consegue defender linha a linha.
`;

export default content;
