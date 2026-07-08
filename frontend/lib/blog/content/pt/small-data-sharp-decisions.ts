// small-data-sharp-decisions - pt
const content = `Existe um pressuposto silencioso de que melhores decisões precisam de mais dados. Muitas vezes é o contrário. Um conjunto de dados pequeno e de confiança que corresponde diretamente a uma escolha vence um gigante que enterra o sinal sob ruído.

O instinto é compreensível. Mais dados parecem mais seguros, mais rigorosos, mais defensáveis. Mas volume e verdade são coisas diferentes. Um grande conjunto de dados pode ser grande e errado ao mesmo tempo, e o seu tamanho torna o erro mais difícil de ver.

## Precisão em vez de volume

Cem linhas que compreende completamente vão superar um milhão de linhas em que confia pela metade. Com dados pequenos, consegue inspecionar cada registo, apanhar os valores atípicos a olho e saber exatamente o que um número significa antes de agir sobre ele. Essa confiança é a chave de tudo. Uma decisão que consegue defender vale mais do que uma previsão que não consegue explicar.

Precisão não é apenas exatidão. É conhecer a proveniência de cada valor, o momento em que foi captado e a razão de estar sequer no conjunto. Quando alguém pergunta "porque é que o sistema assinalou esta encomenda", os dados pequenos deixam-no responder com as linhas reais. Os big data costumam obrigá-lo a responder com um encolher de ombros e um intervalo de confiança.

Há também um argumento de velocidade. Um conjunto de dados pequeno e preciso dá uma resposta clara depressa. Um alastrado exige modelação, amostragem e ressalvas antes de dizer o que quer que seja, e nessa altura a janela de decisão pode já ter fechado. Para escolhas que faz diariamente, o conjunto de dados que responde agora vence o que responde eventualmente.

## O custo oculto de uma escala de que não precisava

Os grandes conjuntos de dados carregam impostos que você paga quer obtenha valor de volta ou não. Custam mais a armazenar, mais a mover, mais a manter frescos e muito mais a raciocinar sobre eles. Cada coluna extra é outro sítio onde um erro se pode esconder. Cada fonte extra é outro pipeline que pode partir às 3 da manhã.

O pior custo é o cognitivo. Quando o conjunto de dados ultrapassa a sua capacidade de o ter na cabeça, deixa de o questionar e começa a confiar nele cegamente. É aí que os erros silenciosos se infiltram. Um campo mal codificado, um bug de fuso horário, um cruzamento que descarta silenciosamente um terço das linhas, nada disto se anuncia. Apenas desloca os seus números, e como o conjunto de dados é grande de mais para se ver a olho, ninguém repara até que uma decisão corra mal.

A escala também convida a falsa confiança. Um gráfico construído sobre um milhão de linhas parece autoritário. As pessoas discutem-no menos. Mas um resultado de aspeto impressionante construído sobre dados que ninguém inspecionou de facto é mais perigoso do que um modesto que toda a gente compreende, precisamente porque desarma o ceticismo saudável que apanha erros.

Os dados pequenos mantêm-no honesto. Pode ainda perguntar, para qualquer saída, que linhas a produziram e porquê. Essa única capacidade, rastrear qualquer resultado até às suas entradas, vale mais do que outra ordem de grandeza de volume.

## Quando o pequeno é a escolha certa

Pequeno e preciso nem sempre é a resposta. Alguns problemas precisam genuinamente de escala: treinar um modelo geral, detetar padrões raros em milhões de eventos, prever a partir de longos historiais. Mas uma quota surpreendente de decisões operacionais do dia a dia não precisa. Recorra a dados pequenos quando:

- **A decisão é específica e recorrente**, como assinalar que encomendas rever hoje ou que faturas parecem estranhas esta semana.
- **A população é delimitada**, para que consiga cobri-la toda em vez de amostrar e torcer para que a amostra represente o todo.
- **A frescura importa mais do que o historial.** Para muitas decisões operacionais, a semana passada importa e há dez anos não. Um conjunto pequeno e atual vence um enorme e desatualizado.
- **Um humano tem de responder pelo resultado** e prestar contas por ele. Se uma pessoa tem de defender a decisão, precisa de dados que consiga realmente ler.
- **O custo de uma escolha errada é suficientemente alto** para querer inspecionar as entradas, e não confiar numa caixa preta.

Se a maioria destes descreve o seu problema, mais dados não são o upgrade. Uma versão mais limpa e mais precisa daquilo que já tem, essa sim.

## Como manter um conjunto de dados pequeno e preciso

Manter-se pequeno exige disciplina, porque os dados acumulam-se por defeito. Alguns hábitos mantêm-no enxuto:

1. **Defina primeiro a decisão, depois recolha só o que ela precisa.** Comece pela escolha que os dados orientam e trabalhe para trás. Cada campo deve ganhar o seu lugar alimentando essa escolha. Se não consegue dizer que decisão uma coluna serve, elimine-a.
2. **Defina uma janela de frescura e imponha-a.** Se a decisão só se importa com os últimos trinta dias, não carregue três anos. Deixe as linhas antigas expirar. Historial que nunca consulta é apenas risco em armazenamento.
3. **Normalize na fronteira, uma vez.** Limpe os dados onde entram para que todo o conjunto se mantenha numa forma consistente. O alastramento desarrumado é como os conjuntos de dados pequenos se tornam silenciosamente grandes.
4. **Pode num calendário, não numa crise.** Reveja as colunas e as fontes periodicamente e remova o que deixou de ser usado. Os conjuntos de dados apodrecem em direção ao inchaço a não ser que alguém os apare ativamente.
5. **Mantenha a proveniência anexada.** Guarde de onde veio cada valor e quando. Custa pouco e é o que lhe permite confiar, e defender, cada saída.

## Um exemplo concreto

Considere um responsável de operações a decidir que encomendas reter para revisão manual todas as manhãs. A jogada tentadora é puxar tudo: historial completo de encomendas, valor de vida do cliente, comportamento de navegação, tickets de suporte, uma dúzia de tabelas cruzadas. O resultado é um modelo que ninguém consegue bem explicar e uma fila de revisão que as pessoas aprendem a ignorar.

A jogada precisa é mais pequena. Pegue nas encomendas de hoje, mais três campos que realmente preveem um problema: o valor da encomenda face ao intervalo habitual do cliente, a incompatibilidade da morada de envio e se o método de pagamento é novo. Isso é um conjunto delimitado, atual e inspecionável. O responsável pode olhar para qualquer encomenda assinalada e ver com precisão porque foi assinalada. Pode defender cada retenção perante um cliente. Quando as regras precisam de afinação, pode raciocinar sobre três sinais em vez de discutir com uma caixa preta.

A mesma decisão, uma fração dos dados, e muito mais confiança no resultado.

## Afine, não acumule

O instinto de recolher mais é forte. Resista-lhe até que os dados que já tem deixem de responder à pergunta. Na maioria das vezes, a solução não é um conjunto de dados maior. É um mais limpo, cruzado com o contexto certo, a alimentar uma decisão que definiu com clareza.

Mantenha-o pequeno. Mantenha-o preciso. Deixe o workflow à sua volta tratar da repetição.
`;

export default content;
