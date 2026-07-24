// from-dataset-to-live-workflow - zh
// Translated from the English body; structure identical. Everything inside a
// fenced code block, every {{...}} template, node name, prefix, output field,
// enum value and file:line citation is byte-identical to English; only prose is
// translated. A wrong template string is the one error a reader copies.
const content = `数据已经选好了。现在它必须自己跑起来。

一个合格的细分领域数据集在有东西按计划读取它、据此做出判断、并以一个人类会信任的动作收尾之前,都是惰性的。本文正是从这里开始:数据集已经选定。如何挑选一个细分数据集,以及运行它需要多少上下文和预算,配套文章已有覆盖(只链接一次,不在此重复论证)。本文从数据选定之后开始,到一个运行中的工作流为止。

下面每一个节点机制都引用了某个生产级工作流引擎的代码和文档,并给出确切字符串。一行话概括这次实战搭建:一个 schedule 触发器每小时刷新,一个 HTTP 请求重新拉取被追踪的商品,一个 code 节点归一化原始响应,一次表查找加一个决策把从未见过的 SKU 与已知的 SKU 分开,第二个决策标记出重大的价格变动,一个 user-approval 门控守护写入,只有到那时告警才会触发。一次幂等的基线写入意味着重跑永远不会重复插入同一行。每个节点的套路都一样:先讲可迁移的坑,再给这个引擎的确切字符串。

## 图:八个节点,七个前缀

在文字走完整个搭建之前,先一眼看全貌。引擎用一个类别前缀为每个节点命名。共有七个:\`trigger:\`、\`mcp:\`、\`table:\`、\`agent:\`、\`core:\`、\`note:\` 和 \`interface:\`(\`LabelNormalizer.java:14-24\`、\`:262-265\`)。\`core:\` 家族最大,涵盖 Loop、Split、Decision、Switch、Merge、Transform、Wait、Fork、Download File、HTTP Request、Data Input 和 User Approval(\`LabelNormalizer.java:182\`)。注意 HTTP Request 是一个 \`core:\` 节点,而不是 \`mcp:\` 节点(\`WORKFLOW_NODE_TYPES.md:1559-1594\`)。

| # | 节点(角色) | 在搭建中做什么 | 关键输出字段 | 引用出处 |
|---|---|---|---|---|
| 1 | Schedule 触发器 | 每小时触发,是心跳 | \`triggered_at\`、\`execution_count\` | \`triggers.md:23-27\` |
| 2 | \`core:fetch_listings\`(HTTP) | 对实时源的新鲜读取 | \`data.organic_results[]\` | \`AGENTS.md:371\`; \`nodes.md:66\` |
| 3 | \`core:normalize\`(code) | 把原始 JSON 重塑为 \`{sku, price, currency, seen_at}\` | \`result\`(被包裹) | \`CodeNode.java:130-137\` |
| 4 | \`find_rows\`(基线查找) | 按 \`sku\` 做幂等探测 | \`items\`、\`item_count\` | \`ConceptsHelpProvider.java:281\` |
| 5 | \`core:decision\`(新 vs 已知) | 按 \`item_count == 0\` 分流 | \`selected_branch\` | \`nodes.md:29\` |
| 6a | \`insert_row\`(新分支) | 写入基线 | 插入的行 | \`tables.md:52\` |
| 6b | \`core:decision\`(重大变动) | 标记超过 5% 的变动 | \`selected_branch\` | \`expressions.md:96\` |
| 7 | \`core:user_approval\` | 写入前的人工门控 | \`approved\`/\`rejected\`/\`timeout\` | \`nodes.md:39\` |
| 8 | \`mcp:send_alert\` + \`update_row\` | 真正的动作,然后是受守护的写入 | 已发送、已合并的行 | \`nodes.md:62\`; \`tables.md:49\` |

这三个表操作映射到构建器面板上的 Create Row / Find Rows / Update Row 三块瓷砖(kind 为 \`create-row\` / \`find\` / \`update-row\`);文中提到的 \`insert_row\` / \`find_rows\` / \`update_row\` 是这些瓷砖的 agent-tool 别名。

每个节点输出都用一种统一的形状来引用,与节点类型无关:

\`\`\`
{{type:label.output.field}}
\`\`\`

\`.output.\` 这一段是强制的(\`WORKFLOW_NODE_TYPES.md:1650-1660\`;\`expressions.md:9\`)。嵌套字段和数组索引都能用(\`expressions.md:28-32\`):

\`\`\`
{{mcp:api_call.output.data.users[0].email}}
\`\`\`

标签会经过一条固定的五步流水线归一化:音译重音符号、转小写、把每个非字母数字字符替换为下划线、折叠重复、修剪两端(\`LabelNormalizer.java:55-82\`)。所以你标记为 \`Baseline Lookup\` 的节点,引用时写成:

\`\`\`
{{table:baseline_lookup.output.item_count}}
\`\`\`

如果一个 LLM 在模板里写了带空格的原始标签,引擎会在求值前自动归一化它(\`LabelNormalizer.java:496-537\`),这就是为什么空格不会破坏解析。有一条硬约束支配着任何节点能读取什么:它只能引用它的祖先,即已经执行过的节点。同级节点和并行分支彼此看不见,也不存在前向引用。引擎只从 \`context.stepOutputs\` 解析(\`WORKFLOW_NODE_TYPES.md:1617-1644\`)。

schedule 触发器只接受标准的五字段 cron。构建器默认的 \`0 * * * *\` 是每小时,而像 \`5m\` 或 \`1h\` 这样的间隔简写会被直接拒绝(\`triggers.md:23-27\`)。它发出 \`triggered_at\` 和一个从一开始计数的 \`execution_count\`,并且每次触发都会开启一个新的 epoch(\`EXECUTION_ENGINE.md:15\`)。

## 刷新与读取:心跳与真实的响应形状

节点 1 是心跳。节点 2 是一个 HTTP Request 节点,拉取这个工作流追踪的那一个 SKU 的当前商品信息。就是在这里,"自我刷新"不再是口号,而开始依赖一个真实的载荷。

可迁移的教训:绑定到实际的响应,而不是声明的 schema。声明的 schema 是一个承诺。线上传回的才是真相,而两者不一致的频率超过任何人愿意承认的程度。

一个经生产验证的例子让它更具体。SerpAPI 的 \`amazon_search\` 在 \`organic_results[]\` 下返回条目,每一条携带 \`title\`、\`thumbnail\`、\`price\`、\`extracted_price\`、\`rating\`、\`reviews\`、\`badges\`、\`sponsored\` 和 \`delivery[]\`。它不携带的是一个 \`prime\` 布尔值或一个 \`brand\` 字段。要知道某条商品是否 Prime 发货,你要用 \`/prime/i\` 去匹配 \`delivery[]\` 数组,而不是一个并不存在的 \`prime\` 字段(\`AGENTS.md:371\`)。与此同时,catalog 声明的 \`outputSchema\` 乐观地列出了一个布尔 \`prime\`(\`serpapi.json:8879\`)、一个 \`brand\`(\`serpapi.json:8849\`),以及把 \`delivery\` 当作对象(\`serpapi.json:8889\`)。实时载荷与这三者全都矛盾。读取到达的东西。

读取节点不能被盲目信任还有第二个原因。一个 HTTP Request 节点把 404 或 500 当作节点级别的成功。只有传输错误才会让节点失败(\`nodes.md:66\`)。所以随后的归一化步骤必须防御一个躯壳为 200 却带着错误的响应体。不要假设节点失败会捕获它,因为它不会。

## 重塑:code 节点,以及让它看起来为空的两个坑

节点 3 是一个 \`core:code\` 节点,把原始响应压平成下游一切所需的形状:\`{sku, price, currency, seen_at}\`。它恰好接受三个参数:\`code\`、\`language\` 和 \`timeoutSeconds\`。没有 \`input_mapping\`。语言有 \`javascript\`、\`python\`、\`typescript\` 和 \`bash\`,\`timeoutSeconds\` 被钳制在 1 到 120 之间,默认为 10(\`CodeNode.java:67-70\`、\`:170-177\`)。

因为 \`amazon_search\` 条目不带 \`sku\` 也不带 \`currency\` 字段,归一化要推导它们:\`sku\` 来自产品标识符(\`asin\`,或从产品链接中解析出来),\`currency\` 对于单一市场的监视用一个常量,因为两者在响应中都不是一等字段。这里也是躯壳错误守卫所在之处:检查 200 响应体是否有 error 键,并在映射之前确认数组存在。WRONG 版本直接读取 \`organic_results\` 并让错误响应体流向下游;CORRECT 版本先大声失败:

\`\`\`
const res = $input.fetch_listings && $input.fetch_listings.data;
if (!res || res.error || !Array.isArray(res.organic_results)) {
  throw new Error("bad body: " + JSON.stringify($input).slice(0, 300));
}
const top = res.organic_results[0];
$output = {
  sku: top.asin,
  price: top.extracted_price,
  currency: "USD",
  seen_at: new Date().toISOString()
};
\`\`\`

因为归一化取的是 \`organic_results[0]\`,\`$output\` 是单个对象,不是数组。这一点很重要:一个数组形状的归一化输出会让单值模板 \`{{core:normalize.output.result.sku}}\` 解析为空,守卫的 \`find_rows\` 值会为空,\`item_count\` 每次运行都读到 0,并且一个空 SKU 的行会被每小时插入一次,幂等守卫被悄悄击败。让归一化保持只发出一个对象;如果你确实需要在许多商品上扇出,那是一个 \`core:split\` 节点的活,而不是裸返回一个数组。

有两个坑会让一个 code 节点看起来为空却完全没有报错,这是最糟糕的一种失败,因为日志里没有任何东西可追。

可迁移的坑之一是输入形状。上游数据不会到达你输入对象的根部。它以前驱节点的标签为键到达。在这个引擎上,JavaScript 包装器注入 \`const $input = JSON.parse(...)\` 和 \`let $output = undefined\`(\`CodeNode.java:180-190\`),每个上游步骤的输出都放在它自己的标签键下并剥掉信封(\`CodeNode.java:300-319\`;\`OutputUnwrapper.java:178-186\`)。所以你把 fetch 的输出读作 \`$input.fetch_listings.data.organic_results\`,或者如果你喜欢方括号访问就写 \`$input['core:fetch_listings']\`。你永远不要读 \`$input.organic_results\`,那是 undefined。你把结果赋给 \`$output\`,它通过一个 \`__RESULT__\` stdout 前缀被捕获并被 JSON 解析回来(\`CodeNode.java:180-190\`)。Python 用 \`_input\` 和 \`_output\`,bash 用 \`INPUT\` 和 \`OUTPUT\`。

可迁移的坑之二是输出嵌套。许多引擎会把你返回的东西包进它们自己的信封。这里,引擎把你的 \`$output\` 对象包在一个额外的 \`result\` 键下(\`CodeNode.java:130-137\`,\`result.put("result", parsedResult)\`;\`CodeNodeSpec.java:22-26\`)。下游你必须钻穿它:

\`\`\`
{{core:normalize.output.result.sku}}
\`\`\`

而要把整个归一化对象映射到一个下游参数,你要指向 \`.result\`:

\`\`\`
{"result":"{{core:normalize.output.result}}"}
\`\`\`

嵌套弄错了,你会得到一个悄无声息的双重 \`result.result\` 和一个空读取,而永远不是报错(\`AGENTS.md\` Interface System 注)。

有一个配套机制解释了为什么上面这个整对象映射必须是一个孤立的模板。一个纯粹的单个 \`{{...}}\` 返回带类型的值,一个 Number、一个 Map 或一个 List。同一个表达式嵌入在周围的文字中会被强制转成 String,其中 Map 被自动编码为 JSON(\`expressions.md:72-74\`)。因此对象类型的参数必须是单个模板,永远不要缝进文字里。

## 别处没有的"正确对错误"表

每一行用平实的话陈述一般性的坑;这个引擎的确切错误与正确字符串就在紧接着的代码块里,这样一个 token 的差异清晰可读,不必把一个长模板硬塞进表格单元格。

| 节点 / 操作 | 一般性的坑(可迁移) | 引用出处 |
|---|---|---|
| Code 节点字段读取 | 返回的对象位于一个信封之下 | \`CodeNode.java:130-137\` |
| Code 节点整对象映射 | 映射整个对象时信封必须包含在内 | \`AGENTS.md\` GOTCHA |
| Code 节点输入读取 | 输入以前驱标签为键,而非根部 | \`CodeNode.java:300-319\` |
| 表 where 列 | 列是裸的存储名 | \`CrudRepository.java:369-372\` |
| 数值阈值 | 一个看似数值的过滤器可能按文本比较 | \`CrudRepository.java:378-416\` |
| 构建一个对象参数 | 某些 transform 会把对象字符串化 | \`AGENTS.md\` finding #2 |

Code 节点字段读取:

\`\`\`
WRONG:   {{core:normalize.output.sku}}
CORRECT: {{core:normalize.output.result.sku}}
\`\`\`

Code 节点整对象映射:

\`\`\`
WRONG:   {"result":"{{core:normalize.output}}"}
CORRECT: {"result":"{{core:normalize.output.result}}"}
\`\`\`

Code 节点输入读取:

\`\`\`
WRONG:   $input.organic_results
CORRECT: $input.fetch_listings.data.organic_results
\`\`\`

表 where 列:

\`\`\`
WRONG:   {column:'data.sku', operator:'=', value:'ABC-123'}
CORRECT: {column:'sku', operator:'=', value:'ABC-123'}
\`\`\`

数值阈值(在 \`core:decision\` 里做数学,而不是在查询里):

\`\`\`
WRONG:   {column:'price', operator:'>', value:9}
CORRECT: compare in core:decision (SpEL, numeric)
\`\`\`

构建一个对象参数:

\`\`\`
WRONG:   assemble the object in a core:transform mapping
CORRECT: assemble it in a core:code node ($output keeps JSON types)
\`\`\`

transform 那一行会烧到那些从不怀疑它的人。一个 \`core:transform\` 节点会把对象值字符串化。你在 transform 表达式里组装的一个对象,到达下游对象类型的工具参数时会变成一个 String,产生像 \`expected map, actual string\` 这样的 provider 错误(\`AGENTS.md\` workflow-builder finding #2)。对象类型的值必须改在一个 \`core:code\` 节点里构建,在那里 \`$output\` 字段通过整值模板保持它们真实的 JSON 类型。

表 where 列那一行也值得内化。用户数据存活在单个 JSONB \`data\` 列中,而 where 列是裸名。前导的 \`data.\` 前缀在构建时和运行时都会被自动剥掉,而带点的列否则会被清理器拒绝,所以这个剥除是强制的而非装饰性的(\`CrudRepository.java:369-372\`;\`SqlSanitizer.java:46\`)。保留名 \`id\` 通过 \`id::text\` 映射到行主键,而不是一个 JSONB 字段。

## 判断:比较究竟发生在哪里

节点 5 是决策层,它藏着这次搭建中最令人意外的一个机制。

可迁移的坑:一个看似数值的过滤器可能按文本比较,而文本排序不是数字排序。在这个引擎上,表 CRUD 的 where 子句把一切都按文本比较。存储的列通过 \`jsonb_extract_path_text(data, :col)\` 读取,主键通过 \`id::text\`,而绑定值经过 \`.toString()\`(\`CrudRepository.java:378-416\`)。与此同时,决策条件里的 SpEL 比较是数值的(\`expressions.md:96\`)。同样长相的 \`>\` 运算符,两个不同的世界。

| 比较运行在哪里 | 比较类型 | 可靠的运算符 | 会误导的运算符 | 引用出处 |
|---|---|---|---|---|
| 表 CRUD where 子句 | 文本 / 字典序 | \`=\`、\`!=\`、\`IN\`、\`IS NULL\`、\`IS NOT NULL\`、\`LIKE\` | \`>\`、\`<\`、\`>=\`、\`<=\` | \`CrudRepository.java:378-416\` |
| \`core:decision\`(SpEL) | 数值 | 所有比较运算符 | 对数字而言无 | \`expressions.md:96\` |

后果是一个真实的潜伏 bug。在一个 where 子句里,\`amount > 9\` 会排除 \`'100'\`,因为 \`'1'\` 排在 \`'9'\` 之前。而 \`id > 5\` 会悄悄跳过 id 10 到 99(\`WorkflowBuilderHelpModule.java:258-262\`)。排序运算符在 where 子句里只有当字典序恰好符合意图时才安全,也就是零填充的字符串或 \`yyyy-MM-dd\` 形式的 ISO 日期(\`WorkflowBuilderHelpModule.java:262\`)。没有一个可用的数值转换排序运算符;一个数值感知的比较是撰写本文时已知但尚未发布的修复。

所以"价格是否变动超过 5%"这个数学属于节点 6b,一个 \`core:decision\`,而不是查询。它需要之前的价格,那存活在 \`find_rows\` 的结果里:\`find_rows\` 返回 \`items[]\`,每个匹配的行暴露其压平的字段,所以基线价格在 \`items[0].price\`(\`ConceptsHelpProvider.java:281\`;数组索引见 \`expressions.md:28-32\`)。因为存储的值是通过与每次 JSONB 读取相同的文本路径回来的,算术必须对它做类型转换:在相减之前把两个操作数都用 \`double()\` 包起来。条件:

\`\`\`
{{ (double(core:normalize.output.result.price) - double(table:baseline_lookup.output.items[0].price)) / double(table:baseline_lookup.output.items[0].price) > 0.05 }}
\`\`\`

一个决策恰好激活一个分支。第一个为真的条件胜出,其余变为 SKIPPED。它的端口是 \`if\`、\`elseif_N\` 和 \`else\`(\`nodes.md:29\`;\`WORKFLOW_NODE_TYPES.md:411-418\`)。

有一条结构规则把整张图绑在一起。边是朴素的 \`{from, to}\` 记录,带一个可选的 \`:port\` 后缀,而分支条件从不存活在边上。它们存活在 \`cores[]\` 节点里,作为 \`decisionConditions\` 或 \`switchCases\`(\`WORKFLOW_NODE_TYPES.md:33-40\`、\`:349-361\`)。仅从边的拓扑就能推出两个后果。一个源出发的多条无条件边构成一个隐式的 Fork,并行运行所有分支。进入一个节点的多条边构成一个隐式的 AND-merge,它会等待每个前驱解决,无论是 COMPLETED 还是 SKIPPED(\`WORKFLOW_NODE_TYPES.md:1008-1010\`、\`:1053-1056\`、\`:925-940\`)。

## 幂等写入守卫,画成一个真实的子图

一个自我刷新的触发器每小时触发同一次读取。没有守卫,它每小时都会插入同一个 SKU 的基线,表就填满了重复项。在任何引擎上修复这个问题的一般模式:先查找,按计数判断,然后仅在条目是新的时才写入。当同一条目可以被重新拉取时,永远不要无条件插入。

这个引擎没有 upsert 也没有 truncate 操作,这恰恰是为什么守卫是强制的而非可选的(\`tables.md:49\`;\`CrudRepository.java\` 的 \`deleteRows\` 需要一个经过校验的 where)。

| 步骤 | 节点 | 走的分支 / 端口 | 对表的影响 | 引用出处 |
|---|---|---|---|---|
| 1 | 按 \`sku\` 的 \`find_rows\` | (喂给决策) | 读取,不写任何东西 | \`ConceptsHelpProvider.java:281\` |
| 2 | 按 item_count 的 \`core:decision\` | \`if\`(真)= 从未见过 | 暂时无 | \`WorkflowBuilderHelpModule.java:252-254\` |
| 3a | \`insert_row\`(基线) | 在 \`if\` 分支上 | 写入一个新行 | \`tables.md:52\` |
| 3b | 重大变动决策 | 在 \`else\` 分支上 | 暂时无 | \`nodes.md:29\` |
| 4 | \`update_row\`(批准之后) | approved 端口 | 命名的 JSONB 键被合并 | \`tables.md:49\` |

守卫的两个确切字符串,用代码块围起来以保持模板完整:

\`\`\`
find_rows {column:'sku', operator:'=', value:'{{core:normalize.output.result.sku}}'}
\`\`\`

\`\`\`
{{table:baseline_lookup.output.item_count == 0}}
\`\`\`

让这一切奏效的探测是 \`find_rows\`,它暴露 \`items[]\`(找到的行)和 \`item_count\`(计数)。一个为 0 的 \`item_count\` 是"尚未处理"的信号,它把表变成跨运行的共享内存(\`ConceptsHelpProvider.java:281\`)。先查找再判断的守卫,就是让一个刷新型工作流安全的东西(\`AGENTS.md\` \`dedupe_idempotent_write\`)。

已知 SKU 路径上的写入是一个 \`update_row\`,它同时需要一个 where 和一个非空的 set map,并只通过 \`data || jsonb_build_object\` 合并命名的 JSONB 键(\`tables.md:49\`)。它是一次部分合并,不是替换,所以它不会把你省略的字段清空。

有一个租户陷阱,如果你不知道它会浪费你一个下午。MCP 的 \`table\` 工具运行在聊天用户的租户下,而不是工作流所有者的。每个 CRUD 查询都被 \`AND tenant_id = :tenant_id\` 限定作用域,所以这个工具可能显示 0 行,而工作流自己的 \`find_rows\` 却看到真实的数据(\`AGENTS.md\`)。要检查或清空一个工作流拥有的表,请从那个工作流内部运行操作,在正确的租户里。

## 先门控,再行动

节点 7 是不可逆步骤之前的人工检查。一般原则:在任何你无法撤销的动作之前放一个阻塞门控,并让接下来发生什么是确定性的。

在这个引擎上,门控是一个 \`USER_APPROVAL\` 信号。节点让出 AWAITING_SIGNAL,运行暂停。USER_APPROVAL 始终是阻塞的,不像接口信号,后者只有当 \`__continue\` 被映射时才阻塞(\`EXECUTION_ENGINE.md:15\`;\`INTERFACE_NODE_GUIDE.md:783-787\`)。节点有三个命名的恢复端口,\`approved\`、\`rejected\` 和 \`timeout\`,它根据所做的决定确定性地路由(\`nodes.md:39\`;\`WorkflowHelpProvider.java:665\`)。未设置时默认超时为 24 小时(\`nodes.md:39\`)。

因为刷新每小时触发,有两个问题重要。第一,如果批准被触发两次会怎样?没坏事。解决是处理前先认领:\`resolveSignal()\` 对一个已解决的信号返回 false,所以一次重复触发的批准永远不会让 DAG 双重推进(\`INTERFACE_NODE_GUIDE.md:1008\`)。第二,当一个人还压着这个决定时,下一次计划触发会怎样?每次触发都开启一个新的 epoch,先前 epoch 的结果保留并保持可浏览,而一个阻塞信号会推迟触发周期的重置直到它被解决(\`EXECUTION_ENGINE.md:15\`)。刷新不会踩踏一个待处理的决定。

在 \`approved\` 端口上,真正的动作触发。那可以是一个一等的 Send Email 节点或任何已连接的 \`mcp:\` 集成(\`nodes.md:62\`),随后是受守护的 \`update_row\`。在 \`rejected\` 和 \`timeout\` 端口上,什么都不写,什么都不发。

## 在你称它上线之前,证明每一个分支

测试规则不容商量:对着一个实时的 orchestrator 演练每一个分支,并并行地跟踪服务日志。一个绿色的响应加上日志里的一段堆栈跟踪是一次失败,不是通过(\`AGENTS.md\` Feature Development Flow step 4)。"它返回了 200"不是分支起效的证据。

| 场景 | 触发条件 | 期望的分支 / 信号 | 通过断言 | 失败信号 |
|---|---|---|---|---|
| 新 SKU 插入 | 没有基线行的 SKU | \`if\` 分支,\`insert_row\` | 恰好插入一行 | 重复行,或日志中的堆栈跟踪 |
| 无变动 | 已知 SKU,价格在 5% 以内 | 重大决策的 \`else\` | 无标记、无审批、无告警 | 任何告警或暂停 |
| 重大变动 | 已知 SKU,变动超过 5% | 运行在 AWAITING_SIGNAL 处 PAUSES | 状态 AWAITING_SIGNAL USER_APPROVAL | 运行未暂停就完成 |
| 审批端口 | 分别解决三个端口 | approved / rejected / timeout | approved 写入 + 告警;其余两者都不做 | 在 rejected/timeout 上写入 |
| 重跑幂等 | 触发计划两次 | 守卫阻挡第二次插入 | 行数稳定 | 行数增长 |

在你信任这张图之前,把五个都跑一遍。重大变动场景应当可见地暂停;如果它完成了,你的阈值数学在错误的层里,很可能是一个假装是数值的字典序 where 子句。

有三条教训可带到你接下来搭建的任何引擎上。输出嵌套:钻到 \`{{core:normalize.output.result.sku}}\`,永远不是 \`{{core:normalize.output.sku}}\`,因为平台会包裹你返回的东西。文本比较:在一个 \`core:decision\` 里计算 5% 的变动,而不是在 \`find_rows\` 的 where 子句里,因为那个比较是字典序的。字符串化的对象:在一个 \`core:code\` 节点里构建带类型的值,而不是一个把它们压平成字符串的 \`core:transform\`。而先查找再判断的守卫,就是那个让一个自我刷新的工作流在任何地方都安全的模式,因为一个会行动的计划,其可信度只等于它对重复自身的防御。
`;

export default content;
