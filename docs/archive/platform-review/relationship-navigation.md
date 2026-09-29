# Foundry关系声明与导航读取

日期：2026-09-28。Foundry提交：`f20138d`。固定上游v0.3.0 / `1d7e1aa`。完整覆盖目标仍在进行。

## 改动

ODL的`@link`不再被过滤。对象、接口和关系类型模型保存独立的关系字段定义，校验端点方向、目标类型、列表基数、history标记及继承冲突。关系字段是读取投影，不能写入对象的普通属性；事实仍由独立关系记录承载。

GraphQL支持正反向嵌套导航、接口上的关系字段和返回关系记录及其属性；REST通过`/api/v1/{type}/{id}/links/{field}`调用同一个ApplicationService入口。源对象、关系和目标对象逐项检查viewer权限，并应用各类型字段策略。集合先过滤不可见结果再计算first/offset。

`history: true`包含已结束关系，每个关系ID返回一个最新记录。这不是双时间图快照；当前入口明确拒绝asOf参数，避免混用历史边和当前端点。

## 验证

根工程 `mvn -q test` 共276项，其中Foundry166项，无失败/错误/跳过。新增5项Schema测试及12项memory/H2共享API测试，覆盖嵌套正反向查询、敏感字段、源/边/目标撤权、历史端点软删除、直接写投影拒绝、跨租户，以及超过100条隐藏关系后的正确分页。最后补查继承关系字段改为计算字段的冲突，Schema测试再次通过。

上游Library五份原始ODL已原样纳入Foundry测试资源并保留来源及许可证。编译测试验证原borrower/books字段；独立探针也确认Book.borrower保留，既有授权、属性、时间和幂等修复无回退。探针与运行日志不等于真实国产数据库或OpenFGA集成验收。

## 未完成项

Library借还动作仍受sideEffects和筛选deleteLink缺口影响，尚未完整运行。跨Pack组合、计算字段、关系字段immutable/constraint的写入语义、required关系提交约束、关系到关系端点、时间一致的通用API、过滤排序/聚合与批量授权仍待实现。导航读取尚未提供整个多次读取过程的并发一致快照。

详见[ADR-0009](../../../foundry/spec/adr/0009-declared-relationship-navigation.md)与[对齐计划](../../../foundry/spec/upstream-parity-plan.md)。本次未扩展Mirror业务或重新打包运行中的服务，未修改运行库。
