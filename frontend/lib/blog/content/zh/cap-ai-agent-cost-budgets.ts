// cap-ai-agent-cost-budgets - zh
// Translated from the English body; the structure must stay identical to it.
// Formulas and code samples are fenced on purpose: an inline code span over
// ~45 chars overflows the page on a phone. The hedges are load-bearing here.
const content = `## 告警不是上限

监控是异步的、事后的：它告诉你已经花掉了多少，因此它不可能成为强制执行层。上限是同步的、执行前的：它拒绝下一次调用。对账和 stop-reason 遥测仍然重要，但那是用来给上限定尺寸、以及发现某个上限设得过紧的，不是用来叫停工作的。

在继续往下读之前，先做这个测试，它不需要任何阈值：把你所配置的上限在最近一个观察窗口内的拒绝记录拉出来。它有没有拒绝过任何东西？一个从未拒绝过任何东西的数字不是一项控制措施，它只是一条注释。

供应商层面的上限是兜底，不是第一道强制执行：

- OpenAI 的项目级和组织级支出限额**默认是软预算**：它只发通知，请求照常放行。硬上限是存在的，但作为一个单独的、需要主动开启的开关，开启后会返回 HTTP 429，直到限额被调高或重置（[支出限额指南](https://developers.openai.com/api/docs/guides/spend-limits)）。
- Anthropic 的 [Spend Limits API](https://platform.claude.com/docs/en/manage-claude/spend-limits-api) 仅限 Claude Enterprise，明确不向 Claude Platform（Console）组织开放，并且只支持 \`monthly\` 这一种周期（在日历月第一天 00 UTC 重置）。它限制的是人类席位的用量，不是 agent 的 API 支出。
- Anthropic 的文档同样否定了把供应商支出当作闸门：\`period_to_date_spend\`“在支出读数暂时不可用时可能读作 '0'；请将其视为信息性的，而非事务性的。”
- Anthropic 确实按用量层级强制执行一个月度上限（Start $500、Build $1,000、Scale $200,000、Custom 不设上限），触顶后会暂停 API 用量直到下个月（[速率限制](https://platform.claude.com/docs/en/api/rate-limits)）。这是一个真实的上限，但它是组织级且按月的：一次失控的运行就能把它吃光，把一个成本 bug 变成整个组织的服务中断。

单步成本呈超线性增长，因为上下文会累积，这就是为什么数步数并不能约束美元。那个推导在配套的成本模型文章里。仅就定尺寸的输入而言，Anthropic 报告称 agent 使用的 token 大约是聊天的 4 倍，多 agent 系统大约是 15 倍（[multi-agent research system](https://www.anthropic.com/engineering/multi-agent-research-system)）。

**披露。** 下文中的实现细节、常量和拒绝消息来自 LiveContext 的 \`agent-service\`，也就是本博客所属的平台。请把它们当作某一个系统的选择来读，可在其社区版源码中核实，而不是当作经过调研的行业普遍做法。

## 预算对象的五个组成部分

预算不是一个数字。它是一个由五部分构成的对象，缺少其中任何一部分的预算，都会以一种特定的、可诊断的方式失效。

**1. 作用域。** 记账所处的层级。这套已上线的系统中存在四种：租户/账户余额（宏观）、agent/步骤（微观）、\`parent_reservation\`（调用链上的某个祖先拒绝为子 agent 的派生出资），以及按运行/按 epoch。一次没有指明是哪个作用域触发的拒绝是无法调试的。

**2. 单位。** 美元、token，或仅仅是计数（轮次、supersteps、工具调用）。计数在金额意义上是浮动的。只有 token 或金额才算预算。

**3. 强制执行点。** 事后对账、迭代前预测、派生前预留，或对输入的准入上限。每一种都有不同的超支上界（表 1）。

**4. 预留策略。** 预算是在事后扣减，还是在工作开始之前就先扣住。这是唯一能让并行扇出变得安全的部分。

**5. 终止响应。** 在上限被触发的那一刻，调用方收到什么。现实中存在五种不同的行为，它们彼此不可互换。

**表 1：强制执行点及其超支上界**

| 强制执行点 | 何时运行 | 它能拒绝什么 | 最坏情况超支 | 对并行扇出安全吗？ |
|---|---|---|---|---|
| 事后对账 / 告警 | 调用结算之后 | 什么都不能 | 无界 | 否（是检测，不是强制执行） |
| 迭代前预测 | 下一次模型调用之前 | 下一次迭代 | 一次迭代（在浏览器步骤上最高可达首次迭代的 40 倍） | 否 |
| 派生前预留 | 子 agent 启动之前 | 整个子 agent | 对该子 agent 为零 | 是 |
| 对输入的准入上限 | 提示词组装之前 | 超大的上下文 / 输出 | 约束迭代本身 | 是（可与其他方式叠加） |

有两个设计决策，人们把它们当作配置，但它们其实属于这个对象：

**守卫顺序就是作用域设计。** 这个实现恰好运行两个守卫，先 \`TenantBudgetGuard\` 再 \`AgentBudgetGuard\`，先拒绝者胜并短路，有两条被记录在案的理由：租户耗尽会让 agent 预算变得无意义，而且把租户守卫放在最前面，是为了在下游信用预留的往返调用之前就尽早拒绝。

**周期是一个定尺寸决策。** 累计型累加器会把上限变成一个终生总额，于是一个长生命周期的 agent 会在数月里悄悄逼近耗尽。按周或按月重置会把同一个数字变成一个速率。重置可以在执行开始时用 compare-and-set 更新惰性地解析，而不必依赖调度器（\`BudgetResolver\` 模式：cumulative、weekly、monthly；未知值按 cumulative 处理）。

一个值得在你自己技术栈里检查的语义陷阱：这个平台面向 agent 的工具参数帮助文本仍然写着“每次 LLM 迭代花费 1 个 credit”，而守卫比较的却是针对同一个 \`credit_budget\` 字段的金额预测。另外两处帮助文本又用“至少一个 credit”和“实践中多于 1 个 credit”来打折扣，所以这些文档彼此之间也自相矛盾。文档里是一条经验法则、代码里是金额比较，这是一类 bug，不是措辞上的小瑕疵。

## 你无法叫停正在进行的那次调用

token 消耗只有在一次调用完成之后才可知。任何运行中预算都无法阻止某一次昂贵的调用突破上限；它只能阻止下一次。因此真实的最坏情况是**预算加一次迭代**，而不是预算。把这一点直说出来，不要暗示它是一个硬上限。

共享的跨语言 fixture 文件中所陈述的门控公式：

\`\`\`
projectedNext = max(
    growthProj,
    lastDeltaProj * LAST_DELTA_SAFETY_FACTOR,
    worstCaseSingleIter
)
deny iff (runCostSoFar + projectedNext > balance)
      OR (runCostSoFar >= balance)

LAST_DELTA_SAFETY_FACTOR = 2.0
RATE_DIVISOR             = 1000
ROUND_DECIMALS           = 6 (HALF_UP, per subterm)
\`\`\`

在复制它之前有两点提醒。两个 Java 守卫在预测比较上实现的是 \`>=\` 而不是 \`>\`；JS 的孪生实现用的是 \`>\`。在预测总额恰好相等时，它们的判断会不一致，而且没有任何 fixture 用例正好落在那个边界上。而且 agent 作用域的比较不是两项而是四项：

\`\`\`
totalProjected = consumedBeforeRun
               + creditsReserved
               + runCostSoFar
               + nextProjected
deny iff totalProjected >= totalBudget
\`\`\`

\`creditsReserved\` 是当前被在途子 agent 锁定的 credits，所以父 agent 自己的循环会被它的子 agent 所持有的额度节流。

每个预测分支都不是冗余的：

- \`growthProj\`（每次已完成迭代的平均 token 数）捕捉稳定的爬坡。
- \`lastDeltaProj\`（上一次迭代的增量乘以 2）捕捉会被平均值稀释掉的突发。
- \`worstCaseSingleIter\`（完整上下文窗口乘以完整的最大输出，按该模型的费率）对增长模式不敏感，能捕捉第 1 次迭代上的阶跃式跳变。

最坏情况分支承担了真正的工作。在 opus 级别的定价下（每 1M 为 15 / 75 USD），配 200K 上下文和 64K 最大输出：

\`\`\`
worstCaseSingleIter = 200 * 15 + 64 * 75
                    = 3,000 + 4,800
                    = 7,800 credits      (1 credit = $0.001)
\`\`\`

任何低于 7,800 credits 的余额，针对那种突发迭代只受最坏情况分支保护，别无其他。

第二个拒绝条件 \`runCostSoFar >= balance\` 在逻辑上是冗余的：只要预测值为正，第一个条件就已经涵盖了它。它存在纯粹是为了让拒绝消息指出真正的失败模式，而不是表现为一次预测超支。

成本公式，便于复现：

\`\`\`
inputCost  = inputRate  * promptTokens     / 1000
outputCost = outputRate * completionTokens / 1000
total      = inputCost + outputCost + fixedCost
\`\`\`

费率是每 1M token 的美元数；\`/1000\` 把它转换成 1 credit = $0.001 的 credit 单位。求和前把每个子项四舍五入到 6 位小数，否则同一个公式的两个实现会漂移。

关于这个机制的三条诚实的约束：

**按 agent 的守卫需要两次已完成的迭代。** 只有一个样本时，\`lastDelta == runCost == growth\`，于是 \`lastDelta * 2 = 2 * runCost\`，任何消耗超过 \`budget/3\` 的首次迭代都会把第 2 次迭代自我拒绝掉，哪怕下一次调用其实很小。租户守卫没有这个门槛：它从第 1 次迭代就开始预测，此时 growth 和 lastDelta 都为零，所以那里只有最坏情况分支起约束作用。这是有意为之，也正是为什么第 1 次迭代的天花板归最坏情况分支管。

**陈旧性会放大这个缺口。** 每 5 次迭代重新拉取一次余额（当成本费率不可靠时降为每次迭代都拉取）会在一次迭代的预测缺口之上再叠加一个陈旧性窗口。一个自适应变体会在燃烧率超过余额的 70% 之后改为每次迭代刷新。

**未知模型的兜底是一个有 bug 历史的真实决策。** 在费率上悲观兜底（回退到最高档，每 1M 为 15 / 75 USD），但在天花板上宽松（把上下文窗口留空，于是 \`worstCase\` 返回 null，守卫退化为只看 growth）。此前一个 0.015 / 0.075 的兜底曾悄无声息地完全绕过了守卫。

守卫自身的注释里带着这样一条自白：曾经有一个原子化的按轮次预留层被做成原型又被回退，因为最多一次迭代的超支被判定为可接受，换来的是更简单的调用路径。而且这个预检查被明确称为“一个快照，不具权威性”：执行后的对账仍然会跑，两者可能不一致。

## 触顶那一刻：调用方实际拿到什么

在这个平台的 stop-reason 契约中，一次预算击杀被归类为 \`PARTIAL\`，而不是 \`FAILURE\`：可用但被截断的输出。它不会抛出异常，而且一次产生了 token 的预算击杀会以执行状态 \`COMPLETED\` 持久化，所以只有 \`stop_reason\` 列携带细节。这里需要两点限定，因为在这一点上说半真话，正是一个过紧的上限得以长期隐形的方式：一次零 token 的预算击杀会被持久化为 \`FAILED\`，而且每日指标汇总确实会把每一次被预算叫停的运行计入其失败计数。真正隐形的是损害的形态，而不是损害这件事本身。如果你只盯着错误率，一个过紧的上限会在数月后以质量退化的形式浮现。

**表 2：每个 stop reason 在哪里被判定（契约 10 个取值中的 6 个）**

| Stop reason | 终止类别 | 在哪里判定 | 调用方必须做什么 |
|---|---|---|---|
| \`MAX_ITERATIONS\` | partial | 事后，在循环退出之后 | 把输出当作被截断的；提高 n 或预算 |
| \`TIMEOUT\` | partial | 事后，在循环退出之后 | 仍在积极工作，但超过了墙钟时间；恢复或放宽 |
| \`BUDGET_EXHAUSTED\` | partial | 迭代前守卫，在调用之前 | 读取 \`budgetScope\`（\`tenant\`、\`agent\`、\`parent_reservation\`、\`browser\`），决定是充值还是调整规模 |
| \`LOOP_DETECTED\` | partial | 迭代中，在工具调用被解析之后 | 检查那个重复的签名；任务本身是有问题的 |
| \`STOPPED_BY_USER\` | partial | 取消通道 | 保留部分输出 |
| \`INACTIVITY_TIMEOUT\` | failure | 看门狗，不是循环；一个后置流程会重新归类 \`STOPPED_BY_USER\` | 它静默了，只能被杀掉；去排查那个挂起 |

\`BUDGET_EXHAUSTED\` 是唯一携带 scopes 数组的取值。一次不告诉你是哪个天花板触发的预算停止，会逼着你去猜。

拒绝不应该是异常。一个可行的实现会跳出循环并记录结构化元数据：stop reason，加上 \`budgetScope\`，再加上一个指明是哪个预测分支触发的 \`denialReason\` 字符串：

\`\`\`
tenant balance X would be exceeded
(run=A + next=B [growth=..., lastDelta=..., worstCase=...])
\`\`\`

在同步路径和流式路径上使用相同的键，这样指标就不会漂移。

在所调研的领域内，存在五种终止行为，它们彼此不可互换：

1. **异常**：\`MaxTurnsExceeded\`（OpenAI Agents SDK）、\`GraphRecursionError\`（LangGraph）、\`UsageLimitExceeded\`（Pydantic AI）、\`ModelCallLimitExceededError\`（LangChain）。
2. **带类型、可分支的结果**：AutoGen 在 \`TaskResult\` 上的 \`stop_reason\`、Claude Agent SDK 的 \`error_max_budget_usd\` 子类型、LangChain 的 \`exit_behavior='end'\` 并注入一条 AI 消息。
3. **HTTP 200 下的静默截断**：Anthropic 的 \`max_tokens\` 会设置 \`stop_reason: "max_tokens"\` 并返回成功（[Messages API](https://platform.claude.com/docs/en/api/messages)）。
4. **HTTP 429 拒绝**：OpenAI 那个需要主动开启的硬限额。Anthropic 只为 \`rate_limit_error\` 记录了 429，并把计费问题放在 402，所以它的月度层级支出上限没有任何被记录在案的状态码；这一条请对照你自己的日志确认。
5. **尽力而为的降级回答**：CrewAI 的 \`max_iter\`，此时 agent“必须给出它最好的答案”（[CrewAI agents](https://docs.crewai.com/en/concepts/agents)）。

一个值得在你自己技术栈里检查的语义冲突：[LiteLLM 的迭代预算](https://docs.litellm.ai/docs/a2a_iteration_budgets)返回 429 并带错误类型 \`budget_exceeded\`，而按 HTTP 惯例 429 意味着稍后重试。对于一个会随时间重置的组织级上限，这是站得住脚的，因为等待最终确实会让请求变得可满足。但对于按运行或按 agent 的预算，这就是错的：等待永远不会满足它，而标准的 SDK 重试逻辑会一头撞在墙上反复捶打。LiteLLM 是这里唯一得到确认的实例，不是一个已被证实的类别。检查一下你的客户端重试策略拿到 429 之后会做什么。

停止之后应当留下什么，是这份契约的另一半。[Claude Agent SDK](https://code.claude.com/docs/en/agent-sdk/agent-loop) 是这个领域里最接近参考设计的东西：\`result\` 字段（最终答案）只在 \`success\` 子类型上出现，但每一种错误子类型仍然携带 \`total_cost_usd\`、\`usage\`、\`num_turns\` 和 \`session_id\`。你丢掉的是答案，不是会话。注意这里的不对称：单次的 \`query()\` 会在产出错误结果之后抛出，而流式输入的会话会继续存活。

为什么这在商业上重要，来自一份[事故报告](https://github.com/anthropics/claude-code/issues/68430)：运维人员当时只有两个选择，要么“让它继续跑，眼看着它把会话预算烧在一个永远不会成功的递归循环上”，要么“杀掉它并失去一切，包括早期 agent 已经完成的正当工作”。一个会丢弃部分成果的上限，把成本问题变成了全损问题，而这恰恰就是运维人员关掉上限的原因。

父侧的拒绝应当遵循同样的规则：不是抛出一个错误，而是合成一个失败结果，指明那个祖先和作用域。

\`\`\`
Cannot spawn child 'X': ancestor agent <id> has
insufficient free budget for reservation N
(scope=parent_reservation, BUDGET_EXHAUSTED)
\`\`\`

最后，让这个上限可以从 agent 内部被自省。已上线的响应结构：

\`\`\`
budget.{ unlimited, total, consumed,
         consumed_own, consumed_from_subagents,
         reserved_for_subagents, free,
         reset_mode, last_reset }

free = max(total - consumed - reserved_for_subagents, 0)
\`\`\`

在 unlimited 分支上，\`total\` 和 \`free\` 为 null，\`reserved_for_subagents\` 返回 0。明确的规则：如果 \`free\` 低于某个子 agent 的预算，派生就会以 \`scope=parent_reservation\` 失败。

## 各技术栈能强制执行什么，不能强制执行什么

**表 3：每个技术栈实际能强制执行什么**（范围限于所调研的平台；Google ADK 和 LlamaIndex 不在其中）

| 技术栈 | 强制执行的单位 | 默认值 | 触顶时的行为 | 是否传播到子 agent？ |
|---|---|---|---|---|
| [Claude Agent SDK](https://code.claude.com/docs/en/agent-sdk/python) | 每次运行的美元（\`max_budget_usd\`），外加轮次 | 两者均不设限 | 带类型的结果子类型 \`error_max_budget_usd\` / \`error_max_turns\`，会话保留 | \`usage\` 不含子 agent 的 token；\`total_cost_usd\` 包含它们 |
| Anthropic Messages API | Token（\`max_tokens\`） | 无默认值；必须自行设置 | HTTP 200 并带 \`stop_reason: "max_tokens"\`，内容被截断 | N/A |
| OpenAI（账户级） | 每月美元 | 默认为软限制 | 通知，或在主动开启硬限额时返回 429 | N/A |
| [OpenAI Agents SDK](https://openai.github.io/openai-agents-python/running_agents/) | 轮次（[\`DEFAULT_MAX_TURNS = 10\`](https://github.com/openai/openai-agents-python/blob/main/src/agents/run_config.py)） | 10 | 抛出 \`MaxTurnsExceeded\` | 未见文档说明 |
| [LangGraph](https://docs.langchain.com/oss/python/langgraph/graph-api) | Supersteps（\`recursion_limit\`） | 文档相互矛盾：OSS graph 运行时自 v1.0.6 起为 1000，SDK 的 \`Config\` schema 和现场反馈则为 25 | 抛出 \`GraphRecursionError\` | 两个有记录的传播 bug（见下文） |
| [LangChain middleware](https://reference.langchain.com/python/langchain/agents/middleware/model_call_limit/ModelCallLimitMiddleware) | 仅调用次数，没有 token 或成本预算 | 两个限额均为 \`None\` | 可配置：\`exit_behavior='end'\` 注入一条消息，\`'error'\` 抛出 | 不适用 |
| [Pydantic AI](https://pydantic.dev/docs/ai/api/pydantic-ai/usage/) | Token、请求数、工具调用 | \`request_limit=50\`，token 限额为 \`None\` | 抛出 \`UsageLimitExceeded\`；可选的预检查 | 未见文档说明 |
| AutoGen（[conditions](https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.conditions.html)、[teams](https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.teams.html)） | Token（\`TokenUsageTermination\`） | 团队默认值：\`termination_condition=None\`、\`max_turns=None\` | 带 \`stop_reason\` 字符串的类型化 \`TaskResult\` | 团队作用域 |
| [CrewAI](https://docs.crewai.com/en/concepts/agents) | 迭代次数（\`max_iter\`） | 文档说 20，源码说 25 | agent“必须给出它最好的答案” | 未见文档说明 |

这张表说出了五件散文会埋没掉的事：

**几乎所有东西都默认无界。** Claude Agent SDK 的 \`max_turns\` 和 \`max_budget_usd\` 都是不设限的；[AutoGen teams](https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.teams.html) 直白地说群聊“会无限期运行下去”；Anthropic 的 Enterprise 席位支出限额在任何层级都没有默认值时默认为无限（相比之下，API 层级的上限始终生效）。

**这次调研中唯一没有默认值的成本旋钮是 Anthropic 的 \`max_tokens\`**，Messages API 的 schema 要求你显式设置它。它也是唯一一个被突破时返回 HTTP 200 并带截断内容的。该 schema 现在还记录了把它设为 0 来预热提示词缓存的用法，所以“必填”并不意味着“有意义的天花板”。

**这次调研中唯一的按运行美元天花板，是针对一个估算值来强制执行的。** Anthropic 的成本追踪页面警告说，\`total_cost_usd\`，也就是 \`max_budget_usd\` 被拿来比较的那个确切数字，由“客户端估算，而非权威计费数据”构成，是用一张在构建时打包进去的价格表算出来的，并明说“不要基于这些字段向最终用户计费或触发财务决策”。它还是在轮次之间求值的，所以支出可能超出所配置的限额一个轮次。这就是同一个“预算加一次迭代”的保证，出现在这个领域里设计得最好的产品上。

**LangChain 根本没有 token 或成本预算。** \`ModelCallLimitMiddleware\` 和 \`ToolCallLimitMiddleware\` 限制的是调用次数，两者都默认为 \`None\`，一位维护者[在 2026 年 7 月确认了这个 token 预算缺口](https://forum.langchain.com/t/a-proposal-to-add-token-usage-budgets-to-langchain-agents-via-a-new-middleware-since-the-existing-limiters-only-cap-call-count-not-tokens/4147)。尽管如此，它的 \`exit_behavior\` 参数是这个领域里最干净的可配置失败模式，值得借鉴。

**Pydantic AI 是唯一带预检查的技术栈**：\`count_tokens_before_request\`（默认 \`False\`）会调用供应商的 token 计数 API，在一个超预算的请求被计费之前就拒绝它。它也带了一个陷阱：\`request_limit\` 会静默地默认为 50，所以只设置 \`input_tokens_limit\` 会继承一个 50 次请求的上限，除非你传 \`request_limit=None\`。

**传播是天花板沦为摆设的头号途径。** 两个有记录的案例：[LangChain deepagents #1698](https://github.com/langchain-ai/deepagents/issues/1698)，其中 \`SubAgentMiddleware\` 调用子 agent 时没有传 \`config\` 参数，于是无论父级被设成 150，它们始终按默认递归上限运行；以及 [langgraphjs #1524](https://github.com/langchain-ai/langgraphjs/issues/1524)，其中 \`withConfig\` 的 \`recursionLimit\` 被静默忽略，而由此产生的错误消息还告诉你去设置那个正被忽略的键。

两个会静默击败朴素预算代码的计量陷阱，都来自 [Anthropic 的成本追踪文档](https://code.claude.com/docs/en/agent-sdk/cost-tracking)：\`usage\` 字段只统计顶层循环，不含子 agent 的 token（而 \`total_cost_usd\` 和 \`model_usage\` 包含它们）；并行工具调用会发出多条共享同一个 message id、usage 完全相同的 assistant 消息，于是一个按消息累加 usage 的计量器会重复计数并提前触发。按 id 去重。

速率限制不是支出限额，而且可能会奖励昂贵的路径：缓存的输入 token 按 10% 计费，但在大多数模型上不计入每分钟输入 token 限制，而 \`max_tokens\` 完全不进入每分钟输出 token 限制（[速率限制](https://platform.claude.com/docs/en/api/rate-limits)）。

## 循环守卫约束 n；预算在给定 n 下约束成本

循环检测器和预算回答的是不同的问题。检测器约束发生多少次迭代；预算约束这些迭代可以花多少钱。两者都不能替代对方。

来自一个已上线检测器的真实阈值，带两个独立的触发条件：

| 条件 | 键 | 升级档位 | 硬停止 |
|---|---|---|---|
| 相同调用 | 工具名 + 排序后的参数，取哈希 | 5 次时告警 | 15 |
| 连续调用 | 工具调用总数，任意签名 | 15、25、35 | 40 |

连续调用的天花板被刻意设得很高，这样正当的批量操作不会被杀掉。两个硬停止都可以按 agent 配置，而中间档位是**推导出来的**（相同调用的告警 = \`ceil(stop/3)\` 且最小为 2；连续调用的档位 = \`ceil(stop * 3/8)\`、\`5/8\`、\`7/8\`），这样在任何自定义值下严重性阶梯都保持单调，并强制执行最小停止值。

这个阶梯不只是打日志：每一档都会在停止之前把一条消息注入回 agent 的上下文，从一条信息性提示逐级升级到“还剩 1 次迭代，停止使用工具，立即回答”，直到终止。所陈述的设计意图是：重复的模式应当被自动化成工作流，而不是被循环。

值得点名的覆盖缺口：那个检测器只统计四个工具名。其他每一个工具调用对两个计数器都是不可见的，所以一个在未被跟踪的工具上的循环永远不会产生 \`LOOP_DETECTED\`。在信任一个循环守卫之前，先检查你自己技术栈里对应的覆盖范围。

不要指望模型自己注意到自己的浪费。RedundancyBench 标注了 200 条轨迹（从收集到的成功运行中筛选而来），包含超过 8,000 个被标注的步骤，而对冗余步骤的最佳自动化步骤级检测得分为 24.88%（轨迹级为 72.50%）（[arXiv 2605.29893](https://arxiv.org/abs/2605.29893)）。上限必须是机械性的。

来自同一实现的其他运行约束默认值，作为一个参照点：最大迭代 100、执行超时 3600 s、每轮最大 token 16,000，以及一个 5 分钟的不活动看门狗，其按 agent 的覆盖值只接受 0（禁用）或 10 到 7200 秒，这样一个乱填的值就无法武装出一个秒级的看门狗。

墙钟时间值得单列一行，作为最后一道上限。一起有记录的事故在不到 5 分钟内消耗了 400 万 token（[claude-code #68619](https://github.com/anthropics/claude-code/issues/68619)），比任何按轮次或余额刷新的采样都反应得更快。这是从一起事故得出的推断，不是有出处的最佳实践，但这个算术很难反驳。

## 检验一个真正上限的方法

六个要点，每一个都能从你自己的日志里回答：

1. 一次拒绝有没有指明是哪个作用域触发的？
2. 检查是否是同步的，且发生在下一次调用之前？
3. 终止响应是否带类型、不可重试，并且携带成本账本外加一个恢复句柄？
4. 上限是否传播到子 agent，并有一个设置父级限额、断言子 agent 继承它的测试来证明？
5. 粒度比 \`g\`，也就是预算除以有界的最坏情况迭代，是否至少为 3？配套的定尺寸文章推导了这个下限，并展示了大多数按步骤的金额上限都达不到它。
6. 在观察窗口内，这个上限有没有真的拒绝过？

诚实的保证：一个运行中预算把成本约束到**预算加一次迭代**，而不是约束到预算。派生前预留是唯一零超支的机制，而且它只覆盖子 agent。

如果同一个公式存在于两个运行时中，为一致性做工程投入是值得的。一个包含具名用例的共享 fixture 文件，同时被一个 JUnit 参数化测试和一个 Node 测试运行器消费，是阻止两者漂移的最廉价方式，而且四舍五入必须逐个子项对齐。注意它的局限：一个 fixture 只覆盖它所包含的用例。一个预置了显式费率的 fixture 永远不会在任何一侧走到未知模型的兜底路径上，而那恰恰正是这里描述的两个实现相差一个数量级的地方；一个只实例化租户守卫的 fixture 也永远不会注意到两个 agent 守卫用的是不同的比较运算符。

把未知的东西说出来。关于生产环境中的 agent 多久失控一次，没有已发表的基准发生率。最强的那份目录明确声明不涉及流行度，只主张其存在性以及在多个独立开发的项目中的复现性。用机制和量级来推理，而不是发明一个频率。

而且要对量级保持现实。根据同一份 2026 年目录中的事故条目（[arXiv 2606.04056](https://arxiv.org/abs/2606.04056)），有记录的超支集中在数百到几千美元：某一例中约 $2,150 的意外支出，某个单一用户四天内 $235，某次超出优化器预算 70%。把这个与这个领域里被转载最多的失控轶事对比一下，[“We spent $47,000 running AI agents”](https://todatabeyond.substack.com/p/we-spent-47000-running-ai-agents)，它没有点名任何公司，拿不出发票、代码仓库、配置或日志，随后又被换上第二个署名放大，并通过十几篇互相引用的 SEO 帖子扩散。它自己给出的每周数字是 $127、$891、$6,240 和 $18,400，加起来是 $25,658，而不是 $47,000，而且一个为期四周的成本爬坡与同一篇文章里的“11 天循环”相互矛盾。真实的风险画像是：静默的、反复出现的、中等四位数的。
`;

export default content;
