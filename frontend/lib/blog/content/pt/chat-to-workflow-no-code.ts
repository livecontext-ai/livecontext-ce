// chat-to-workflow-no-code - pt
const content = `Não precisa de escrever código para construir uma automatização de IA. Precisa de dizer, em linguagem simples, o que quer que aconteça. A ferramenta transforma essa frase num workflow que consegue ver, executar e alterar.

É essa toda a promessa da automatização de IA no-code: descreva a tarefa, obtenha um sistema que funciona, mantenha o controlo sobre ele.

## Comece pelo resultado, não pelos passos

O hábito que as pessoas trazem das ferramentas de automatização mais antigas é pensar primeiro em passos. Que gatilho, que nó, que campo mapeia para qual. Aqui isso está ao contrário.

Comece pelo resultado. Diga como é o "concluído".

"Quando chega um email de suporte, ler, decidir se é um bug, uma questão de faturação ou geral, redigir uma resposta no tom certo e colocar o rascunho numa fila de revisão para um humano."

Essa única frase basta para começar. Descreveu o objetivo e a forma do trabalho. A ferramenta trata das ligações.

## Recebe um grafo, não uma caixa preta

Quando descreve a tarefa, a ferramenta constrói um grafo legível: um gatilho, alguns passos, as ramificações entre eles. Consegue olhar para ele e compreendê-lo numa só passagem. Isto importa mais do que parece.

Muitas ferramentas de IA escondem o trabalho. Escreve um pedido, algo acontece, e cruza os dedos. Quando corre mal, não tem nada para inspecionar.

Aqui vê cada nó. Vê onde o email entra, onde acontece a classificação, que ramificação segue uma questão de faturação, onde o rascunho é escrito e onde aguarda por um humano. Nada fica subentendido. Se um passo existe, está na tela.

## Refine por conversa, ou à mão

A primeira versão raramente é a final. Refinar é onde o no-code prova o seu valor.

Tem duas formas de alterar o workflow, e pode combiná-las livremente:

- **Continuar a conversar.** "Marca também como urgente tudo o que mencione um reembolso." A ferramenta acrescenta a ramificação e liga-a.
- **Editar os nós diretamente.** Abra o passo de classificação e ajuste as categorias. Abra o passo de rascunho e afine o tom. Renomeie uma ramificação. Mova um passo para antes.

Conversar é rápido para alterações estruturais. Editar diretamente é preciso para pequenos ajustes. Nenhum o impede do outro. O grafo é a fonte da verdade, e ambos os caminhos escrevem no mesmo grafo.

## Cada passo é delimitado, o que o mantém barato

Um workflow não é um grande agente a fazer tudo. É um conjunto de pequenos passos, e cada passo só vê aquilo de que precisa.

O passo de classificação vê o texto do email e devolve uma categoria. É tudo o que precisa, por isso é tudo o que recebe. O passo de rascunho vê o email e a categoria. O passo de revisão vê o rascunho.

Como cada passo recebe uma fatia estreita de contexto em vez de todo o histórico, os tokens mantêm-se pequenos e o custo mantém-se baixo. A mesma tarefa corre cerca de dez vezes mais barata do que entregar tudo a um único agente que faz tudo e torcer para que se mantenha no rumo. Não desenhou essa poupança à mão. Ela decorre de construir a tarefa como um grafo delimitado.

## Quando ainda recorre a um nó de código

O no-code cobre a maior parte do trabalho. Não tem de cobrir tudo, e fingir o contrário é onde estas ferramentas ganham má fama.

Recorra a um nó de código quando a lógica é genuinamente mecânica e exata:

- Reformatar um payload para a estrutura exata que outro passo espera.
- Um cálculo preciso, uma regra de aritmética de datas, um limiar sem ambiguidade.
- Analisar um formato que os passos incorporados não reconhecem.

Estes são os casos em que algumas linhas de código são mais claras e mais fiáveis do que um parágrafo de instruções a um modelo. O objetivo não é evitar código. O objetivo é não escrever código para as partes que uma descrição trata melhor. Use linguagem para o discernimento. Use um nó de código para a exatidão.

## Um exemplo concreto: triagem da caixa de entrada de suporte

Percorra o exemplo de suporte de ponta a ponta.

**Gatilho.** Um novo email chega à caixa de entrada de suporte.

**Classificar.** Um agente delimitado lê o email e devolve uma etiqueta: bug, faturação ou geral. Vê o email e nada mais.

**Ramificar.** O grafo divide-se em três a partir dessa etiqueta. Esta é uma ramificação real que consegue ver, não uma decisão escondida. Um bug segue por um lado, a faturação por outro, o geral por um terceiro.

**Rascunhar.** Em cada ramificação, um passo escreve uma resposta no tom adequado. A ramificação de faturação pode primeiro obter o estado da conta. A ramificação de bug pode anexar uma ligação para a página de estado.

**Revisão.** Cada rascunho chega a uma fila. Um humano lê-o, edita se necessário e aprova. Nada chega a um cliente sem essa aprovação.

**Auditoria.** Cada execução deixa um rasto: o que entrou, que etiqueta recebeu, que ramificação seguiu, o que foi rascunhado, quem aprovou.

Construiu isto descrevendo-o. Consegue lê-lo porque é um grafo. Consegue alterá-lo conversando ou editando. E quando alguém pergunta porque é que um determinado email recebeu a resposta que recebeu, consegue apontar para o caminho exato que seguiu.

É isto que a automatização de IA no-code deve significar. Não uma caixa mágica em que confia cegamente, mas um sistema que descreve por palavras e depois tem nas suas mãos.
`;

export default content;
