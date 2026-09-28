# 业务状态和 Action

> 本文为早期流程摘要。新版动作定义和状态约束见 [动作契约](../domain-pack/contracts/actions.yaml)、[动作签名](../domain-pack/contracts/action-types.odl) 和 [状态契约](../domain-pack/contracts/states.yaml)。送达与版本阅读独立；修订不重置首次送达时间或阅读截止时间。

## 标签状态

```text
候选 → 待复核 → 已生效
已生效 → 已失效
已生效 → 人工删除抑制
人工删除抑制 → 人工恢复
```

AI候选未经有权限管理员复核不得进入已生效状态。确定性规则失效只终止对应规则来源关系，不删除人工或其他来源关系。

## 提醒任务状态

```text
草稿 → 待审核 → 已驳回 → 草稿
待审核 → 已审核待发送
待审核 → 审核过期
已审核待发送 → 发送中
已审核待发送 → 已取消
发送中 → 全部成功
发送中 → 部分失败
发送中 → 全部失败
全部成功/部分失败 → 撤回中 → 全部撤回/部分撤回/撤回失败
已发送版本 → 新版本待审核 → 新版本发布
```

## 接收记录状态

```text
待提交 → 已提交 → 送达成功/送达失败
送达成功 → 未阅读 → 已阅读
未阅读 → 逾期未读 → 已阅读
```

每个接收人的阅读时限从实际送达成功时间开始。发送失败不能启动计时；重试成功后重新计算该人员的起算时间。

## 主要 Action

```text
RegisterPerson
ResolveAssociationIssue
MarkNonObjectAccount
AssignTag
RemoveTag
PublishTagVersion
CreateTagBatch
ReviewTagCandidate
CreateReminderTask
FreezeReminderSnapshot
SubmitReminderReview
ApproveReminder
RejectReminder
SendReminder
RetryFailedRecipients
ReviseReminder
WithdrawReminder
RecordDeliveryResult
RecordReadReceipt
ResolveOverdueRecord
```

所有生产写入都必须经过 Foundry Action 或受控同步命令，并写入历史、审计和事件。
