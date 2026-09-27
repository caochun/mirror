# 契约输入与引用语义

`action-types.odl`是动作业务参数签名。`actions.yaml`补充身份、前置条件、原子效果、幂等和事件。两者均是定义，当前Foundry Loader不加载这些动作。标量JSON需要在实现时落实以下结构校验，禁止直接执行内容。

所有写命令共有：可信tenant、actor、currentOrganization上下文；客户端commandId；涉及已有对象的expectedVersions；可选业务effectiveAt和reason。服务端产生recordedAt、事务ID。单个动作的可选备注不被公共reason字段变成强制。

- 对象类型参数表示稳定对象引用，需按租户和权限重新解析，不接受客户端伪造的完整对象状态。
- ID是新增/查找标识；对象类型?是可选引用；[T]是数组。集合需数量限制、去重、逐项权限校验。
- `facts/fieldsJson`：白名单字段到值，以及字段可用性（AVAILABLE/MISSING/INVALID/UNKNOWN）、来源系统/记录/版本、生效时间。性别、生日、入职日期、职级等只取可可靠提供的数据；身份和联系方式使用受保护引用。
- `conditionJson`：`all`或`any`条件组，叶子为`field/operator/value`。操作符只允许标准相等/集合匹配、日期相对范围、组织/职务映射；相对年龄以业务统计日计算。无SQL、脚本、任意公式、正则或客户端属性路径。
- `scope/filters`：组织根ID集合、是否含下级、人员ID集合、标签版本集合、tagOperator=ANY/ALL、业务状态、时间范围。scope只能缩小服务端授权范围。
- `evidenceJson`：输入字段摘要和来源记录/版本/生效时间、所用规则/策略版本、判断理由。完整敏感信息不进入对话结果或无权限审计视图。
- `body`：白名单结构化富文本节点或清洗后的HTML、不可猜测媒体ID及摘要、完整受控HTTPS链接。保存时重新清洗；冻结时保存安全检查引用与媒体不可变副本。
- `recipientsJson/personIdsJson`：兼容只读快照，包含接收ID和人员ID；权威成员关系是VersionTargetsRecipient和TaskHasRecipient，不能两套独立修改。
- `organizationRootsJson/tagVersionIdsJson`：筛选表达式快照，改变后更新selection revision和digest。matchedByCondition/explicitlyIncluded/manuallyExcluded分别保存，同人多入口只保留一行。
- `linksSnapshotJson/mediaManifestDigest`：链接地址、可信域名检查结果及版本；媒体清单包含摘要/顺序/确认记录，媒体替换不能沿用旧确认。
- `AssistantQuery`条件使用同一过滤结构，结果带指标代码、分子分母/单位、asOf、实际返回范围、需求引用和明细入口；对话输入不是可直接执行的DSL。

唯一性键见rules.yaml。关系端点与兼容字符串ID必须一致；snapshotId、taskVersionId等兼容字段的迁移语义见MIGRATION.md。

发生业务冲突返回可解释原因，不静默删减名单或覆盖别人审核。规则/AI批次可部分成功，每项事实/历史/审计在一个事务内；需要原子操作的提交审核、发布版本、单人标签变更不可拆成多次独立写。

对外渠道调用不能与本地数据库伪装成单一事务：先提交发送/撤回意图和outbox，再由适配层执行，回执通过受控系统动作确认。此定义不选择队列、数据库品牌或Web框架。
