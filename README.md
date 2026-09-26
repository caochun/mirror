# Open Foundry Java

Open Foundry Java 是面向对象、对象关系、状态历史和领域模型编译的数据底座实现。当前代码按 [Foundation v0.1 规约](spec/README.md) 逐步建设，领域业务通过 Domain Pack 接入。

## 构建

项目以 Java 21 为编译目标。使用 JDK 21 或更新版本均可构建；当前开发环境使用 OpenJDK 27。

```bash
mvn test
```

## 模块

| 模块 | 作用 |
|---|---|
| `foundry-spi` | 对象、关系、历史、事务和 Storage SPI 契约 |
| `foundry-schema` | ODL 解析、Schema 校验、diff 和版本注册 |
| `foundry-storage-memory` | 内存 Storage Provider 和一致性测试基线 |
| `foundry-storage-jdbc` | 关系数据库 JDBC Provider、当前/历史表和时态遍历 |
| `foundry-actions` | Action YAML 解析和受控事务效果执行 |
| `foundry-pack` | Domain Pack manifest、ODL 和 Action 加载 |
| `foundry-events` | 追加式审计与 Transactional Outbox 存储基线 |

## 当前实现边界

已实现并测试：

- ODL 对象、关系和 Action 类型解析；
- Schema 变更分类和内存版本注册；
- 对象/关系当前状态、版本、双时态历史；
- `traverseAsOf` 历史关系遍历；
- 内存和 JDBC Storage Provider；
- Action YAML、基础前置条件和事务效果；
- Domain Pack 加载；
- 内存/JDBC 审计与 Outbox Store。

尚待完成：

- 正式 CEL 运行时适配；
- 对象、关系、历史、审计和 Outbox 的同一事务写入；
- 国产数据库 Provider 和方言验证；
- OIDC、关系授权、字段脱敏和 API 层；
- JDBC/REST 同步、来源血缘和冲突解决；
- 完整的一致性测试套件。

办公文档被根目录 `.gitignore` 忽略，Open Foundry 上游代码位于 `references/open-foundry` 子模块中。

