# Pack 契约到运行实现的登记

当前定义基线：0.2.1。这里区分“定义存在”和“处理器已接入”，不是生产完成清单。

## 已接入的首批处理器

| Pack动作 | 处理器 | HTTP适配 | 主要证据 |
| --- | --- | --- | --- |
| AddPersonTag | PersonTagCommands | POST /api/tags/{id}/assignments，operation=ADD | 权限、末级/发布版本、重复回放、来源贡献、再次添加解除抑制 |
| RemovePersonTag | PersonTagCommands | 同上，operation=REMOVE | SUPPRESSED状态、所有贡献保留、逻辑标签范围校验 |
| RestorePersonTag | PersonTagCommands | 同上，operation=RESTORE | 原来源贡献不被覆盖，新增本次人工贡献，解除抑制 |

现有批量HTTP请求是至多100个单人命令的原子适配：每人使用自己的expectedVersion、personId/assignmentId和同一已发布tagVersionId进行契约输入检查，一项不合格则整批回滚。不是把一条单人签名解释成任意JSON批量。

`DomainContracts`启动时读取Pack动作与状态定义，权限名来自契约；动作、状态、模型和规则内容摘要进入结果/审计。仅明确登记的处理器可通过此执行路径，未定义或未接通动作拒绝。自由文本规则仍由业务代码实现，不声称YAML文本已经成为自动规则引擎。

原 `TagService` 的目录配置和旧提醒原型尚未全部迁移到此路径；它们不因注册器存在就被标记为契约实现完成。后续应按实施计划逐域替换。

## 人员标签兼容策略

- API/持久化新状态使用SUPPRESSED，前端对旧REMOVED保留明确旧记录说明。
- 写入旧人员标签时，若尚无贡献且旧标签版本引用可靠，则在同一事务迁入一条legacy贡献，保留原source/时间/组织，不补造规则评估证据。版本依据不可靠时拒绝并要求数据核实。
- 删除设置整体抑制，不终止其他来源；恢复新建人工贡献，不把原RULE改成MANUAL。
- 恢复动作清除当前抑制标记，旧抑制人/时间和备注保留于对象历史；原来源组织与当前操作组织分别记录。
- 旧PersonTagADD/PersonTagREMOVE命令回执，仅在账号、请求标识和原请求指纹完全一致时兼容回放；不同请求不能复用幂等标识。
- 自动规则/AI输入的生产处理器尚未接通，不能用人工生命周期测试声称自动重算抑制已完成端到端验收。

## 验证命令

```bash
mvn -pl business-verification -am test
mvn -pl mirror-server -am package \
  -Dtest=ApplicationSecurityTest,PersistenceRestartTest,TagWorkflowTest \
  -Dsurefire.failIfNoSpecifiedTests=false
npm --prefix web run build
npm --prefix web run test:e2e
```

当前工作区仍有未提交的ReminderWorkflowTest原型，其Map.of空值测试准备会导致完整Maven测试失败。上面的定向测试命令明确用于本阶段，不是完整系统测试结果；提醒原型和对应测试将在提醒阶段按Pack重做，不能靠跳过它们宣布目标完成。
