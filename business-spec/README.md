# 政务系统对象库业务规约

本目录记录基于 Foundry 构建的政务系统对象库业务规则。它描述业务对象、对象关系、状态、角色、Action 和验收场景，不描述数据库、HTTP 框架、部署拓扑等平台实现细节。

Foundry 位于 `foundry/` 子模块，负责通用对象关系、历史、事务、权限、事件和 Domain Pack 运行时。本仓库只维护政务领域业务。

## 文档

- [application-plan.md](application-plan.md)：当前有效的完整应用交付计划，React + Tailwind 前端、可启动后端、里程碑与完成门槛。
- [business-plan.md](business-plan.md)：从零重建计划和阶段性交付物。
- [domain-model.md](domain-model.md)：业务对象和关系模型。
- [workflow-model.md](workflow-model.md)：业务状态和 Action 流程。
- [frontend-design.md](frontend-design.md)：重新对照原始文档形成的页面、角色、交互与验收设计草案，包含范围差异和待确认事项。
- [tasks.md](tasks.md)：按完整业务验收重新校正的任务清单，原型代码不等于已完成交付。
