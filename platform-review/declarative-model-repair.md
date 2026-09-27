# Foundry声明属性与接口继承

日期：2026-09-28。Foundry提交：`878b4f1`。上游基线仍为v0.3.0 / `1d7e1aa`，完整覆盖目标保持进行中。

## 本次实现

- ODL保留字面量默认值、readonly、字段/类型CEL约束、接口及祖先。单Schema内展开普通继承字段，拒绝循环、未知接口和冲突定义；直接Java Schema入口同样验证展开结果。
- 对象和关系共享声明式校验：创建时为省略字段填默认，显式null不触发默认，更新不回填。标准创建/更新人及时间由引擎生成，拒绝调用方写入readonly。旧数据不会被自动补造审计信息。
- 默认值和引擎字段生成后，对完整合并状态执行约束；编译失败、求值错误、非布尔结果或规则不满足均拒绝写入。
- GraphQL输出保留接口、枚举、列表、JSON及日期等标量；敏感属性经字段策略隐藏后仍允许返回null。
- Schema摘要和迁移分类包含新增声明语义。新增foundry-validation模块供编译器和两个存储Provider复用，未引入政务业务规则。
- 修复CEL Program创建未继承环境标准库的问题，Action中的size等标准函数也恢复执行。

## 验证证据

运行 `mvn -q test`：根reactor259项，其中Foundry149项，全部通过，无失败/错误/跳过。本阶段新增27项，包含18项memory/H2共享声明测试，以及Schema、GraphQL、CEL测试。覆盖只读伪造、跨字段规则、关系规则、失败无历史、直接模型绕过、循环继承、默认值非追溯和求值预算等边界。

运行 `python3 scripts/foundry-audit/run.py`：[当前探针](foundry-audit-current.json)的接口主键继承错误为空；既有属性、授权、历史修复仍通过。Library Pack仍明确报告未支持sideEffects，Book中的关系/计算字段尚未完整保留，未将拒绝加载算作覆盖完成。

## 兼容边界及后续

Java更新会重新检查所有已存在的可变字段约束，比上游仅检查本次更新字段更严格；immutable字段约束仅创建时执行。同名继承字段要求声明完全一致。新必填只读字段可能阻止旧记录更新，须评审迁移。

属性CEL有1024条编译缓存、8192字符表达式及100000次节点观察预算；matches使用RE2/J并限制输入大小。这不是通用资源沙箱，也不代表原Action求值器已经具备相同限制。详见[ADR-0008](../foundry/spec/adr/0008-declarative-properties-and-interfaces.md)。

跨Pack组合、关系导航与计算字段、类型化Action API、完整effects/sideEffects、通用查询、持久Schema Registry、同步恢复和真实目标数据库验收仍未完成。继续按[上游对齐计划](../foundry/spec/upstream-parity-plan.md)推进。本次未重新打包运行中的服务，未改动实际业务数据库。
