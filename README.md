# Mirror：领域模型确认稿

当前阶段：**旧应用实现已移除，新模型及原子Action可通过本地Pack体验台试用；Mirror生产业务流程仍未重建。**

请按以下顺序审阅：

1. [Mirror业务自述](business-spec/mirror-business.md)：根据修订后的Pack解释业务主链。
2. [原始业务需求与责任分层](business-spec/requirements.md)：重新阅读两份DOCX的结果、业务范围及冲突。
3. [Domain Pack](domain-pack/README.md)：对象、关系、业务语义版本和历史设计。
4. [Mirror服务边界](business-spec/service-boundaries.md)：哪些规则由业务服务执行，哪些能力由Foundry提供。
5. [Foundry适用性核查](business-spec/foundry-readiness.md)：实际验证证据、不能自动保证的事项和实施门槛。
6. [待确认清单](business-spec/review-decisions.md)：确认后才能开始业务实现。

## Pack体验台

当前Pack可以先通过本地体验台试用，确认对象、关系、历史和原子Action是否符合业务直觉：见 [`pack-explorer/`](pack-explorer/)。它只使用隔离内存中的合成数据，不连接旧数据库或外部渠道。

## 仓库范围

- `domain-pack/`：全新 `mirror.domain / 1.0.0` 模型确认稿，不是旧Pack的兼容升级。
- `business-spec/`：本次重新建模的有效规约。`archive/legacy-before-remodel-20260928/`仅保留旧文档历史，不再指导开发。
- `model-verification/`：模型、约束及真实Action执行测试。
- `pack-explorer/`：本地Pack驱动的React/Tailwind体验台及同源HTTP服务，使用合成内存数据。
- `foundry/`：Java通用底座子模块，已有未提交的REST实验仍保留，不是本次模型的依赖。
- `open-foundry/`：上游只读参考子模块。
- `platform-review/`、`scripts/foundry-audit/`：既往平台审计历史，不代表Mirror当前实现状态。
- 两份DOCX、XLSX及`.runtime/`：保留本地原始材料与旧运行数据，继续Git忽略；新模型没有应用到旧数据库。

旧 `business-core/`、`business-verification/`、`mirror-server/`、`web/`、`deployment/` 和旧启动脚本已删除，可从Git历史恢复。当前可运行的是独立的Pack体验台，不是旧应用或生产业务系统。

## 模型验证

```bash
mvn -pl model-verification -am test -Dtest=DomainModelTest,DomainActionTest -Dsurefire.failIfNoSpecifiedTests=false
```

该命令加载真实Pack并验证内存/H2存储，不连接旧Mirror数据库，不发送任何外部消息。测试证明模型与底座可配合，不证明业务流程、前端、性能或国产数据库已经实现/验收。
