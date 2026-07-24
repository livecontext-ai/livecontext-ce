// ai-agent-audit-trail - zh
// Translated from the English body; structure identical to it. Legal scope
// statements and every "at least 6 months"/"out of scope"/"not legal advice" are
// load-bearing and must not be strengthened or softened. Fenced code stays fenced.
const content = `Comparing the translation against the source, I found: (1) a spurious meta-line prepended to the body, and (2) two spots where "previous row" (上一行) lost a character and became "one row" (一行). Fixed both. Here is the corrected body:

审计轨迹并不是一份更长的日志。它是一种不同的产物，有着不同的读者、不同的写入契约、不同的时钟。本文发布一份可直接复制的运行级与步骤级 schema，其中每个字段同时承载四样东西：它的数据类型、它的基数类别、它是否可以保存个人数据，以及它存在的理由。一篇配套文章会做存储的算术，把保留分层变成一个可推导的决策，梳理实际的法律义务，并处理与一条保存数年的轨迹相冲突的删除请求。

全文引用的参考实现就是本博客自己的平台。真实的列名、真实的迁移、真实的 bug。

## 你所写给的读者不是你，也不是现在

仪表盘由它的作者阅读，在几分钟内，事故还留在工作记忆里。轨迹则由一个漠然或怀有敌意的第三方阅读，在数月之后，他无法追问一个后续问题。这种差异衍生出下文的每一个决策。

由此得出两条不变量，而几乎没有人把它们写下来：

1. **审计记录永不采样。**
2. **内容字段在其保留窗口内永不降级。**

其余的都是设计判断，而这种判断的存储成本是算术。

先把令人不适的事情摆在前面：**没有任何法规文书规定这份 schema。** 除了 EU AI Act Art. 12(3)（它恰好适用于 Annex III 的一个子项，即 point 1(a)，远程生物特征识别，而不适用于生物特征验证）之外，这里审阅的任何文书（AI Act、ISO/IEC 42001、NIST AI RMF、SOC 2）都没有规定日志 schema、字段类型、基数上限或采样策略。下面这份 schema 是工程判断，目标是满足法律在 Art. 12(2)(a) 至 (c) 中命名的那些*目的*，以及 Art. 86 中的可解释性权利。它不是一份合规产物，我也不会把它当作合规产物来兜售。

一份可用的轨迹里，每个字段都同时承载四样东西：它的数据类型与可空性、迫使它存在的问题或义务、它的基数类别，以及它的保留类别（包括它是否可以被采样或降级）。没有任何已发布的来源填满全部四个角。[OpenTelemetry 的 GenAI 约定](https://github.com/open-telemetry/semantic-conventions-genai)有类型，但没有义务，且默认不含内容；[ARMO 的最小可行审计轨迹](https://www.armosec.io/blog/minimum-viable-audit-trail/)有义务和字段名，但没有类型；AI Act 这一组文书有法律，但承认它并未规定任何字段。

两条防重叠的说明。这份轨迹在步骤上是**线性的，而非二次的**：你付费让模型每一轮都重新发送累积的上下文，但每条消息只存储一次，所以一个六步的运行大约是 ~27 行，与上下文增长无关（二次的那一面属于成本模型那篇文章）。而 \`stop_reason\` 和 \`terminal_category\` 在这里出现，纯粹是作为要记录的字段；其分类法和上限行为属于预算强制那篇文章。

## 一个可观测性仪表盘不是一份审计轨迹

这种混淆按文章标题清晰地分开：以可观测性为题的文章把追踪当作审计轨迹来卖；以审计为题的文章则很少提到标准 schema 默认不记录任何内容。

那个默认值就是头条发现。GenAI 语义约定把 prompt、completion、system instruction、tool argument 和 tool result 全都定为要求级别 \`Opt-In\`，而规范的立场是各类插桩"SHOULD NOT capture them by default"，其中选项 1 是"[Default] Don't record instructions, inputs, or outputs."。所以"我们有 OTel 追踪，因此我们有审计轨迹"这句话开箱即错：你手上有的是 model、token 计数、延迟和 finish reason，没有任何能重建一个决策的材料。

把它打开比看起来更难。在 [opentelemetry-python-contrib](https://github.com/open-telemetry/opentelemetry-python-contrib/blob/main/util/opentelemetry-util-genai/src/opentelemetry/util/genai/utils.py) 中，捕获开关不是一个布尔值：

\`\`\`
OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT
  = NO_CONTENT | SPAN_ONLY | EVENT_ONLY | SPAN_AND_EVENT
  unset            -> NO_CONTENT
  invalid value    -> warning, then NO_CONTENT

# second gate, barely documented:
OTEL_SEMCONV_STABILITY_OPT_IN must select the GenAI
experimental mode, or get_content_capturing_mode() raises.
\`\`\`

仅设置捕获变量还不够。（仅对 Python contrib 包做过验证；其他语言的 SDK 在标志名、枚举值，或第二道门是否存在这一点上，都可能不同。）

与此同时，主流的可观测性建议在两个各自独立的方面上是审计致命的。对于超过 ~1,000 requests/second 的高流量特性，[把调用信封采样降到 10-20%，并把完整的 token 级捕获保留给显式的调试会话](https://www.braintrust.dev/articles/llm-call-observability)；以及[在内容到达后端之前对其擦除或掩码](https://mlflow.org/articles/setting-up-llm-observability-pipelines-in-2026/)。当你必须为之辩护的那个决策，恰好落在你丢弃的那百分之九十里时，一份百分之十的采样毫无用处。

| 维度 | 可观测性仪表盘 | 审计轨迹 |
|---|---|---|
| 消费者 | 作者，几分钟后 | 一个漠然或怀有敌意的第三方，数月后 |
| 读取延迟 | 数秒到数小时 | 数月到数年 |
| 采样 | 预期会有（10-20%，或基于尾部） | 禁止 |
| 内容默认 | 关闭（OTel GenAI 内容为 Opt-In） | 开启，在其保留窗口内 |
| 写入契约 | 发后即忘，失败被记录 | 同事务，失败使操作失败 |
| 排序来源 | 时间戳，重采样 | 写入者分配的序号 |
| 可变性 | 按设计可变（重处理、后端升级时丢字段） | 仅追加，理想情况下哈希链式 |
| 保留驱动 | 一次回归还能保持多久有趣（天） | 一项义务或一个争议时限（数月到数年） |
| 失败模式 | 你调试更慢 | 你无法回答那个问题 |

写入契约是最便宜却最容易搞错的东西。本平台同时持有两种立场，各自对其产物而言都是正确的。agent 可观测性写入是一个发后即忘的 HTTP POST（\`AgentClient.recordObservability\`），其失败被捕获并以 WARN 级别记录为"non-critical"：运行仍然计费并返回，审计行只是丢失了。而特性开关审计（\`V173__flag_flip_audit.sql\`）在其迁移头部声明了相反的契约：同事务、无 \`REQUIRES_NEW\`、无异步、无 \`AFTER_COMMIT\` 监听器（那会与一次 JVM 被杀竞态），并且如果审计插入抛出异常，开关就不会被翻转。

尽力而为这种选择的后果，是那种在你需要它之前都看起来没问题的失败模式：**轨迹的覆盖率变得与系统健康度相关**，于是它恰恰在你将被要求解释的那些事故期间变稀。

## 运行级 schema

每次运行一行。这是审计者最先读到的表头。

| 字段 | 类型 | 可空 | 基数 | 个人数据 | 为何存在 |
|---|---|---|---|---|---|
| \`run_id\` | uuid | 否 | 高 | 否 | 每一个子行的连接键。在**分派**时铸造，而非在 INSERT 时。 |
| \`trail_seq\` | bigint（专用序列） | 否 | 高 | 否 | 能挺过时钟偏移与同毫秒写入的排序。 |
| \`prev_row_hmac\` | bytea(32) | 是 | 高 | 否 | 篡改证据：覆盖自身内容加上上一行的 HMAC。 |
| \`tenant_id\`, \`organization_id\` | text / uuid | 否 | 中 | 间接 | 擦除与访问控制的作用域键。 |
| \`actor_subject_ref\` | text（假名令牌） | 是 | 高 | **是** | "谁发起的。"仅通过一份单独持有的映射才能解析到身份。 |
| \`parent_run_id\` | uuid | 是 | 高 | 否 | 哪次运行派生了这一次。 |
| \`caller_agent_id\` | uuid | 是 | 中 | 否 | 哪个 agent 派生了它。 |
| \`depth\` | int2 | 否 | 低 | 否 | 环检测与树排序。 |
| \`caller_tool_call_id\` | text | 是 | 高 | 否 | 父运行中派生该子运行的那个确切调用。 |
| \`trigger_source\` | enum | 否 | **低** | 否 | manual / chat / webhook / schedule / datasource / workflow / error。决定是否有人类为该运行的存在负责。 |
| \`started_at\`, \`ended_at\` | timestamptz | 否 / 是 | 高 | 否 | 两个时间戳，而不是一个加一段时长。 |
| \`status\` | enum | 否 | 低 | 否 | 你将被要求辩护的那个断言：这次运行是否成功。 |
| \`stop_reason\` | text（原始枚举字符串） | 是 | 低 | 否 | 为取证按原样存储。 |
| \`terminal_category\` | enum | 是 | 低 | 否 | 物化，而非在读取时推导。 |
| \`billed_provider\`, \`billed_model\` | text | 否 | 低 | 否 | 你被收费的对象。 |
| \`executed_provider\`, \`executed_model\` | text | 是 | 低 | 否 | 实际运行的对象。二者可以不同。 |
| \`model_snapshot\` | jsonb（\`_v\` 键控） | 是 | 中 | 否 | 在执行开始时冻结的价目表与模型配置。 |
| \`prompt_tokens\`, \`completion_tokens\`, \`cache_creation_tokens\`, \`cache_read_tokens\`, \`reasoning_tokens\` | int4 x5 | 否（默认 0） | 高 | 否 | 五个计数器，而不是一个总数：它们计价方式不同。 |
| \`cost_settled\` | numeric(15,4) | 是 | 高 | 否 | 实际收取的金额，在写入时物化。 |
| \`system_prompt_hash\` | bytea(32) | 是 | 高 | 否 | 引用，绝不存文本。 |
| \`build_sha\` | text(40) | 是 | 低 | 否 | 这次运行是否早于那个修复。 |
| \`config_snapshot\` | jsonb | 是 | 中 | 也许 | 生效中的策略，包括是否要求审批。 |
| \`approval_ref\` | uuid | **是** | 高 | 否 | NULL 意为"生效策略不要求审批"。 |
| \`iteration_count\`, \`tool_call_count\` | int4 | 否 | 高 | 否 | 无需读取其步骤即可了解运行的形状。 |

其中有十一项需要不止一句话。

**在分派时铸造 \`run_id\`。** 一个真实的 bug：MCP 侧的 task-claim 行在执行行存在之前就被写入，于是一个 Hibernate 生成的 id 让 \`task_id\` 悄然为 NULL。修复的做法是通过分派调用传入一个显式的执行 id，并把它用作主键（\`AgentObservabilityRequest.executionId\`，在代码中记为"stable correlation ID minted at dispatch"）。

**子 agent 调用树需要四个字段，而不是一个：**父运行、调用方 agent、深度，以及父运行中那个确切的工具调用。丢掉其中任何一个，一次多 agent 运行就会读成一堆无法排序的扁平堆。

**两个时间戳，而不是一个加一段时长。** 时长无法与外部事件时间线对账。这也是 AI Act 本身唯一命名的字段形状：Art. 12(3)(a) 要求"recording of the period of each use of the system (start date and time and end date and time of each use)"。

**计费的与执行的模型可以不同。** 一个路由层可以把计费的 \`(provider, model)\` 对发往一个不同的执行目标，同时在响应上保留计费身份（\`V365__create_model_execution_links.sql\`）。一份只记录其中一个的轨迹，对是什么产生了输出这件事是错的。

**\`model_snapshot\`** 在执行开始时冻结价目表：

\`\`\`json
{
  "_v": 1,
  "provider": "anthropic",
  "model_id": "claude-opus-4-8",
  "price_input": 5.0,
  "price_output": 25.0,
  "credits_input": 1.0,
  "credits_output": 5.0,
  "canonical_id": "anthropic/claude-opus-4-8",
  "bundle_version": 41,
  "markup": 1.2,
  "captured_at": "2026-07-22T09:14:03Z"
}
\`\`\`

大约 260 bytes，在 10k runs/day 下约 905 MB/year，块存储成本约每年一美元。它的存在是为了让成本在运行中途的模型弃用和回溯性的价格改动中存活，而它正是工程师最先砍掉、又最痛悔的字段。

**\`cost_settled\` 在写入时物化。** 在读取时用 token 乘以价格重新计算，是 \`model_snapshot\` 所支持的*后备方案*，而非记录本身；任何日后的分歧本身就是一项发现。

**\`terminal_category\` 即使可从 \`stop_reason\` 推导，也仍以物化形式存储**，目前是由生成的契约代码推导（\`AgentStopReason.valueOfOrError(x).terminal()\`）。代码生成会变；一份七年后仍可读的轨迹不能依赖这个月的构建，否则旧行会悄悄地把自己重新分类。

**\`build_sha\`**（~40 bytes）是最常缺失、也最常需要的字段。陷阱：\`.git\` 通常不在 Docker 构建上下文里，所以运行中的版本会报告一个静态占位符，除非把提交作为构建参数传入。

**绝不要按每次运行存储 system prompt 文本。** 在 10k runs/day 下，一个 6 KB 的 system prompt 就是 20.89 GB/year 的纯重复，而本平台每次运行最多存储它三次（\`agent_executions.system_prompt TEXT\` 列、\`agent_config_snapshot\` JSONB 中的一份副本，以及在 \`agent_execution_messages\` 中作为一条 SYSTEM 角色行再存一次），所以 20.89 GB/year 是下限，而非总量。每个不同的 prompt 每个版本只存一次，用哈希引用。不过它并不是最大的可避免开销项：重复的 tool-result 存储（在配套的保留文章中量化）是 83.55 GB/year，大四倍。这两项，83.55 GB/year 的工具结果加上 20.89 GB/year 的 system prompt，是这个模型里仅有的超过 10 GB/year 的可避免项。

**\`trail_seq\` 来自一个专用序列，而不是 \`created_at\`。** 它能挺过时钟偏移、恢复到另一个时区，以及同一毫秒内写入的两行。间隙是可接受的，并应作为间隙记录在案；被断言的属性是单调性。\`V169__trigger_lifecycle_invariants.sql\` 展示了这个模式：它按 \`(trigger_id, trigger_type, seq DESC)\` 对历史排序，并仅为时间窗口的运维查询保留一个 \`created_at DESC\` 索引。

**\`prev_row_hmac\` 是一条界线**，区隔一份可观测性日志与一份审计轨迹。每一行的 HMAC 覆盖它自己的内容加上上一行的内容，所以一次悄然的编辑或删除会打断这条链。本平台的 \`V195__create_organization_audit_event.sql\` 头部把它列为在那个 MVP 中被刻意省略的项，与之并列的还有分布式锁下的保留清除、一个 WORM 镜像，以及仅追加的角色分离。那份清单同时也是一份成熟度检查表。

## 步骤级 schema

每个 LLM 轮次、工具调用、决策或信号一行。步骤行数量约为运行行的 26 比 1，并承载全部有效载荷，所以它们的保留与个人数据画像完全不同。

| 字段 | 类型 | 可空 | 基数 | 个人数据 | 为何存在 |
|---|---|---|---|---|---|
| \`run_id\` | uuid | 否 | 高 | 否 | 父连接键。 |
| \`tenant_id\`, \`organization_id\` | text / uuid | 否 | 中 | 间接 | 在**每一个**子行上，用于组织范围的擦除。 |
| \`step_seq\` | int4（写入者分配） | 否 | 高 | 否 | 确定性的顺序。绝不从 \`created_at\` 推导。 |
| \`iteration_seq\` | int4（写入者分配） | 否 | 中 | 否 | 这属于哪个 LLM 轮次。 |
| \`parallel_index\` | int2 | **是** | 低 | 否 | NULL 意为顺序执行。区分一个并发批次与一条因果链。 |
| \`step_kind\` | enum | 否 | 低 | 否 | llm_turn / tool_call / decision / signal / message。 |
| \`tool_name\` | text | 是 | **低** | 否 | 用于"这个 agent 实际做什么"的 GROUP BY。 |
| \`tool_call_id\` | text | 是 | 高 | 否 | 在重试与重排间把请求与结果关联起来。 |
| \`args_digest\` | bytea(32) | 是 | 高 | 否* | 在不保留有效载荷的情况下，证明或反证一个已产生的载荷。 |
| \`result_digest\` | bytea(32) | 是 | 高 | 否* | 同上，用于结果。 |
| \`content_length\` | int4 | 是 | 高 | 否 | 有效载荷曾经**有**多大，在它消失后仍保留。 |
| \`payload_ref\` | uuid | 是 | 高 | 仅指针 | 超过内联阈值而卸载出去的 blob。 |
| \`content\` | text | 是 | 高 | **是** | 内联的有效载荷，在短时钟上。 |
| \`error_code\` | enum | 是 | 低 | 否 | 机器可读的失败类别。全窗口。 |
| \`error_message\` | text | 是 | 高 | **是** | 自由文本。有效载荷时钟。 |
| \`branch_taken\` | text（端口标签） | 是 | 低 | 否 | 运行走了哪条出边。 |
| \`skip_reason\` | text | 是 | 低 | 否 | 一个节点为何**没有**运行。 |
| \`skip_source_node\` | text | 是 | 中 | 否 | 哪个上游决策跳过了它。 |
| \`redaction_applied\` | int2（位掩码） | 否 | 低 | 否 | 哪些脱敏规则触发了。 |
| \`prompt_tokens\`, \`completion_tokens\`, ... | int4 | **是** | 高 | 否 | 仅在非零时写入，所以 NULL 保有其含义。 |
| \`duration_ms\` | int8 | 是 | 高 | 否 | 把一个运行级超时归因到消耗掉预算的那一步。 |

\\\\* 只有当有效载荷空间不可枚举时，一个摘要才不是个人数据（见下方的告诫）。

那五个 token 计数器在运行表头上是 NOT NULL default 0（一次运行总有一个总数），但在步骤行上可空，其中 NULL 意为"不适用"（一个工具调用行没有 token），而非零。带着这条规则把步骤对着表头相加，否则两者会不一致。

**\`parallel_index\` 花费四字节**，却防住了最糟的轨迹失败：把一个并行批次重建成一条因果链，这比一个间隙更糟，因为它自信地错着。

**\`args_digest\` 和 \`result_digest\` 是保留设计的枢轴。** 每个摘要 32 B；那 6 个工具调用行各带两个，那 14 个消息行各带一个，所以每次运行 832 bytes，在 10k runs/day 下 2.83 GB/year。把摘要保留整个义务窗口，把有效载荷放在短时钟上：当有人拿出一份文件并声称 agent 看过它时，摘要在零有效载荷保留的情况下证明或反证它。

那条告诫，直说：**对于一个小的可枚举输入空间（一个邮编、一个出生日期），摘要是可再识别的**，必须用一份单独持有的密钥加盐。规则是"绝不发布一个低熵字段的未加盐摘要"，而不是"摘要非个人数据"。[EDPB 假名化指南](https://www.edpb.europa.eu/system/files/2025-01/edpb_guidelines_202501_pseudonymisation_en.pdf)认为，缺乏域分离与访问控制的简单哈希是不充分的（January 2025 磋商草案）。

**\`content_length\` 在决定内联、卸载还是截断之前无条件设置**，这正是告诉未来读者截断发生过、以及他们没看到多少内容的东西（\`AgentObservabilityService\`，\`CONTENT_INLINE_THRESHOLD = 8192\`）：

\`\`\`
length = content.length()          # set FIRST, always
if length > 8192:
    id = storage.saveText(content) # payload_ref
    content = content[:500] + "...[truncated]"
else:
    keep inline
# if the offload throws: fall back to an inline prefix
# with NO storage id, which MUST be distinguishable
# from a successful offload.
\`\`\`

**把 \`error_code\` 与 \`error_message\` 分开。** 自由文本消息不可查询、跨库升级不稳定，并且惯常回显导致失败的输入，这使它们在看起来像诊断信息的同时，成为轨迹中风险最高的个人数据字段。代码保留整个窗口；消息走有效载荷时钟。

**\`branch_taken\` 让轨迹能在纸面上重放**，而不必重新执行；在一个工作流引擎里，端口是每种节点类型下一个封闭的低基数集合（\`if\` / \`else\` / \`elseif_N\`、\`case_N\` / \`default\`、\`body\` / \`iterate\` / \`exit\`、\`branch_N\`）。也要记录一个节点为何**没有**运行：\`skip_reason\` 加 \`skip_source_node\` 让这个否定成为一等事实，从而让一个被跳过的分支与一个从未到达的分支可区分。

**\`redaction_applied\` 是两个字节**，区隔一份朴素轨迹会混为一谈的三种状态：有效载荷干净、有效载荷已脱敏，或脱敏器被禁用。没有它，一份看起来干净的轨迹在证据上一文不值。本平台的 \`ToolCallRedactor\` 是两层的（一个密钥字段名正则，加上一个把整个参数体清空的凭据工具允许列表），且不持久化任何标记来记录哪一层触发了；这正是这个字段填补的缺口。

## 审批记录是它自己的一行，而其最难的字段是人类看到了什么

人在环中是 AI Act 为其所覆盖的系统所列举的唯一一件事，也是 OTel 没有对应属性的唯一一件事。Art. 12(3)(d) 要求，对于 Annex III point 1(a) 系统，"the identification of the natural persons involved in the verification of the results"，即 Art. 14(5) 所指的那些人。

一份可用的审批记录（本平台的 \`orchestrator.workflow_signal_waits\`）：

\`\`\`
signal_type, signal_config jsonb, status, resolution,
resolution_data jsonb, approval_context text,
expires_at, created_at, claimed_at, claimed_by,
resolved_at, resolved_by,
UNIQUE (run_id, node_id, item_id, epoch)

signal_config = { type, approverRoles, requiredApprovals,
                  timeoutMs, receivedApprovals, delegation,
                  continuationMode }
\`\`\`

**没人记录的那个字段是审批者实际看到了什么。** \`approval_context\` 是节点的上下文模板，针对**在暂停那一刻冻结的**执行上下文渲染而成，随信号一起持久化，然后原样重新发射到已解决的节点输出里，从而让它挺过从等待到解决的转换（迁移 \`V373\`，它向 signal-wait 表添加了 \`approval_context\`）。

**运行行上的 \`approval_ref\` 可空，而 NULL 必须意为"生效策略不要求审批"**，这是一个不同于"审批状态未知"的事实。这要求策略版本能从 \`config_snapshot\` 中恢复。

**身份默认值必须与真实身份在视觉上可区分。** 这里 \`resolved_by\` 在节点输出中为 null 时回退为字面量 \`"system"\`，在上游用户头缺失时回退为 \`"api"\`。可以，只要绝不会有人类被命名为 \`api\`。

**给一个身份列定尺寸是一件审计上的事。** \`resolved_by\` 曾是 \`VARCHAR(100)\`，直到形如 \`b:org:user\`（~120 chars）的联邦标识符将其溢出，回滚了 resolve 事务，让审批永远卡在 \`CLAIMED\`，与真正待处理的审批无从区分（\`V191__signal_waits_widen_resolved_by.sql\`）。

**委派的审批需要它们自己的投递台账。** \`orchestrator.approval_channel_deliveries\`：一个一次性的回调令牌（\`VARCHAR(64) UNIQUE\`）、状态（\`PENDING\`、\`SENT\`、\`FAILED\`、\`RESOLVED\`、\`CANCELLED\`）、实际发出的消息文本、一份允许用户的允许列表，以及作为重放守卫的 \`UNIQUE (signal_wait_id, channel)\`。身份随后是一个带命名空间的字符串，例如 \`telegram:<fromId>\`。

**记录下来的意图不是被强制执行的控制，轨迹也不应暗示相反。** 这里 \`approverRoles\` 被记录在信号配置中并展示给审批者，但应用内的 resolve 端点只强制运行作用域，而不强制角色成员资格。如果你的轨迹记录了一个它没有检查的角色，就在该字段的文档里说清楚。
`;

export default content;
