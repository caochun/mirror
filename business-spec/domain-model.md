# 政务对象库业务模型

> 本文为早期概念摘要。完整业务定义以 [Domain Pack 0.2.0](../domain-pack/README.md) 为准，尤其是来源贡献、事项阶段、审核轮次及跨版本稳定接收关系。

## 基础对象

```text
Person
Organization
Position
Assignment
ExternalIdentity
DataAssociationIssue
ObjectEligibility
```

核心关系：

```text
Person --belongsTo--> Organization
Person --holds--> Position
Person --hasAssignment--> Assignment
Assignment --inOrganization--> Organization
Assignment --usesPosition--> Position
Person --hasExternalIdentity--> ExternalIdentity
Person --hasDataIssue--> DataAssociationIssue
Person --hasEligibility--> ObjectEligibility
```

业务约束：

1. 有效人员只能有一个当前归属组织。
2. 人员可以同时拥有多个当前有效职务或任职关系。
3. 组织、身份和廉政档案来自外部权威系统，业务对象库不修改其主数据。
4. 身份或当前组织无法唯一确定时，保留人员对象并记录异常。
5. 非对象账号不参与标签、AI建议和提醒名单。
6. 停用人员保留所有历史对象、标签、任务和回执。

## 标签对象

```text
TagDefinition
TagVersion
TagRule
PersonTagAssignment
TagCandidate
TagProcessingIssue
TagBatch
```

标签关系必须保存人员、稳定标签ID、标签版本、来源、来源组织、生效/失效时间、操作人、操作时间、批次和备注。标签不是人员的静态字符串属性。

## 提醒对象

```text
ReminderContent
ContentVersion
MediaAsset
ReminderTask
ReminderTaskVersion
RecipientRecord
DeliveryAttempt
DeliveryReceipt
ReadReceipt
OverdueRecord
WithdrawalRecord
```

任务版本保存最终标题、正文、图片、链接、接收名单、阅读时限和计划发送时间。任务示例库的后续变化不得覆盖已发布任务快照。
