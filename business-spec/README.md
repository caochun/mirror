# Mirror重新建模规约

本目录的有效内容均基于本轮重新读取原始DOCX及用户已明确的约束：**不存在可直接复用的“明镜现有技术栈”；不照搬文档技术方案；不是所有业务要求都在Foundry层实现。**

- [../pack-explorer/README.md](../pack-explorer/README.md)：本地模型体验台的启动、演示边界及验证。
- [mirror-business.md](mirror-business.md)：由当前Pack反向梳理的Mirror业务叙述与职责。
- [requirements.md](requirements.md)：需求来源、章节/段落、覆盖矩阵与业务解释。
- [action-boundaries.md](action-boundaries.md)：已注册原子Action、Mirror流程编排、授权/审计与必要事务边界缺口。
- [service-boundaries.md](service-boundaries.md)：Foundry／领域包／Mirror服务／外部系统分工及事务责任。
- [payload-contracts.md](payload-contracts.md)：模型中JSON配置与证据的结构边界，防止把未定义业务藏进JSON。
- [review-decisions.md](review-decisions.md)：范围分歧与待确认决定。
- [foundry-readiness.md](foundry-readiness.md)：底座实现是否足够及验证限制。
- [catalog-reference.md](catalog-reference.md)：试点附件4原始标签表格供配置确认，不是已启用的自动规则。
- [tasks.md](tasks.md)：只到模型确认的任务清单。

旧文档已移至 `archive/legacy-before-remodel-20260928/`，其已完成状态、页面/API、模块路径和自动goal均不再有效。旧实现和模型仍可通过Git历史追溯。
