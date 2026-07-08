import type { BlogTranslation } from '../i18n';
import theNicheDataAdvantage from '../content/zh/the-niche-data-advantage';
import chatToWorkflowNoCode from '../content/zh/chat-to-workflow-no-code';
import fromDatasetToLiveWorkflow from '../content/zh/from-dataset-to-live-workflow';
import workflowBeatsDoEverythingAgent from '../content/zh/workflow-beats-do-everything-agent';
import smallDataSharpDecisions from '../content/zh/small-data-sharp-decisions';
import capAiAgentCostBudgets from '../content/zh/cap-ai-agent-cost-budgets';
import aiAgentAuditTrail from '../content/zh/ai-agent-audit-trail';

export const zhBlog: BlogTranslation = {
  ui: {
    eyebrow: "实战手记", blogTitle: "Blog", lead: "关于利基数据以及在其之上构建的自动化的手记。为什么窄数据集胜过宽数据集，以及如何把一个数据源变成一个自行运行的工作流。", latest: "最新", readThePost: "阅读文章", readMore: "阅读更多", allPosts: "全部文章", minRead: "分钟阅读", by: "作者", and: "和", ctaTitle: "把你的利基数据变成一个能用的自动化", ctaText: "在聊天里描述这份任务，LiveContext 就在你眼前构建出工作流。", startFree: "免费开始", metaTitle: "Blog - LiveContext", metaDescription: "关于利基数据以及在其之上构建的自动化的实战手记：为什么窄数据集胜过宽数据集，以及如何把一个数据源变成一个自行运行的工作流。",
  },
  posts: {
    "the-niche-data-advantage": { title: "利基数据的优势", excerpt: "大数据是一种大宗商品。真正交付有用自动化的团队，赢在几乎无人愿意去结构化的小巧、精准的数据集上。", coverAlt: "一台笔记本电脑展示着带有图表、地图和指标的分析仪表盘", content: theNicheDataAdvantage },
    "chat-to-workflow-no-code": { title: "从聊天到工作流：无代码 AI 自动化", excerpt: "用大白话描述任务，得到一个你能看见、能运行、能修改的工作流。无需手动接线节点，没有黑盒。", coverAlt: "一只手在手机上输入消息，屏幕上显示着一段聊天对话", content: chatToWorkflowNoCode },
    "from-dataset-to-live-workflow": { title: "从数据集到实时工作流", excerpt: "一套五步的形状，把一个静态的利基数据源变成一个自我刷新、并以真实动作收尾的工作流。", coverAlt: "一只手在白板上画出由方块和箭头连成的工作流示意图", content: fromDatasetToLiveWorkflow },
    "workflow-beats-do-everything-agent": { title: "为什么工作流胜过一个什么都干的智能体", excerpt: "一个有范围的工作流运行成本低得多，保持可审计，也比一个什么都干的大型自主智能体更少失败。这里讲的是各自何时该用。", coverAlt: "一个立在支架上的单臂机械臂，代表一个自主智能体", content: workflowBeatsDoEverythingAgent },
    "small-data-sharp-decisions": { title: "小数据，利决策", excerpt: "更好的决策很少需要更多数据。一个小巧可信、对应某个选择的数据集，胜过一个把信号埋起来的庞然大物。", coverAlt: "双手在打印的图表旁使用计算器分析数据", content: smallDataSharpDecisions },
    "cap-ai-agent-cost-budgets": { title: "如何给一个 AI 智能体的花费封顶", excerpt: "无上限的智能体是一种财务风险。给每一个都设一份它不能超出的硬预算，并为它能触及的工具和数据划定范围。", coverAlt: "散落在桌面上的硬币，旁边有一本笔记本和一支笔用于做预算", content: capAiAgentCostBudgets },
    "ai-agent-audit-trail": { title: "每个 AI 智能体都需要的审计轨迹", excerpt: "一个能用的演示还不够。记录输入、工具调用、输出、成本和每一个决策，好让你能调试、能证明合规、能赢得信任。", coverAlt: "一只放大镜和一个计算器搁在打印的文件上", content: aiAgentAuditTrail },
  },
};
