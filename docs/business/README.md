# Mirror 业务规约

业务依据来自两份原始 DOCX 及已确认的讨论约束：不存在可复用的“明镜现有技术栈”；不照搬文档的技术方案；XLSX 只作参考数据；Foundry 提供通用机制，Mirror 实现具体业务规则与流程。

| 文档 | 内容 |
| --- | --- |
| [业务自述](mirror-business.md) | 对象库的业务主链与领域解释 |
| [Domain Pack 说明](domain-pack.md) | 领域对象、关系、版本及原子 Action 的业务含义 |
| [需求与来源](requirements.md) | 原文证据、覆盖矩阵及需求解释 |
| [服务边界](service-boundaries.md) | Foundry、Domain Pack、Mirror 服务与外部系统的分工 |
| [Action 边界](action-boundaries.md) | 原子事实变更与业务流程编排的关系 |
| [结构契约](payload-contracts.md) | JSON 配置和证据的数据结构要求 |
| [待确认决定](review-decisions.md) | 原文冲突、已确认约束与尚待明确的业务口径 |
| [目录参考](catalog-reference.md) | 原始标签附件的参考含义，不代表已启用规则 |
| [页面术语](ui-terminology.md) | 原文术语核对、界面用词及对象关系入口 |

这些文档说明系统应当表达什么。哪些内容已实现，以 [开发进度](../development/progress.md) 为准；实施计划见 [开发文档](../development/README.md)，旧核查和旧方案见 [历史归档](../archive/README.md)。
