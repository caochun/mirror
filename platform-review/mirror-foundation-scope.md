# Foundry 对 Mirror 的必要范围

> 历史评估：旧Mirror实现已按后续用户要求删除。当前模型、分层及底座判断以[重新建模核查](../business-spec/foundry-readiness.md)为准，下文只记录删除前状态。

日期：2026-09-28。用户调整：不再以完整覆盖上游作为交付前提，不影响 Mirror 的平台缺口不继续实现。此判断优先于此前各阶段“完整目标继续”的历史记录。

## 当前结论与证据

Foundry 已具备继续实现 Mirror 的主要底座能力：对象/关系模型、身份与属性校验、状态与历史、事务/并发版本、审计/outbox、持久元数据及基本查询。不能由此声称 Mirror 已完整或可以生产上线。

Mirror 实际入口 `mirror-server/StorageConfiguration` 加载 Domain Pack 并构造 JdbcStorageProvider；业务层使用 StorageProvider/Transaction、自有 BusinessCommands 及 Spring Security。server 的直接 Foundry 依赖是 foundry-pack、foundry-storage-jdbc；business-core 直接依赖 foundry-spi。通用 GraphQL/ApplicationService、CDC、Overlay、托管连接器没有进入当前业务调用路径，不因模块已实现就算业务已经接通。

DirectoryService 通过500条一页的基础SPI取数后处理；MetricsSnapshotReader有只读JDBC投影，在REPEATABLE_READ中读取对象和关系，依赖现有Foundry表布局。因此“大屏必须等通用聚合/搜索/订阅平台完成”不是当前依赖事实；规模和一致性仍需用实际业务查询验收。

已提交基线为Foundry ef09ac3 / Mirror 217f9d1。最近完整Java回归Foundry810项、根reactor920项通过，这是已实现功能的回归证据，不代表两份业务材料或生产环境完整验收。

本轮托管REST改动尚未提交。其31项定向HTTP/应用测试及2项独立JVM提交前后中断恢复测试已通过；保留工作区，不把它作为后续必须完成的独立平台目标，不与已提交基线混算。未运行本批全仓回归，不报告为正式交付。

## 仍与 Mirror 有关的五组工作

| 工作 | 对 Mirror 的影响 | 所属层与处理原则 |
| --- | --- | --- |
| 人员/组织/任职及身份接入 | 来源同步、匹配、停用、非对象处理和权限随组织变更；真实业务不能只依赖演示数据 | Mirror适配与治理流程。根据实际源契约选择现有JDBC/REST或批量导入，不预先要求Kafka或全通用连接器平台 |
| 实际访问路径的权限、审计及事务一致性 | 跨单位/租户、历史访问、敏感字段、幂等与后台作业需端到端验证 | 优先审计Mirror服务及UI/API，通用Foundry功能存在不等于应用已经使用。只有共享SPI正确性缺陷才回补底座 |
| 查询规模与业务统计 | 选人、规则重算、关系查询、历史与统计的5万级性能尚未验收 | 用业务负载验证并按瓶颈优化。可用受控JDBC投影/索引，不以通用SQL编译器或全部数据库下推作为前置条件 |
| 实际国产数据库与交付 | 当前默认H2；openGauss配置入口不等于真实国产库兼容、并发、迁移、备份恢复验收 | 选定实际目标库，验证Mirror业务表/Flyway和Foundry表/事务/锁/历史及升级。数据库差异回到对应适配层修复 |
| Mirror业务闭环与真实外部渠道 | 映射治理、专项事项/阶段、AI建议复核、真实鹿路通与H5身份等尚有未完成项 | Mirror应用、配置与外部适配。遵循tasks/runtime-contracts中的已实现边界；标签规则、审核、通知和AI不迁到底座 |

以上是五组实施/验收工作，不是五个小改动，也不是本轮已证实的五个Foundry代码缺陷。细项以business-spec/tasks.md与runtime-contracts.md为准。

常规人员调动和关系解除已有历史能力。任意历史区间追溯更正、未来事实仅在Mirror明确需要该业务语义时再立契约；不能把不支持的时间操作包装成普通修改，也不为“完整双时态”名义无限扩展。

## 不再主动补齐的平台差异

- 完整上游ODL AST、全部指令、接口型Action、通用代码生成、CLI/SDK及通用Undo。
- 完整GraphQL订阅/批量能力、通用分布式事件与平台部署套件；Mirror采用自身业务API及所需作业即可。
- 医疗FHIR/CDM及Consent专项扩展，Mirror当前没有这些领域需求。
- CDC的Schema Registry、rebalance/租约、完整Kafka运维；仅在真实来源要求CDC时评估。
- Overlay关系、writeback、跨节点缓存失效；当前Mirror以本地物化事实为主，不把这些作为前提。
- 自动FGA模型编译发布、完整Pack运行编排、全部数据库/全部查询下推和独立配置epoch；只补Mirror实际需要的身份/权限/部署方案。

“不继续扩展”不等于删除已实现代码，也不等于对这些能力作生产承诺。Mirror若选择接入，必须先验证对应能力的正确性、权限及实际契约。

## 后续执行规则

每次提出Foundry改动，先注明它阻塞的Mirror业务场景、实际调用位置或失败验收用例。应用层可以合理解决的业务问题留在Mirror；没有直接业务依据的上游差异保留为参考，退出当前任务范围。

先回到Mirror运行契约与业务验收矩阵，按用户确定的应用范围推进。不要根据以前的自动目标继续扩充通用平台，也不要把“完整上游重制”标记成已完成。
