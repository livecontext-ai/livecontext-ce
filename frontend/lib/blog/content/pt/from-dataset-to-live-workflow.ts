// from-dataset-to-live-workflow - pt
const content = `Um conjunto de dados torna-se útil no momento em que algo acontece por causa dele. Até lá, é um ficheiro. Esta é a forma que usamos para transformar uma fonte de nicho estática num workflow que corre sozinho e termina numa ação real.

Para o manter concreto, um exemplo percorre os cinco passos: uma lista de preços semanal de fornecedores que deve desencadear um workflow de revisão e alerta. Todas as segundas-feiras os seus fornecedores enviam uma folha de preços atualizada. Hoje alguém abre cada uma, examina-a à vista e avisa o comprador se algo disparou. É exatamente o tipo de tarefa rotineira que se devia executar a si própria.

## Passo 1: escolha uma fonte com batimento

Escolha dados que se atualizem num calendário que consiga prever. Uma exportação semanal, uma página pública que se atualiza todas as manhãs, uma caixa de entrada que recebe um relatório todas as segundas-feiras. O batimento é o que lhe permite automatizar a atualização em vez de copiar linhas à mão. Se a fonte nunca muda, não precisa de um workflow, precisa de uma consulta. Poupe-se ao esforço.

Seja específico quanto ao batimento. Não apenas "semanal", mas "chega por email todas as segundas antes das 9h, um CSV por fornecedor." Essa precisão decide o seu gatilho. Um ficheiro que aterra numa caixa de entrada sugere um gatilho de email. Uma página que se atualiza sugere uma recolha agendada. Um sistema que consegue notificar sugere um webhook.

**Exemplo trabalhado.** As listas de preços dos fornecedores chegam como anexos de email todas as segundas de manhã. Isso é um batimento limpo e previsível. O gatilho é "novo email de um fornecedor conhecido com um anexo de lista de preços." Ninguém tem de se lembrar de iniciar seja o que for.

## Passo 2: normalize uma vez, na fronteira

As fontes em bruto são desarrumadas. Os nomes das colunas variam, as datas vêm em três formatos, a mesma entidade aparece com duas grafias, um fornecedor chama-lhe "preço unitário" e outro chama-lhe "preço/un." Faça a limpeza num único sítio, mesmo onde os dados entram, para que cada passo seguinte veja uma única forma consistente. Um pequeno passo de normalização à entrada compensa muitas vezes o seu custo. Tudo o que vem depois fica mais simples porque pode confiar na entrada.

Decida primeiro a forma canónica, depois mapeie cada fonte sobre ela. Para as listas de preços, a linha canónica pode ser: fornecedor, referencia, descricao, preco_unitario, moeda, data_efetiva. Seja qual for o aspeto da folha de um fornecedor, o passo de normalização emite essa forma e nada mais. Os nós a jusante nunca veem a confusão em bruto.

**Exemplo trabalhado.** O fornecedor A envia um ficheiro Excel com uma coluna "Custo" em euros. O fornecedor B envia um CSV com "Preço de Tabela" em dólares. O passo de normalização lê cada um, converte para uma moeda comum, analisa as datas e produz os mesmos seis campos limpos para cada fornecedor. Daqui para a frente, o workflow não sabe nem se importa de que fornecedor veio uma linha.

## Passo 3: ramifique na decisão, não nos dados

O objetivo do workflow é uma decisão. Modele essa decisão explicitamente. Se um valor ultrapassa um limiar, encaminhe por um lado. Caso contrário, encaminhe pelo outro. Divida uma lista e trate cada item em paralelo quando os itens são independentes. Bifurque em caminhos separados quando duas coisas devem acontecer ao mesmo tempo. Mantenha a ramificação legível. Um grafo que toda a sua equipa consegue seguir num relance vale mais do que um engenhoso que só o seu autor compreende.

A armadilha aqui é ramificar sobre dados em bruto em vez de sobre a decisão. Não lhe interessa que o preço seja 12,40. Interessa-lhe se subiu mais do que a sua tolerância desde a semana passada. Por isso, calcule a decisão e depois ramifique sobre ela.

**Exemplo trabalhado.** Para cada linha normalizada, o workflow procura o preço da semana passada para a mesma referência, calcula a variação percentual e ramifica: se o aumento for superior a cinco por cento, encaminha para o caminho de "assinalar"; caso contrário marca como revisto e segue em frente. Como cada referência é independente, a lista é dividida e as linhas são verificadas em paralelo, de modo que uma folha de mil linhas ainda resolve numa só passagem.

## Passo 4: termine numa ação, com um humano onde importa

O último nó deve fazer algo: enviar a notificação, atualizar a linha, abrir o pedido, preparar a ordem de compra. Quando a ação é arriscada ou irreversível, pare primeiro para aprovação. A execução aguarda que uma pessoa dê o aval e depois retoma exatamente onde parou. Ações baratas e reversíveis podem correr sem supervisão. Ações caras ou sem retorno recebem um portão humano.

**Exemplo trabalhado.** Os saltos de preço assinalados são reunidos num único resumo e enviados ao comprador: fornecedor, referência, preço antigo, preço novo, variação percentual. Se um salto for grande o suficiente para pausar automaticamente uma encomenda já em curso, o workflow para num passo de aprovação e aguarda que o comprador confirme antes de tocar em seja o que for. Os rotineiros apenas enviam o alerta.

## Passo 5: registe o resultado para que a execução seguinte seja mais inteligente

Escreva o resultado de volta. Uma tabela que o workflow lê e atualiza torna-se uma memória partilhada: lembra-se do que já processou, por isso a execução seguinte salta duplicados e só toca no que é novo. É também a fonte para a comparação da semana seguinte.

**Exemplo trabalhado.** Cada linha processada é escrita numa tabela de preços indexada por fornecedor e referência, com a data efetiva. Essa tabela é exatamente o que o Passo 3 lê para calcular a "variação desde a semana passada." O workflow alimenta-se a si próprio. Dá-lhe também um registo de auditoria limpo: que preços mudaram e quando, e quem aprovou a resposta.

## Armadilhas comuns

- **Nenhum batimento real.** Automatizar uma fonte que raramente muda acrescenta peças móveis sem qualquer retorno. Confirme a cadência antes de construir.
- **Normalizar em três sítios.** Se dois nós limpam os dados cada um à sua maneira, vão divergir e discordar. Normalize uma vez, na fronteira.
- **Ramificar sobre valores em bruto.** Calcule a decisão e depois ramifique sobre a decisão. Limiares enterrados dentro de cinco nós diferentes são impossíveis de mudar mais tarde.
- **Sem portão humano em ações irreversíveis.** Enviar automaticamente uma ordem de compra a partir de uma análise errada é como a automatização ganha má fama. Coloque um portão nos passos sem retorno.
- **Esquecer-se de escrever de volta.** Sem memória, cada execução reprocessa tudo e não consegue detetar mudança. O registo não é opcional, é o que faz o ciclo funcionar.
- **Comparar texto como se fossem números.** Guarde os preços numa forma numérica consistente e compare-os como números, para que um salto de 9 para 100 leia como uma subida, e não como uma descida.

É este todo o padrão. Uma fonte com batimento, uma fronteira limpa, uma decisão explícita, uma ação real e uma memória. Ligue esses cinco e o conjunto de dados deixa de ser um ficheiro que verifica. Passa a ser um sistema que trabalha para si.
`;

export default content;
