// ai-agent-audit-trail - pt
const content = `Um agente de IA que funciona na demonstração provou exatamente uma coisa: consegue funcionar uma vez. A produção faz uma pergunta mais difícil. Quando faz algo errado, e vai fazer, consegue descobrir o que aconteceu e porquê? Se a resposta é não, não tem um sistema que possa executar. Tem um sistema sobre o qual está a torcer.

O que transforma a esperança em operação é um registo de auditoria. Um registo completo do que o agente fez, em cada execução, que pode ler depois do facto.

## Porque é que "funcionou na demonstração" não chega

Uma demonstração é um único caminho feliz sob uma entrada amigável. A produção são milhares de execuções sob entradas que nunca antecipou. Uma fração corre mal: uma classificação errada, uma ferramenta que devolveu lixo, uma ação tomada sobre o registo errado.

Quando uma dessas aparece, normalmente sob a forma de uma reclamação, precisa de responder depressa a três perguntas. O que viu o agente? O que fez? Porque escolheu isso? Sem um rasto, está a reconstruir uma decisão de um sistema probabilístico depois do facto, ou seja, está a adivinhar.

Um rasto substitui a adivinha por um registo. É essa toda a diferença entre um agente que opera e um que apenas implanta.

## O que registar

Um registo de auditoria só é tão bom quanto aquilo que captura. Registe o suficiente para que uma execução possa ser totalmente reproduzida no papel, sem a voltar a correr.

- **Entradas.** O que realmente entrou no agente ou no passo. Não um resumo, a entrada real. A maioria dos relatos de "a IA está avariada" acaba por ser entrada má ou surpreendente, e não a consegue ver a não ser que a tenha registado.
- **Cada chamada a ferramenta e o seu resultado.** Cada ferramenta que o agente invocou, com o que passou e o que voltou. Os resultados das ferramentas são onde a realidade entra na execução, e onde muitas falhas começam.
- **Saídas.** O que o agente produziu em cada passo e no fim. A resposta final, e as intermédias que a ela conduziram.
- **Custo.** Tokens e gasto por passo. É a sua fatura e o seu aviso precoce de um passo a fazer mais do que deveria.
- **A ramificação ou decisão tomada.** Que caminho a execução seguiu. Um item de faturação desceu pela ramificação de faturação: registe que o fez, para que possa confirmar que o encaminhamento estava certo.
- **Quem aprovou.** Para qualquer passo com portão humano, registe quem aprovou, quando e o que viu quando o fez. As aprovações são a espinha dorsal da responsabilização.

Capture esses e qualquer execução torna-se uma história que pode ler do início ao fim.

## Como o rasto ajuda na depuração

Depurar sem um rasto é encarar uma saída má e teorizar. Depurar com um é seguir um caminho.

Abre a execução falhada. Lê a entrada e parece normal. Passa ao passo de classificação e vê que devolveu a etiqueta errada. Verifica o que recebeu, e a mensagem era ambígua de uma forma que não tinha considerado. A solução é agora óbvia: afinar as instruções de classificação ou acrescentar uma ramificação para esse caso. Descobriu-o lendo, não voltando a correr a coisa toda vinte vezes na esperança de a reproduzir.

Um rasto por passo também localiza o problema. Sabe que nó falhou, por isso altera esse nó e deixa o resto em paz. O rasto transforma um vago "o agente está errado" num passo específico e corrigível.

## Como o rasto ajuda a conformidade e a confiança

Algum trabalho tem de ser explicável a alguém de fora da equipa: um cliente, um auditor, um regulador, a sua própria liderança. "A IA decidiu" não é uma resposta aceitável para nenhum deles.

Um rasto deixa-o responder como deve ser. Aqui está a entrada que o agente recebeu. Aqui está a regra que a ramificação aplicou. Aqui está o humano que aprovou antes de algo ser enviado. Isso é um relato defensável de uma decisão, e é a mesma prova quer a pergunta venha de um cliente curioso ou de uma auditoria formal.

A confiança dentro da equipa funciona da mesma forma. As pessoas dão mais responsabilidade a uma automatização assim que conseguem ver exatamente o que ela fez na semana passada. O rasto é o que conquista isso.

## Retenção e revisão de execuções

Um rasto que não consegue encontrar ou não consegue guardar não é grande rasto. Algumas notas práticas.

**Retenção.** Guarde as execuções o suficiente para cobrir as perguntas que realmente vai receber. Reclamações e auditorias chegam semanas ou meses depois da execução, por isso uma janela que só guarda os últimos dias é curta de mais. Ajuste a retenção a quanto tempo uma decisão fica ativa e a quaisquer regras que governem os seus dados.

**Revisão.** Não espere por uma reclamação para olhar. Reveja uma amostra de execuções normais num calendário. Está a verificar que as ramificações encaminham como pretendido, que os custos estão onde espera e que as aprovações estão a acontecer onde deviam. É assim que apanha desvios enquanto são pequenos.

**Grão fino.** Guarde o registo por passo, não apenas por execução. Um único estado final diz-lhe que falhou. Um registo por passo diz-lhe onde e porquê. O detalhe extra é exatamente aquilo de que precisa no dia em que algo corre mal.

## Em resumo

Um agente de IA de produção não se define por quão bem se sai num bom dia. Define-se por se consegue explicar o que fez num mau. Registe as entradas, cada chamada a ferramenta e o seu resultado, as saídas, o custo, a ramificação tomada e quem aprovou. Guarde esses tempo suficiente para importarem, e reveja-os antes de ser forçado a isso.

Faça isso e os seus agentes deixam de ser uma caixa preta que defende com uma demonstração. Passam a ser sistemas que consegue depurar, dos quais consegue prestar contas e em que consegue confiar, que é o único tipo que vale a pena executar.
`;

export default content;
