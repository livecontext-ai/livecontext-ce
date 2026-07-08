// workflow-beats-do-everything-agent - pt
const content = `A demonstração de um único agente autónomo é sempre impressionante. Dá-lhe um objetivo, ele pensa, chama ferramentas, volta com uma resposta. Depois coloca-o em produção e chega a fatura, os resultados oscilam e ninguém lhe sabe dizer porque é que fez o que fez.

O problema não é o modelo. O problema é a forma. Um agente a fazer tudo é a forma errada para a maior parte do trabalho real.

## Custo: o contexto é o contador

Sempre que um agente chama o modelo, reenvia o seu contexto. As instruções, o histórico, todos os resultados de ferramentas até ao momento. Um agente que faz tudo acumula tudo isso numa longa conversa, e o contexto cresce a cada passo.

Você paga esse contexto em cada chamada. Uma tarefa de dez passos não custa dez chamadas pequenas. Custa dez chamadas que carregam, cada uma, uma pilha crescente de tudo o que veio antes.

Um workflow parte a tarefa em passos delimitados e alimenta cada um apenas com aquilo de que precisa. O passo de classificação vê a mensagem. O passo de rascunho vê a mensagem e a categoria. O passo de envio vê o rascunho aprovado. Nenhum passo arrasta consigo todo o histórico.

Alimente cada agente com uma fatia estreita em vez de toda a transcrição e a contagem de tokens cai a pique. Na prática, a mesma tarefa corre cerca de dez vezes mais barata. Não é um truque. É o resultado direto de não pagar para reenviar contexto que um dado passo nunca usa.

## Controlo: ramificação determinística versus improviso

Um agente que faz tudo decide o seu próprio caminho em tempo de execução. Às vezes escolhe o certo. Às vezes inventa um novo. Está a confiar num sistema probabilístico para tomar a mesma decisão de encaminhamento sempre, e ele não o fará.

Um workflow torna o encaminhamento explícito. Uma questão de faturação desce pela ramificação de faturação porque o grafo o diz, não porque o modelo teve vontade nesta execução. O juízo difuso (isto é faturação ou um bug?) continua a acontecer dentro de um passo. A decisão estrutural (o que acontece a um item de faturação) é fixa.

Essa separação é a chave de tudo. Deixe o modelo fazer aquilo que só um modelo consegue fazer, que é ler e julgar. Não o deixe improvisar as partes que precisa que sejam fiáveis.

## Auditabilidade: um caminho para o qual pode apontar

Quando um agente faz tudo num único ciclo, o registo é uma parede de raciocínio e chamadas de ferramentas. Reconstruir o que realmente aconteceu é arqueologia.

Um workflow dá-lhe uma execução que consegue ler. Aqui está a entrada. Aqui está a ramificação que seguiu. Aqui está o que cada passo recebeu e devolveu. Aqui está o custo de cada passo. Aqui está quem aprovou antes de enviar. Quando alguém pergunta porque é que um cliente recebeu uma determinada resposta, você responde a partir do rasto em vez de adivinhar.

## Depuração: uma superfície delimitada

Um grande agente que falha dá-lhe uma única falha gigante para encarar. Foi o plano, um mau resultado de ferramenta, uma instrução perdida vinte turnos atrás? Não consegue isolá-la, porque tudo partilha um só contexto.

Um workflow falha num nó. O passo de rascunho produziu o tom errado, por isso abre o passo de rascunho. As suas entradas estão ali mesmo. Altera esse passo, executa de novo e deixa o resto intocado. Pequeno, delimitado e repetível, tal como funciona a depuração de software normal.

## Sejamos justos: quando um único agente é a escolha certa

Os workflows delimitados nem sempre são a resposta, e fingir o contrário é o seu próprio tipo de exagero.

Recorra a um único agente autónomo quando:

- **A tarefa é genuinamente aberta.** Investigação exploratória, ou depuração em que o próximo movimento depende inteiramente do último resultado. Não consegue desenhar as ramificações à partida porque ainda não existem.
- **O caminho é curto e barato.** Uma consulta única ou um rascunho rápido não precisa de um grafo. Um grafo seria sobrecarga.
- **Ainda está a descobrir a forma.** No início, deixe um agente vaguear e observe o que ele realmente faz. As partes estáveis desse comportamento são exatamente o que mais tarde eleva a um workflow.

A regra honesta: se consegue desenhar os passos, construa um workflow. Se genuinamente ainda não os consegue desenhar, um agente é a ferramenta certa, por agora.

## O híbrido: o workflow orquestra, os agentes fazem as partes difusas

Os melhores sistemas de produção não são um ou outro. São um workflow com agentes lá dentro.

O workflow é dono da estrutura: os gatilhos, as ramificações, as junções, as aprovações, as repetições, o orçamento de cada passo. É determinístico onde o determinismo importa.

Dentro de nós individuais, os agentes fazem o trabalho que precisa de discernimento: classificar esta mensagem, redigir esta resposta, extrair estes campos, resumir este documento. Cada um desses agentes é delimitado. Recebe uma entrada clara, um pequeno conjunto de ferramentas, um orçamento que não pode exceder, e devolve uma saída clara ao passo que se segue.

Você obtém o perfil de custo dos passos delimitados, a fiabilidade da ramificação explícita e o raciocínio de um modelo exatamente onde o raciocínio ajuda. O agente trata da subtarefa difusa. O workflow trata de tudo o resto, e é entregue como uma aplicação que pode executar, monitorizar e passar a outra pessoa.

Comece por perguntar que partes da sua tarefa precisam realmente de discernimento. Envolva essas em agentes delimitados. Ligue o resto como um grafo. É essa a forma que sobrevive ao contacto com a produção.
`;

export default content;
