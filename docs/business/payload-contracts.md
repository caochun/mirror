# 配置、证据与跨对象约束

本文件描述Mirror服务需验证的结构，Foundry不会自动执行这些JSON/跨对象规则。服务拒绝未知字段，校验类型、大小、身份、权限、引用、版本与摘要；复合key由服务按规范JSON数组哈希等无歧义方法生成。

## 对象关系约束

| 对象/关系 | 身份与一致性要求 |
| --- | --- |
| ObjectMembership | MembershipPerson恰好一个且一人一条；status+decisionCode+decidedBy/At是准入决定，证据引用和决定组织一起更新；缺记录视为待确认 |
| ExternalIdentity | identityKey为来源+身份标识的规范身份；IdentityPerson恰好一个；技术账号不进入本体；更正/重新关联必须审计，来源状态不直接决定Membership |
| Appointment | Person/Organization/Position各一个，结束不早于开始；任职角色/管理范围字段须有来源，个人职级不混入岗位等级 |
| 发布版本与当前版本关系 | versionKey=根ID+revision，各VersionOf恰好一个；TagCurrentVersion/PolicyCurrentVersion/ContentCurrentVersion最多一个，服务验证同根，发布后版本内容和关系集合都冻结 |
| PersonTag | pairKey=personId+tagId，Person/Tag各一个；suppression只表示人工抑制，不表示派生有效性；末级来自树 |
| TagContribution | key=逻辑标签+来源稳定身份；ForPersonTag/TagVersion各一个，kind对应Policy/Evaluation/Suggestion/Participation/Risk依据；人工恢复新增贡献、原证据保留 |
| TagEvaluation / Suggestion | resultKey=批次+人员+策略版本，三关系各一个；建议须属于该策略候选，接受时重新检查输入/策略/权限；结果、说明、时间为一次评估证据，失败重评另记业务评估，不覆写先前输入 |
| ReminderTask | lifecycle不代替审核/发送/撤回；TaskCreatedIn在提交时固定，之后不能随操作者切换单位；TaskPublishedVersion/TaskWorkingVersion分别最多一个且同任务 |
| AudienceSnapshot | snapshotKey为任务+名单修订标识；AudienceTask一个，参数/成员冻结。TaskApprovedAudience首轮通过时选择且之后不可换绑 |
| ReminderVersion | versionKey=任务+revision；ReminderVersionOf一个；已提交版本的VersionAudience一个且同任务。首次批准后所有内容修订复用TaskApprovedAudience，不创建替代名单 |
| TaskRecipient | recipientKey=任务+人员，两个关系各一个；只有批准AudienceSnapshot中的成员有正式发送资格 |
| RecipientSnapshot | snapshotKey=audienceId+recipientId，SnapshotAudience/Recipient/Organization各一个且同任务，保存当时姓名/组织；内容修订不复制本对象 |
| ReviewRound | roundKey=版本+轮次；ReviewVersion一个；摘要覆盖内容、媒体成员及哈希、名单ID/成员摘要、发送方式/时间/期限、安全确认；审核者独立且同创建单位 |
| DeliveryAttempt / Receipt | 稳定业务请求ID / 渠道事件ID；Attempt的Recipient/Version各一个且同任务；网络重传复用请求；回执关联原尝试，迟到失败不降级成功 |
| ReadReceipt | readKey=接收人+内容版本；关系同任务，新增时必须当前发布版本且本人有效身份；服务禁止代读/覆盖首次时间 |
| Withdrawal / Attempt / Receipt | 每接收人撤回意图独立；尝试固定目标业务请求集合；每次回执留证；意图并不意味着外部已撤回 |
| OverdueEpisode | key=接收人+内容版本；已超冻结期限且当前版未读；记录产生时主管单位，当前清单用当前组织权限 |

关系的最大基数不能代替最少成员、同根、状态检查；`@immutable`属性也不会冻结所有连接关系。模型测试使用的fixture不是完整业务处理器。

## 权威引用、快照与查询索引

- 当前版本、对象归属和快照成员的权威连接使用ODL关系，不再另存可独立修改的版本ID或旧快照关系。
- 策略DSL中带条件的映射（例如“组织A→标签版本X”）以不可变specification为权威。PolicyAppliesToOrganization/PolicyTargetsTagVersion是由发布服务同事务提取的引用索引，不能另行编辑或作为第二份配置。冻结后两者不得各自改动。
- 批次selection保存请求范围，BatchPolicyVersion是其发布配置引用索引；提交时必须精确一致。
- RecipientSnapshot.selectionBasis中的贡献/标签版本引用是不可变选人证据；SnapshotTagVersion是服务提取的同一证据索引，不能悄悄补上今天的标签。
- 图文body中的mediaId/链接是正文事实。ContentUsesMedia/ReminderUsesMedia是精确媒体引用集合，安全证据links是校验结果，不能当作另一份正文链接列表。
- 名单selection是历史输入条件，最终接收成员以SnapshotAudience集合为准，不能在发送时重新执行selection。

## JSON字段契约

| 字段 | 结构与限制 |
| --- | --- |
| ProfileReference.projection | `{schemaVersion,sourceRevision,observedAt,fields}`；来源白名单投影与Person规范字段间注明采用权威，不存在两个独立可编辑的生日/归属 |
| TagPolicyVersion.specification（RULE） | `{schemaVersion:1,kind:"RULE",predicate:{all:[...]或any:[...]}}`；仅白名单字段、eq/in/年龄日期范围；禁止SQL/脚本/客户端任意路径 |
| specification（ORGANIZATION_MAPPING） | `{schemaVersion:1,kind,entries:[{organizationId,tagVersionId}],inherit:"NEAREST_CONFIGURED_ANCESTOR"}`；字段为结构化映射事实，服务派生对应引用索引 |
| specification（POSITION_MAPPING） | `{schemaVersion:1,kind,tiers:[{tagVersionId,priority,categories,personalRankCodes,positionGradeCodes,appointmentRoleCodes,managementScopeCodes}],domains:[...]}`；个人与岗位等级不能不加区分比较，未知值不推断真/假 |
| specification（AI） | `{schemaVersion:1,kind,candidateVersionIds,promptText,inputFields,modelProfileRef}`；整个PolicyVersion定义提示词/候选版本身份，不重复一份可变enabled或promptVersion；凭证不入本体 |
| EvaluationBatch.selection | `{schemaVersion:1,organizationRootIds,personIds,policyVersionIds,asOf,sourceRevisionDigest}`；明确授权及范围；统计由评估项计算，不存另一份summary权威结果 |
| TagEvaluation.inputs | `{schemaVersion:1,personVersion,membershipVersion,organizationVersion,appointmentVersions,fields,evaluationDate}`；输入最小化且敏感，模型/来源版本纳入摘要 |
| ContentVersion.body / ReminderVersion.body | `{schemaVersion:1,blocks:[{type,...}]}`；paragraph/heading/list/image；文字、允许格式、链接、mediaId；不是任意HTML |
| AudienceSnapshot.selection | `{schemaVersion:1,organizationRootIds,tagIds,tagOperator:"ANY"或"ALL",specifiedPersonIds,excludedPersonIds}`；保留确认条件，最终人数以冻结名单成员计算 |
| ReminderVersion.safetyEvidence | `{schemaVersion:1,contentHash,audienceSnapshotId,selectionHash,confirmedBy,confirmedAt,images:[{mediaId,contentHash,confirmed}],links:[{url,domainPolicyVersion,accepted}],duplicateWarningAcknowledged}`；必须绑定VersionAudience和当前正文所有媒体 |
| RecipientSnapshot.selectionBasis | `{schemaVersion:1,origins:["FILTER","SPECIFIED"],tagVersionIds,contributionRefs,capturedPersonVersion}`；引用真实依据，不向H5公开 |

## 草稿与发布责任

Mirror持久配置工作区承载标签/策略/示例草稿和选人草稿，保存draftId、owner、expectedRevision、basePublishedVersion、候选内容/关系、预览摘要及确认；保留业务审计，过期/回收按保留政策。它不因不在Pack里就变成未持久化临时变量。未发布新条目的目录/示例列表由服务合并工作区和发布模型展示。

发布时先确认权限、预览是否过期及基础版本，再同一Foundry事务创建不可变语义版本/关系、切换当前版本关系、写回执/审计/outbox；工作区标记已发布不是第二次发布依据，失败重试通过原命令回执关联发布结果。若工作区另库，不能假设它和Foundry自然构成跨库事务。

ReminderVersion本身是任务内容草稿。选人草稿不直接就是正式名单；提交时原子创建AudienceSnapshot/成员，将内容设FROZEN并建立VersionAudience/ReviewRound。调整待审内容须撤回本轮审核并产生新的内容草稿；旧冻结版本不解冻。初次审核未通过前可产生新名单快照；首次通过后TaskApprovedAudience固定，修订只改内容。

## 状态与统计

任务lifecycle只有OPEN/CANCELLED/CLOSED；审核状态从ReviewRound读取，当前发布从TaskPublishedVersion读取，发送从逐人回执汇总，撤回从各意图/结果汇总。TaskWorkingVersion能与已发布版本并存，无待处理修订时可缺失。“部分成功”等展示状态不回写成覆盖所有维度的大枚举。

成员准入、组织可用性、目录启用/末级、已发布版本、适用期内ACTIVE贡献和suppression=NONE共同决定标签当前有效性。目录关闭只改变可用性；重新启用不得直接激活因停用结束的旧自动贡献，需业务重算；人工抑制始终独立。来源不明或状态UNKNOWN的默认处置见待确认清单。

deadlineHours只允许24/48/72/168；时间存UTC、展示用业务时区；发布修订不更新原截止。名单快照里的原计划时间仅说明首次发送安排，不作为修订审核“过期”的判断条件。

对象统计按Membership.IN_SCOPE与业务有效范围去重；技术/非对象账号统计来自身份/接入服务，不通过造Person凑数。来源分布可重叠，不能相加当总覆盖。标签覆盖、批次汇总、任务综合状态、投递计数及阅读率都是可重建查询投影。

提醒人数按task+person去重，跨任务区分人数与人次；历史归属/标签来自批准名单，未读督促按当前组织。阅读采用当前发布版，送达与阅读独立。成功撤回后的阅读率分母仍待确认，应显示原名单、撤回数及采用口径，不暗改历史分母。
