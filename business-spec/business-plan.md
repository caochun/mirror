# 政务系统对象库重建计划

## 目标

基于 Foundry 从零建设政务系统对象库业务系统，形成“人员对象—组织关系—标签画像—提醒任务—送达阅读—逾期督促”的可追溯业务闭环。

旧的 `gov-supervision-pack` 不作为实现基础。新的业务包、对象模型、Action 和测试全部重新定义；试点方案和需求说明书只作为业务需求来源。

## 阶段一：业务边界和模型冻结

交付：

- 业务术语和范围；
- 基础对象模型；
- 标签、内容、任务和回执对象模型；
- 角色与数据范围规则；
- 状态机和业务不变量；
- 端到端验收场景。

完成标准：业务对象和关系能用 Foundry ODL 表达，且没有把标签规则、鹿路通协议或页面细节混入基础对象。

## 阶段二：基础对象域

对象：`Person`、`Organization`、`Position`、`Assignment`、`ExternalIdentity`、`DataAssociationIssue`、`ObjectEligibility`。

能力：

- 人员、组织、岗位和任职关系；
- 当前组织唯一约束；
- 历史任职和调动；
- 外部身份/档案关联；
- 非对象账号和停用状态；
- 关联异常记录和处理 Action。

## 阶段三：标签域

对象：`TagDefinition`、`TagVersion`、`TagRule`、`PersonTagAssignment`、`TagCandidate`、`TagProcessingIssue`、`TagBatch`。

能力：

- 最多三级标签目录；
- 长期性标签和阶段性标签；
- 确定性规则、人工赋标、AI建议复核；
- 人工删除抑制规则恢复；
- 标签版本和完整历史；
- 局部异常和重算批次。

## 阶段四：提醒内容和任务域

对象：`ReminderContent`、`ContentVersion`、`MediaAsset`、`ReminderTask`、`ReminderTaskVersion`、`RecipientRecord`。

能力：

- 内容示例维护；
- 标签到内容的推荐关系；
- 任务内独立内容草稿；
- 接收名单按组织、标签和指定人员组合；
- 名单冻结和审核快照；
- 立即/单次定时发送；
- 审核、驳回、取消、修订和撤回。

## 阶段五：送达、阅读和逾期域

对象：`DeliveryAttempt`、`DeliveryReceipt`、`ReadReceipt`、`OverdueRecord`、`WithdrawalRecord`。

能力：

- 鹿路通适配层接口；
- 逐人送达结果；
- 当前版本首次阅读回执；
- 1天、2天、3天、1周阅读时限；
- 逾期未读清单和解除；
- 失败/未知结果单独重试；
- 送达、阅读和H5异常统计。

## 阶段六：权限、统计和 AI 助手

能力：

- 超级、片区、单位管理员和推送审核员权限；
- 当前组织上下文和组织数据范围；
- 对象、标签、任务和回执统计；
- 敏感字段查看审计；
- AI 查询标签、人员和提醒闭环；
- AI 动作卡必须人工确认，不能直接发送。

## 阶段七：业务验收和部署准备

- 5万级对象分页查询；
- 全链路权限越权测试；
- 事务、历史、失败重试和幂等测试；
- PostgreSQL/openGauss 目标环境验证；
- 鹿路通 Mock 和正式契约切换；
- 业务 Domain Pack 发布和版本冻结。

