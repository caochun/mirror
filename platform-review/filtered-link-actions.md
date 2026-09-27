# Foundry事务筛选删除与上游还书动作

日期：2026-09-28。Foundry提交：`19ce518`。上游基线仍为v0.3.0 / `1d7e1aa`，完整覆盖目标继续。

## 已实现

DeleteLink新增filter.from/to/active及expect.ONE/ALL，保留原直接linkId模式。Transaction.findLinks通过同一数据库连接或内存工作快照选出全部活动关系，包含本事务此前的写入，不被默认100条分页截断。ONE要求恰好一条，ALL允许零条；数量或权限失败回滚此前effect及历史、审计、回执。

ApplicationService对实际选中的每条关系及两端检查动作权限，然后才返回数量错误或执行删除。重放使用回执中原受影响实体验权，防止删除后当前筛选为空而绕过原目标撤权，也不会删除后来新增的替代关系。

ActionValues补普通参数和params路径、对象ID/属性、嵌套Map、actor.id、带单引号的字面量及now。单次执行的前置条件、属性、审计和完成事件共享now；后续effect仍读取执行前属性快照，写入则采用事务内最新版本。旧默认manifest及直接ID删除的摘要编码保留。

## 验证

`mvn -q test`：根reactor298项，其中Foundry188项，均无失败/错误/跳过。本阶段新增22项，包括解析、指纹兼容、表达式、两个Provider的筛选/并发/回滚/权限测试，以及未修改上游ReturnBook YAML和原schema的兼容测试。

ReturnBook已在memory和H2单连接池中验证：无librarian角色时前置条件失败；通过后Book变为AVAILABLE，原借阅关系结束，对象和关系分别保留历史，重复同键返回同一结果。并发测试验证不同键争抢ONE关系只成功一次，同键只写一次审计；ALL验证105条完整选取与租户隔离；重放撤销原关系或原端点权限均拒绝。

独立探针保留ReturnBook的from/book、expect/ALL和ROLLBACK_ALL声明，原属性、历史、接口、导航及授权修复未回退。H2与本地策略测试不等于真实国产数据库/OpenFGA验收。

## 明确未完成

ReturnBook不含外部副作用，因此保留rollback策略声明并不代表补偿能力已经实现。BorrowBook和完整Library Pack仍因sideEffects不支持而拒绝加载。上游事件副作用在业务提交后执行，ROLLBACK_ALL还涉及已提交变更的补偿；本轮没有用outbox成功写入冒充这套行为已完成。

接下来仍需实现持久副作用任务、分发/恢复、失败策略和补偿，再验证完整借还。跨Pack、计算字段、完整effect表达式和关系路径等缺口继续保留。详见[ADR-0010](../foundry/spec/adr/0010-transactional-link-selection.md)。本次未扩展Mirror业务，也未重新打包服务或修改运行数据库。
