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

原 `TagService` 的目录配置尚未全部迁移到此路径；它不因注册器存在就被标记为契约实现完成。提醒原型已由下述处理器替换，其他业务按实施计划逐域推进。

## 提醒准备与审核处理器

`ReminderService`新增SaveReminderDraft、UpdateRecipientSelection、ConfirmRecipientSelection、ConfirmReminderContent、SubmitReminderReview、DecideReminderReview、WithdrawReminderReview、CancelScheduledReminder与内部ExpireReminderReview路径。保存/确认HTTP命令组合了对应的业务子动作，执行仍在同一Foundry事务内。

它们覆盖文字/列表/可信链接和受控图片；ConfirmReminderContent已校验当前全部图片确认并保存逐图证据。发送审批只创建QUEUED作业，DispatchReminder仍未登记，也未执行真实渠道调用。到期作业由服务端内部上下文触发，没有面向用户的系统权限绕过入口。

名单再次计算保留排除意图，确认检查输入摘要，审核结果只作用于本轮冻结版本。旧草稿遗留接收记录保留审计关联；后续发送及统计必须从批准版本的VersionTargetsRecipient取名单，不能扫描任务历次草稿的接收记录并集。

## 媒体与内容示例处理器

RegisterMediaAsset由MediaService接入：文件验证/规范化、私有不可变保存、Pack元数据与回执/审计/事件写入。鉴权图片GET只返回有权使用的媒体，不能靠枚举ID读取。上传落盘先于业务提交，失败可能留私有孤立文件；尚无自动垃圾回收，不宣称文件与数据库构成同一资源事务。

SaveContentExample、SetContentAvailability由ContentLibraryService接入；只有CONTENT_CONFIGURE可写，REMINDER_WRITE可选择启用版本。版本、推荐标签、受控媒体及任务来源关系均保存。示例编辑与任务副本独立，旧引用不能变成新内容。DeleteUnusedContent尚未接通，界面不提供虚假的删除入口。

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

ReminderWorkflowTest原型已替换为符合Pack的实际流程测试，当前`mvn package`全量通过。上面的定向命令仍可用于标签迭代；完整目标还需发送/H5/规则等其余业务验收，不以当前测试数量代表全部完成。
