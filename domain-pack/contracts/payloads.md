# 契约输入与引用语义

`action-types.odl`是动作业务参数签名。`actions.yaml`补充身份、前置条件、原子效果、幂等和事件。两者均是定义，当前Foundry Loader不加载这些动作。标量JSON需要在实现时落实以下结构校验，禁止直接执行内容。

所有写命令共有：可信tenant、actor、currentOrganization上下文；客户端commandId；涉及已有对象的expectedVersions；可选业务effectiveAt和reason。服务端产生recordedAt、事务ID。单个动作的可选备注不被公共reason字段变成强制。

- 对象类型参数表示稳定对象引用，需按租户和权限重新解析，不接受客户端伪造的完整对象状态。
- ID是新增/查找标识；对象类型?是可选引用；[T]是数组。集合需数量限制、去重、逐项权限校验。
- `facts/fieldsJson`：白名单字段到值，以及字段可用性（AVAILABLE/MISSING/INVALID/UNKNOWN）、来源系统/记录/版本、生效时间。性别、生日、入职日期、职级等只取可可靠提供的数据；身份和联系方式使用受保护引用。
- `SynchronizeOrganizationFacts.facts`：完整组织快照，白名单为`name/organizationCode/nature/status/managedUnit/responsibilityKind/parentId/sourceRecordId`。`parentId`不得省略，null表示根，不能把缺失误解为解除父关系。来源系统由认证上下文确定。父节点尚未同步时记录`IssueForOrganization`，不创建虚构父组织；修复后用新来源版本重新关联。
- `SynchronizeAccountAuthority.facts`：`username/state/currentOrganizationId/personId/sourceRecordId`完整快照；`personId`可空，当前组织不明时禁止依赖当前组织的操作。不接收密码、会话、客户端角色或身份票据。
- `SynchronizeAccountAuthority.grants`：该来源对当前账号的完整授权数组，每项为`sourceRecordId/permission/scopeKind/organizationRootIds/validFrom/validTo`。空数组表示撤销该来源全部授权，字段缺失拒绝而不是沿用旧值。功能代码取受支持白名单；CUSTOM_ORGS至少一个有效根，CURRENT_ORG_AND_DESCENDANTS使用账号当前组织，ALL不接受客户端自行声明。失效日期不得早于生效日期。不同来源的记录不能相互覆盖；来源版本更新和授权替换原子提交。
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

组织、人员、账号授权各自保存来源版本和有效历史。迟到来源事件不能用记录时间覆盖更新的业务事实；历史更正由可信来源显式给出更正版本及生效时间，保留更正前后证据。来源序列不可比较时记录待核实，不能按版本字符串字典序猜先后。

统计HTTP过滤映射：organizationId=当前对象单位（含下级），creatorOrganization=可见任务创建单位，recipientOrganization=发送时单位，tagId=当前目录含下级，tagVersionId=末级标签历史版本，category/taskId/from/to用于提醒及对接；includeWithdrawn默认true保留撤回历史。对象/质量基数不受任务时间或标签版本影响。来源条数、人员标签项、账号数和任务人次分别标单位。

VersionTargetsRecipient.tagVersionIdsJson为该人当时有效标签定义版本ID数组；空数组是已确认无标签，缺字段为旧数据未知。它与ReminderTaskVersion的标签版本关联及冻结名单同时提交，不接收统计客户端改写。
