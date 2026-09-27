# 政务系统对象库业务规约

本目录记录基于 Foundry 构建的政务系统对象库业务规则。它描述业务对象、对象关系、状态、角色、Action 和验收场景，不描述数据库、HTTP 框架、部署拓扑等平台实现细节。

Foundry 位于 `foundry/` 子模块，负责通用对象关系、历史、事务、权限、事件和 Domain Pack 运行时。本仓库只维护政务领域业务。

## 文档

- [Domain Pack 0.2.0](../domain-pack/README.md)：最新业务对象、关系、动作签名、规则、状态与两份原始文档的需求对照；定义完善不等于完整系统实现。
- [application-plan.md](application-plan.md)：当前有效的核心价值大屏计划，React + Tailwind 前端、Mock 数据和展示门槛。
- [dashboard-plan.md](dashboard-plan.md)：大屏的信息架构、指标口径和 Mock 边界。
- [progress.md](progress.md)：阶段实现、实际验证证据和未完成边界。
- [acceptance-matrix.md](acceptance-matrix.md)：原始需求编号到页面、API、测试及剩余项的验收对照。
- [business-plan.md](business-plan.md)：从零重建计划和阶段性交付物。
- [domain-model.md](domain-model.md)：业务对象和关系模型。
- [workflow-model.md](workflow-model.md)：业务状态和 Action 流程。
- [frontend-design.md](frontend-design.md)：重新对照原始文档形成的页面、角色、交互与验收设计草案，包含范围差异和待确认事项。
- [tasks.md](tasks.md)：核心价值大屏任务清单；完整业务系统作为后续方向暂停。
