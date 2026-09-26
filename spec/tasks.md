# Foundation v0.1 任务清单

## 规约和工程基线

- [ ] 评审并冻结 Foundation Constitution。
- [ ] 确定 Java 版本、构建工具、代码风格和包命名。
- [ ] 确定第一种国产数据库 Provider 和最小支持能力。
- [ ] 建立 ADR 模板和变更审查流程。

## Schema 和 Pack

- [ ] 定义 Java 版 `ParsedSchema` 和 `CompiledOntology`。
- [ ] 实现 ODL 解析、指令解释和错误定位。
- [ ] 实现 ObjectType、LinkType、ActionType 校验。
- [ ] 实现 Pack 依赖、命名空间和确定性合并。
- [ ] 实现 Schema diff、迁移分类和版本注册。

## 对象、关系和历史

- [ ] 定义 Object、Link、HistorySnapshot 和 Provenance 类型。
- [ ] 定义当前表、历史表和关系基数约束。
- [ ] 实现对象版本和关系版本。
- [ ] 实现 `getObjectAtTime`、`getLinkAtTime` 和 `traverseAsOf`。
- [ ] 实现 `valid_time + recorded_time` 双时态查询。
- [ ] 实现乐观并发和事务回滚。

## Action、事件和审计

- [ ] 实现 Action manifest 解析。
- [ ] 实现参数校验、CEL 前置条件和受限效果执行。
- [ ] 实现幂等键、批量结果和失败错误码。
- [ ] 实现审计表和追加式写入。
- [ ] 实现 Transactional Outbox。
- [ ] 实现 CloudEvents 发布、重试和消费者幂等。

## 安全和 API

- [ ] 实现 OIDC/JWT 主体和租户提取。
- [ ] 实现 OpenFGA 授权适配。
- [ ] 实现字段级脱敏。
- [ ] 实现通用 GraphQL 查询和关系遍历。
- [ ] 实现通用 REST 查询和 Action 路由。
- [ ] 生成并锁定 API 契约。

## 同步和一致性测试

- [ ] 实现 JDBC/REST 连接器接口。
- [ ] 实现来源映射和字段血缘。
- [ ] 实现来源优先级、时间优先级和 Action 优先级冲突策略。
- [ ] 将 [conformance/README.md](conformance/README.md) 转为 JUnit 测试套件。
- [ ] 为 PostgreSQL 和国产数据库运行相同测试。
- [ ] 完成 Person/Organization/Assignment 调动场景端到端测试。

