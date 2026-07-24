// ai-agent-audit-log-retention - zh
// Translated from the English body; structure identical to it. Legal scope
// statements and every "at least 6 months"/"out of scope"/"not legal advice" are
// load-bearing and must not be strengthened or softened. Fenced code stays fenced.
const content = `After a careful line-by-line comparison against the source (5 level-2 headings, 1 level-3 heading, 8 fence markers, 31 table lines, 22 external links, all numbers/dates/field names/URLs, and every legal scope statement all verified), the translation is faithful. The only change needed is a small fluency fix in the OpenTelemetry section ("移动带来的假象" reads ambiguously; the referent is the repo migration). Corrected body below.

一篇配套文章发布了本文所定价的运行级和步骤级字段模式：下文表格中引用的字段名、类型和基数类别都在那篇文章里定义。本文回答该模式留下的三个问题。这条轨迹实际上要花费多少字节？每个字段必须保留多久？以及它是否在法律上适用于你，而对大多数读者来说并不适用。

全文引用的参考实现就是本博客自己的平台：真实的列名、真实的迁移、真实的 bug。

## 算术推演，让分层是推导出来的而非断言出来的

下文的一切都是一个**模型**，而非一次测量。输入条件已列明，你可以用自己的数字重新运行。行大小是分析得出的，源自 DDL 列类型加上有据可查的 Postgres 开销；一旦把 fillfactor、空闲空间和膨胀计入，真实表大约会大 10-25%，所以请把下文每个推导出的数字都读作“生产环境中 +10 到 25%”（模型中七年全量捕获的固定值 1.68 TB，在该区间上限约为 2.1 TB）。

\`\`\`
Volume:  10,000 runs/day, 6 steps/run
Rows:    27/run = 1 run header + 6 iterations
                + 6 tool calls + 14 messages
Payload: 1500-token system prompt, 200-token user msg,
         250-token completions, 150 B tool arguments,
         4 KB mean tool result, 4 bytes/token
PG overhead/row: 23 B heap tuple header, MAXALIGNed to 24
                 + 4 B line pointer
                 + 8 B assumed null bitmap (1 bit/column,
                   present only when the row has NULLs;
                   8 B covers up to 64 columns) = 36 B
                 + ~16 B per btree index entry
\`\`\`

模型其余部分据以缩放的仅元数据数字是 9.05 KB/run，推导如下：

\`\`\`
Worked row sizes (metadata only):
Run header (1 row):
  ~300 B column data (uuids, 3 timestamptz, 5 int4 token
   counters, 3 bytea(32) hashes, build_sha, enums, numerics)
  + 36 B tuple overhead + ~48 B (3 btree entries) = ~384 B
Step row (avg over 26):
  ~180 B column data + 36 B overhead + ~80 B index entries
  = ~335 B
Per run: 384 + 26 x 335 = ~9.05 KB
\`\`\`

| 捕获级别 | 字节/run | MB/day @10k runs | GB/year | 七年累计 GB | 压缩后 GB/year |
|---|---|---|---|---|---|
| 仅元数据 | 9.05 KB | 88.38 | 31.50 | 220.51 | 31.50（在 PG 中未压缩；归档效果好） |
| 元数据 + 摘要（~832 B/run） | 9.86 KB | 96.29 | 34.33 | 240.31 | 34.33 |
| 全量捕获 | 70.43 KB | 687.78 | 245.16 | 1,716（1.68 TB） | 92.6-117 |

全量捕获是仅元数据的 7.8x。压缩假定对超过 Postgres 约 2 kB（2048 字节）TOAST 阈值的负载有 2.5-3.5x 的压缩比，这是一个典型的已发布区间，而非对本语料库的测量，因此压缩后的全量捕获数字跨度为 92.6 到 117 GB/year，取决于你落在该区间的哪个位置。

一个输入主导着结果：

| 平均工具结果 | KB/run（全量捕获） | GB/year @10k runs/day | 生活在这里的 Agent 形态 |
|---|---|---|---|
| 1 KB | 34.43 | 119.84 | 分类、路由、短 API 查询 |
| 4 KB | 70.43 | 245.16 | 混合工具使用，即上面的模型 |
| 8 KB | 118.43 | 412.24 | 文档起草、多记录 CRUD |
| 20 KB | 262.43 | 913.50 | 搜索、文件读取、SQL 密集型 Agent |

在平均工具结果为 4 KB 时，提示词和补全占负载的 20%（61.38 KB 中的 12.8 KB），在 20 KB 时降至约 5%（253.38 KB 中的 12.8 KB），所以工具结果才是分层见效的地方。**如果你只分层一样东西，就分层工具结果。**

现在来看驱动整节的反转。245 GB/year 大约是 **$235/year** 的 gp3 块存储、S3 Standard 上的 **$68/year**、Glacier Instant Retrieval 上的 **$12/year**；仅元数据约为 $30/year。（列出的是 us-east-1 数量级数字，不含请求和检索费用；冷层假定近乎零读取量。）**没有人会为了省 $200 而削减自己的轨迹。**

这个美元数字掩盖的是真实成本：每年 **9855 万行**（七年 6.8985 亿行）的擦除面、索引维护和恢复时间，加上每一个保留下来的提示词和工具结果字节都是责任。围绕影响半径和行数来设计分层。

在 1M runs/day 时，运营天花板远在存储账单之前就开始咬人：约 54M 索引插入/天、每年 98.6 亿行、每年 23.94 TB 的全量捕获，以及以 50 MB/s 逻辑恢复一年数据约需 140 小时。骨架层才是让轨迹保持*可恢复*而不只是负担得起的东西。

一个免费的节省，靠读模式而非读代码发现：**工具结果经常被持久化两次**，一次作为工具调用行的 content，再一次作为对应的 tool-role 消息行的 content。把负载只存一次，让消息行携带相同的 \`payload_ref\`，负载就从每次运行的 61.38 KB 降到 37.38 KB，从 245.16 GB/year 降到 161.61 GB/year。任何同时拥有工具调用表和消息表的轨迹都有这种形态。（模式层面的观察是可靠的；生产中确切的重叠率未经测量。）

## 保留分层，每一层都由它所支撑的决策来证明其正当性

| 层 | 内容 | 窗口 | GB/year | 它回答的问题 | 是否采样或降级？ |
|---|---|---|---|---|---|
| 0 骨架 | 去掉所有文本的运行头；步骤元数据（\`step_seq\`、\`tool_name\`、\`branch_taken\`、status、\`stop_reason\`、时长、token 计数、\`content_length\`、所有摘要） | 完整义务窗口（建模为 7 年） | 31.50 | 这次运行是否发生、何时、谁触发、做了什么、往哪个分支走、花费多少 | **从不** |
| 1 摘要与代码 | \`args_digest\`、\`result_digest\`、\`error_code\`、\`redaction_applied\`、\`model_snapshot\` | 12-24 个月 | 34.33 | 证明或推翻 Agent 见过某个已生成的文档；按当时价格重新核算一次有争议的运行 | **从不** |
| 2 工具参数与结果 | 工具步骤的 \`content\`、\`payload_ref\` | 热存 30-90 天，之后采样 | 约负载字节的 80% | 调试实时回归；回应客户投诉 | 是，热窗口之后 |
| 3 提示词与补全 | 消息 content | 30 天，**外加 100% 的失败或触发护栏的运行，无论年龄** | 见下文 | 重建一次有争议决策的推理 | 仅非均匀地采样 |
| 4 提示词模板 | 系统提示词、按版本的提示词文本 | 永久（千字节级） | ~0 | 运行了哪个提示词版本 | 从不按每次运行的时钟计 |

七年的第 0 层是 220.51 GB，在 Glacier Instant Retrieval 上约为 **$10.60/year**（220.51 GB x $0.004/GB-month x 12）。这在保留零字节个人数据的同时回答了大多数审计员的问题。

第 3 层的采样规则是最值得争论的一条，而这个旋钮只会触及第 2 层和第 3 层（不变量 1：审计记录从不采样）。在假定 8% 失败率下，保留所有失败加 5% 的成功，会保留 12.6% 的运行（0.08 + 0.92 x 0.05 = 0.126）。仅应用于负载层（全量捕获减去 31.50 的骨架层和 2.83 的摘要层，即 210.83 GB/year），这会保留 26.56 GB/year 的负载；在第 0 层和第 1 层保持 100% 的情况下，常驻全细节从 245.16 降到约 **60.9 GB/year**（31.50 + 2.83 + 26.56），同时保留每一个真正会有人来问的运行。均匀采样是在为没人调查的运行做优化。

组合方案，按层列出：

\`\`\`
30 days full capture:   20.15 GB gp3           $19.34
365 days digests:       34.33 GB S3 Standard    $9.47
7 years skeleton:      220.51 GB Glacier IR    $10.58
resident total:        274.99 GB             ~ $39/year
\`\`\`

这是 274.99 GB 常驻，相对于持有七年的固定全量捕获 1.68 TB，减少了 6.2x，大约 $39/year 相对于固定 gp3 的 $1,647/year。真正重要的节省不是钱：**任何删除请求所涉及的个人数据负载只有 30 天在范围内，而不是七年。**

热加冷是监管机构已经在编纂的形态。PCI DSS 4.0 要求 10.5.1 要求 12 个月，其中最近的 3 个月立即可用；SEC Rule 17a-4 要求六年，其中头两年易于访问。（两者均可按所述确认。）

需要点名的反模式：被广泛传播的**渐进降级阶梯**，它在第一年后丢弃提示词和补全内容，从第三年起只保留元数据。它恰恰在审计员需要的那段窗口里降级内容，却让公司得以宣称“七年审计日志”，同时不保留任何能解释单个决策的东西。

## 你实际上欠什么，以及为什么很可能什么都不欠

| 工具 | 条款 / 控制项 | 约束谁 | 它实际要求什么 | 保留期 | 是否规定字段？ |
|---|---|---|---|---|---|
| EU AI Act | [Art. 12(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-12) | 高风险**系统**（设计要求） | 系统“应在技术上允许在系统生命周期内自动记录事件（日志）” | 不适用 | **否** |
| EU AI Act | Art. 12(2)(a)-(c) | 同上 | 仅限于*目的*：Art. 79(1) 下的风险或重大修改；Art. 72 下的上市后监测；Art. 26(5) 下的运行监测 | 不适用 | **否** |
| EU AI Act | Art. 12(3)(a)-(d) | **仅 Annex III point 1(a)**（远程生物识别） | 每次使用的时段；所核对的参考数据库；导致匹配的搜索所用输入数据；核实结果人员的身份 | 不适用 | **是，唯一之处** |
| EU AI Act | [Art. 19(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-19) | **提供者** | 保留 Art. 12(1) 日志“在这些日志处于其控制之下的范围内” | **至少 6 个月** | 否 |
| EU AI Act | [Art. 26(6)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-26) | **部署者** | 同一义务、同一限定语、独立时钟 | **至少 6 个月** | 否 |
| EU AI Act | [Art. 18(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-18) | 提供者 | 技术文档、QMS 文档、公告机构决定、EU 合规声明 | 投放市场或投入使用后 **10 年** | 不适用 |
| EU AI Act | [Art. 86](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-86) | 部署者 | “关于 AI 系统在决策程序中所起作用以及所作决定主要要素的清晰而有意义的解释” | 不适用 | **否** |
| ISO/IEC 42001 | Annex A 事件日志控制项 | 自愿 | 事件日志加上表明日志记录正在运行的监测记录 | 未规定 | **否** |
| NIST AI RMF | MEASURE 2.8、MANAGE 2.4、MANAGE 4.3 | 自愿 | 检测并维护历史与审计日志；为取证、监管和法律审查保存材料；维护事件与系统变更数据库 | 未规定 | **否** |
| SOC 2 | 2017 TSC（2022 修订关注点） | 合同 | 应用于你的 Agent 的通用控制环境证据 | 基于准则，无固定期限 | **否** |
| HIPAA | [45 CFR 164.316(b)(2)(i)](https://www.govinfo.gov/content/pkg/CFR-2023-title45-vol2/xml/CFR-2023-title45-vol2-sec164-316.xml) | 受保护实体 | 保留所需文档 | **6 年** | 否 |

大多数摘要弄错的三个区分。

**Art. 12(1) 是对系统的设计要求。Art. 19(1) 对提供者施加六个月的下限。Art. 26(6) 对部署者施加另一个平行的六个月下限。** 六个月由两个不同的当事方各欠一次，不是一个共享的时钟，两者都带着同样的限定语，“在这些日志处于其控制之下的范围内”。

**六个月是日志下限；十年是文档下限。** Art. 18(1) 和 Art. 19(1) 是两套不同的制度，经常被混为一谈。

**真正强制要求逐决策可解释性的义务是 Art. 86，不是 Art. 12。** 一个受到部署者基于某个 Annex III 高风险系统输出（point 2 除外）所作决定影响的人，若该决定产生法律效果或以类似方式对其产生重大影响，且其认为对自身健康、安全或基本权利有不利影响，就有权获得对 AI 系统作用及决定主要要素的解释。Art. 86(3) 使其从属于其他欧盟法律。

**现在给出对大多数读者的诚实答案：完全不在 Art. 12/19/26(6) 范围内。** 高风险意味着 Art. 6(1)（需第三方合规评估的 Annex I 产品的安全组件）或 Art. 6(2)（[Annex III](https://ai-act-service-desk.ec.europa.eu/en/ai-act/annex-3) 的八个领域）。一个编码助手、一个内部研究或支持 Agent、一个文档起草 Agent，都不属于其中任何一个。

抓住人们的那个“除非”是 Annex III **point 4**（招聘与甄选、定向招聘广告、筛选申请、评估候选人、关于工作条件的决定、晋升、解雇、基于行为或特质的任务分配、绩效监控）和 **point 5**（其四个子项的部分列举，最常抓住建设者的两项：(b) 信用度评估和信用评分，欺诈检测除外，以及 (c) 人寿和健康保险的风险评估与定价；另外两项，(a) 公共当局对基本公共援助福利与服务（包括医疗保健）资格的评估，以及 (d) 紧急呼叫分流与调度，抓住的是政务科技和福利相关的 Agent）。

即便是一个 Annex III 系统也可通过 [Art. 6(3)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-6) 减免逃脱（狭窄的程序性任务；改进先前已完成的人类活动；在不取代先前人类评估的情况下检测模式；一项准备性任务），但**如果它对自然人进行画像则绝不可以**。而 Art. 6(4) 使这个逃生口自行生成文书工作：在投放市场前记录该评估，加上 Art. 49(2) 下的登记义务。

给建设者的两个陷阱。纯粹为内部使用而构建一个 Agent 并不使你仅仅成为部署者：[Art. 3(11)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-3) 将投入使用定义为为首次使用“或为自用”而供应，因此一个内部高风险系统可能同时欠下 Art. 19、Art. 26(6) 和 Art. 18。[Art. 25(1)(c)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-25) 对任何修改通用模型预期用途以致系统变为高风险的人施加同样的效果。

日志义务的处罚敞口是中间档，而非头条：[Art. 99(4)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-99) 为至多 EUR 15,000,000 或全球年营业额的 3%，以较高者为准。它涵盖 Arts. 16、22、23、24、26、31、33、34 和 50；Art. 19 本身未被列出，因此提供者的日志保存违规经由 Art. 16(e) 触及，后者引入了 Art. 19 义务，而部署者的则直接是 Art. 26。35 million / 7% 那一档留给 Art. 5 禁止性做法。

**时间表已经移动。** Digital Omnibus on AI 将高风险适用日期推迟至独立（Annex III）高风险系统的 **2 December 2027** 和嵌入受监管产品的高风险 AI 的 **2 August 2028**，据 [Council of the EU](https://www.eeas.europa.eu/delegations/chile/artificial-intelligence-council-gives-final-green-light-simplify-and-streamline-rules_en)。截至 2026 年 7 月下旬的程序状态：EP 全体会议批准 16 June 2026，Council 通过 29 June 2026，签署 8 July 2026，等待 Official Journal 发布（[EP Legislative Train](https://www.europarl.europa.eu/legislative-train/package-digital-package/file-digital-omnibus-on-ai)）。任何仍为高风险引用 2 August 2026 的文章都已过时。Omnibus 在其议定文本中未修改 Articles 12、19 或 26(6)，正如对其的每一份已发布分析所报告的；六个月下限不变。待 OJ 文本发布后再据其确认。

遗留系统可能完全逃脱：[Art. 111(2)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-111) 仅在切换前投放市场的高风险系统随后在其设计上受到重大变更时才将本条例适用于它们；公共当局部署者有直到 2 August 2030 的期限。

有两项义务无论风险档次如何都会咬人：[Art. 4](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-4)（AI 素养，自 2 February 2025 起适用于提供者和部署者）和 [Art. 50(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-50)（提供者必须设计系统使自然人被告知其正在与 AI 交互，除非显而易见），后者自 2 August 2026 起适用，即本文发表后十天。Art. 50(2) 内容标记对已在市场上的系统有到 2 December 2026 的宽限期。Omnibus 将 Art. 4 从确保充分的素养水平软化为支持员工中该素养的发展；2 February 2025 这一日期不变，且在 OJ 发布之前，原始措辞仍是有约束力的内容。

而那些将规定*如何*满足 Art. 12 的标准尚不存在：[CEN-CENELEC JTC 21](https://www.cencenelec.eu/news-events/news/2025/brief-news/2025-10-23-ai-standardization/) 仍在制定 Chapter III Section 2 标准，2025 年 10 月通过的加速措施目标是在 2026 年第四季度左右可用。在那之前，它是一项没有技术规范支撑的法定义务。

自愿框架也不给你任何模式。[ISO/IEC 42001](https://www.iso.org/standard/81230.html) 是自愿的（ISO 不认证组织；受认可的机构才认证），其 Annex A 控制项 A.6.2.8“AI 系统事件日志记录”既未规定保留时长也未规定字段清单。[NIST AI RMF](https://www.nist.gov/itl/ai-risk-management-framework) 明确是自愿且行为性的。SOC 2 使用带 2022 修订关注点的 2017 Trust Services Criteria，尚未发布任何 AI 专属准则，因此审计员测试的是应用于你的 Agent 的通用控制环境证据。

如果你涉及招聘或后果性决定，Colorado 值得写一行。SB 26-189，据 [bill page](https://leg.colorado.gov/bills/sb26-189)，于 14 May 2026 签署，1 January 2027 生效；它废止并重新颁布了 2024 Colorado AI Act。范围是用于后果性决定（教育、就业、住房、金融/借贷、保险、医疗保健、基本政府服务）的自动化决策技术。开发者和部署者必须保留合规记录至少三年，对部署者而言从后果性决定之日起算。

**反表演的结论。** 如果你不在范围内，就为你实际会被问到的问题构建轨迹：一次客户争议、一次事件审查、一次账单争议、一次安全调查。按最长的合理未来义务来设定骨架层大小，因为它每年只花 31.50 GB。然后让六个月成为你顺带跨过的一条下限，而非一项工作计划。这不是法律建议，上述任何保留制度都不应被压平成一个适用于你的单一数字。

## 个人数据：你保留多年的轨迹与你明天收到的删除请求

**一个假名化的行为者引用并不能把轨迹移出 GDPR 范围。** Recital 26 将可借助额外信息归属于某人的数据视为个人数据。存储一个仅通过单独控制的映射表才能解析为身份的 token，就不要声称该轨迹是匿名的。

**六个月的下限在同一个句子里就有一个上限。** Art. 19(1) 和 Art. 26(6) 都以“除非适用的欧盟或国家法律另有规定，特别是关于个人数据保护的欧盟法律”结尾。永远保留一切不是合规答案，它是另一项违规。

**设计上的答案是摘要枢轴：** 长期层保存哈希、代码、计数和分类，没有负载。这正是让七年骨架站得住脚而非成为七年责任的东西。

**把 \`tenant_id\` 和 \`organization_id\` 放在每一个子行上，而不只是父行。** 擦除以按表的组织范围 DELETE 运行；仅携带 \`execution_id\` 的行需要 join，而任何父行已消失的行会作为一个仍持有个人数据的不可达孤儿存活下来。本平台的 \`WorkspaceDataPurger\` 对 \`agent_execution_tool_calls\` 发出一个以 \`organization_id\` 为键的组织范围 DELETE（及等价物），这之所以有效，只是因为 \`V210\` 向所有五个 agent 运行时表添加了该列并回填了其中四个（\`agent_tasks\` 行按设计保持 NULL，是一个个人范围）。

**把轨迹拆分为一个可擦除的操作层和一个不可擦除的账本层**，并让删除只取走前者。参考实现删除 31 个声明的组织范围表（\`PURGED_ORG_SCOPED_TABLES\`）加上它直接命中的 agent 执行子表（messages、tool calls、iterations），同时从不触碰 \`auth.credit_ledger\`、\`auth.usage_cycle\`、\`auth.credit_reconciliation_log\` 或 \`auth.organization_audit_event\`，并把组织行保留为墓碑，以便账本引用保持有效。一个覆盖率测试断言每条语句的组织范围性以及保留表的不被删除。诚实的局限：存活的账本仍然证明某主体的运行存在过以及它们花费了多少，因此只有当账本不携带负载且仅含假名标识符时，这才满足最小化。

**擦除却没有擦除。** 当大型负载被卸载到对象存储而行保留一个指针时，删除该行会**孤立那个 blob**。个人数据在删除请求中幸存下来，无引用，因而对你所持内容的任何后续审计都不可见。上面的 purger 在它自己的 javadoc 中确切地记录了这个孤儿：它删除 \`storage.storage\` 行但不删除底层的 S3/MinIO 对象。修复：让负载存储成为删除目标、行成为指针，并按计划对账孤儿。

**决定去标识化是在写时还是读时发生，并记录是哪一种。** 一个只在把行呈现给审阅者时才运行的去标识器，会把原始凭据留在存储的工具参数里（此处的当前状态：\`ToolCallRedactor\` 是读路径过滤器）。一个写时去标识器会摧毁你可能需要的证据。无论你选哪个，\`redaction_applied\` 才是让这个选择可审计的东西。

**值得实现的未解决模式：** 给已擦除的内容立墓碑同时保留其摘要，以便防篡改链在一次擦除后仍然存活，且后来的读者仍能分辨出曾经有过某物、它有多大、以及它是在一项权利请求下被移除而非丢失。

## 要设计规避的两个失败，以及关于 OpenTelemetry 该怎么办

**你无法追溯延长的保留期。** 你发现窗口比清理定时任务更长的那一天，数据已经没了。这里有一个团队把生命周期审计日志从 30 天提到 365 天，之后首次清理时撞上了 12x 的积压，而那还是*幸运*的方向。第一天就把骨架层设为最长的合理义务；每年 31.50 GB，它是系统里最便宜的保险。（相关：一条记录着“30d default”的保留注释，而该服务的 \`@Value\` 默认值却是 365，这就是记录的保留期与配置的保留期悄然分歧的方式。）

**让轨迹变得不可用而非错误的查询路径失误。** 明细行不是查询路径：把低基数维度预聚合为以 \`(tenant, date, provider, model)\` 和 \`(tenant, tool_name)\` 为键的汇总表。Postgres 不会自动为外键建索引：这里有一个 18k 行、39 MB 的工具调用表，其唯一索引是主键，在每次聚合读取时都做全表扫描，直到 \`V341\` 在 \`execution_id\` 上添加了一个 \`CONCURRENTLY\` btree。而对 MB 级负载行的不分页读取是一种 OOM 形态：给页面设上限（100 是一个合理的硬上限），并返回 \`total\` / \`shown\` / \`truncated\`，以便当较旧的行被丢弃时读者被告知，而不是悄悄地看到一条不完整的轨迹。

由模式表格得出的基数规则：**低基数字段**（\`status\`、\`stop_reason\`、\`provider\`、\`model\`、\`tool_name\`、\`trigger_source\`、\`branch_taken\`）是每个问题据以分组的东西，属于汇总表；**高基数字段**（\`run_id\`、\`tool_call_id\`、摘要）是需要 btree 索引的 join 键，且绝不能进入汇总键。

### OpenTelemetry 的裁决

**先别把审计模式绑定到它上面。** 零个 \`gen_ai.*\` 属性是 Stable（活动注册表中 99 个 Development、0 个 Stable），[GenAI semconv repo](https://github.com/open-telemetry/semantic-conventions-genai) 没有 release 也没有 tag，而且这些约定已移出主 semantic-conventions 仓库，后者现在把每个 \`gen_ai.*\` 属性在[遗留注册表页面](https://opentelemetry.io/docs/specs/semconv/registry/attributes/gen-ai/)上呈现为“Deprecated”，这是这次迁移造成的假象。一个双向的假信号。

重命名已经把模式弄坏过一次：

\`\`\`
gen_ai.system              -> gen_ai.provider.name (now absent)
gen_ai.usage.prompt_tokens -> gen_ai.usage.input_tokens
gen_ai.usage.completion_tokens -> gen_ai.usage.output_tokens
gen_ai.prompt / gen_ai.completion
   -> gen_ai.input.messages / gen_ai.output.messages
\`\`\`

OTel **没有属性**表示一次人工审批、一个行为者或主体身份、一项策略或护栏决定、一个保留类别、或货币成本（仅 token 计数，没有 \`gen_ai.cost.*\`）。这些恰恰是承载审计的字段，这就是为什么轨迹是你的表而非你的追踪后端。

有两个字段值得原样采用，因为它们廉价且回答真实的审计问题：**\`gen_ai.prompt.name\` 加 \`gen_ai.prompt.version\`** 在不存储文本的情况下证明运行了哪个提示词版本，而 **\`gen_ai.conversation.compacted\`** 回答模型看到的是完整历史还是摘要。还要注意 \`gen_ai.provider.name\` 是一个遥测格式判别符，可能指向一个代理，而非哪个供应商处理了数据的证明，且 \`gen_ai.conversation.id\` 不得从 UUID、trace id 或内容哈希伪造，因此它在许多轨迹中合理地缺席。

跨度限制会悄悄截断轨迹：\`OTEL_SPAN_ATTRIBUTE_COUNT_LIMIT\` 默认为 128。扁平化的按消息索引属性（OpenInference 的 \`llm.input_messages.<i>.message.*\` 形态）在一次长对话中可能超过它，而一个单一的结构化 \`gen_ai.input.messages\` 只花一个属性。那是推导出的算术，不是有据可查的事件。结构化属性值在跨度上也尚未被普遍支持，因此同一个逻辑字段在一个后端里是 JSON 字符串，在另一个后端里是对象。

规范自己的生产建议正是这里所主张的架构：把内容存在有独立访问控制的外部存储中并在跨度上记录引用，并“无论跨度采样决定如何”都调用上传钩子。**采样追踪，绝不采样证据。** 那就是 \`payload_ref\` 加摘要的另一种叫法。

收尾规则：**为仪表盘发出 OTel，为轨迹拥有一张表，用 \`run_id\` 连接二者，并让两个保留时钟分开。**
`;

export default content;
