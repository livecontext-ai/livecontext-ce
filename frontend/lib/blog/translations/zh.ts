import type { BlogTranslation } from '../i18n';
import theNicheDataAdvantage from '../content/zh/the-niche-data-advantage';
import chatToWorkflowNoCode from '../content/zh/chat-to-workflow-no-code';
import fromDatasetToLiveWorkflow from '../content/zh/from-dataset-to-live-workflow';
import workflowBeatsDoEverythingAgent from '../content/zh/workflow-beats-do-everything-agent';
import capAiAgentCostBudgets from '../content/zh/cap-ai-agent-cost-budgets';
import sizeAnAiAgentBudget from '../content/zh/size-an-ai-agent-budget';
import aiAgentAuditTrail from '../content/zh/ai-agent-audit-trail';
import aiAgentAuditLogRetention from '../content/zh/ai-agent-audit-log-retention';

export const zhBlog: BlogTranslation = {
  ui: {
    eyebrow: "实战手记", blogTitle: "Blog", lead: "关于利基数据以及在其之上构建的自动化的手记。为什么窄数据集胜过宽数据集，以及如何把一个数据源变成一个自行运行的工作流。", latest: "最新", readThePost: "阅读文章", readMore: "阅读更多", allPosts: "全部文章", minRead: "分钟阅读", by: "作者", and: "和", ctaTitle: "把你的利基数据变成一个能用的自动化", ctaText: "在聊天里描述这份任务，LiveContext 就在你眼前构建出工作流。", startFree: "免费开始", metaTitle: "Blog - LiveContext", metaDescription: "关于利基数据以及在其之上构建的自动化的实战手记：为什么窄数据集胜过宽数据集，以及如何把一个数据源变成一个自行运行的工作流。",
  },
  posts: {
    "the-niche-data-advantage": { title: "利基数据的优势，算笔账", excerpt: "反对护城河的证据比支持它的更有力。所以这篇给利基数据的论点算笔账，而不是吹捧它：先摆出最强的反方论证，再给出一个要测量的参数、一张七行评分卡，以及自建、购买还是什么都不做的盈亏平衡点。", coverAlt: "一台笔记本电脑展示着带有图表、地图和指标的分析仪表盘", content: theNicheDataAdvantage },
    "chat-to-workflow-no-code": { title: "从聊天到工作流：无代码 AI 自动化", excerpt: "用大白话描述任务，得到一个你能看见、能运行、能修改的工作流。无需手动接线节点，没有黑盒。", coverAlt: "一只手在手机上输入消息，屏幕上显示着一段聊天对话", content: chatToWorkflowNoCode },
    "from-dataset-to-live-workflow": { title: "从数据集到运行中的工作流，逐个节点讲", excerpt: "一个跑在生产引擎上的真实工作流图：一套定时的竞品价格监控，会刷新、判断，并为写入把关。附上能正确解析的模板字符串、会悄无声息失败的那些，以及防止它重复插入行的幂等守卫。", coverAlt: "一只手在白板上画出由方块和箭头连成的工作流示意图", content: fromDatasetToLiveWorkflow },
    "workflow-beats-do-everything-agent": { title: "限定范围的工作流相比全能智能体的真实成本", excerpt: "我们删掉了自己那句「便宜 10 倍」的说法，因为它没有任何推导。取而代之的是成本模型：两个函数，其中一个是二次的，一份算到底的工单分流账本，以及比率跌到 1.3x 甚至反转的条件。", coverAlt: "一个立在支架上的单臂机械臂，代表一个自主智能体", content: workflowBeatsDoEverythingAgent },
    "cap-ai-agent-cost-budgets": { title: "真正能拦住智能体的预算", excerpt: "大多数智能体预算只是一个从未拒绝过任何一次调用的数字。一个真正的上限由什么构成，为什么它只能拦住下一次调用，以及每个技术栈实际能强制执行什么。", coverAlt: "散落在桌面上的硬币，旁边有一本笔记本和一支笔用于做预算", content: capAiAgentCostBudgets },
    "size-an-ai-agent-budget": { title: "如何设定一个真正能强制执行的智能体预算", excerpt: "关于设定数值的另一半：一个可复现的生成模型、一个推导出来的安全系数、金额上限低于该下限就完全无法强制执行的临界点，以及在能够引用 p99 之前需要多少次运行。", coverAlt: "双手在打印的图表旁使用计算器分析数据", content: sizeAnAiAgentBudget },
    "ai-agent-audit-trail": { title: "智能体的审计轨迹：一份可以照抄的字段模式", excerpt: "审计轨迹不是更长的日志，而是另一种工件，面向另一类读者。一份可照抄的 run 级与 step 级字段模式，每个字段都带上它的类型、基数、个人数据标记，以及它存在的理由。", coverAlt: "一只放大镜和一个计算器搁在打印的文件上", content: aiAgentAuditTrail },
    "ai-agent-audit-log-retention": { title: "智能体的审计轨迹要留多久，以及你到底负有什么义务", excerpt: "把留存变成推导结果的存储算术、由此得出的分级留存，以及一张诚实的 EU AI Act 日志义务地图，包括其中大多数智能体根本不在适用范围内的那部分。", coverAlt: "一只手在白板上画出由方块和箭头连成的工作流示意图", content: aiAgentAuditLogRetention },
  },
};
