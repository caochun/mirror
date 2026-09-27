# 从0.1.0到0.2.0的定义差异

此文档是迁移设计，不是已运行迁移脚本。pack和schema版本共同升到0.2.0；现有应用自动加载ODL不代表已完成数据迁移。

1. 保留已有对象名称与未提交提醒字段，新增business-evidence.odl。技术命令回执和锁移到runtime-support.odl，类型名未变。
2. PersonHoldsPosition、AssignmentOnProject、ContentSuggestsTag、VersionUsesMedia、RiskInvolvesPerson、BatchAffectsTag放宽为多对多。岗位任职以AssignmentUsesPosition为权威，PersonHoldsPosition仅当前摘要投影，两者更新需一致。
3. TagCandidateForPerson改为多对一。一条旧候选若连多个人员，要拆成每人员候选并保留原ID引用，不能丢弃其他关联。IssueForTag改为多对一，新增无需成功赋标即可存在的人员/规则/评估/候选异常关系。
4. 标签定义parentId、规则tagDefinitionId等字符串是已有查询投影；对应新增关系为领域关联。迁移要建立关系并检查二者一致，不同时接受两套独立写入。发布版本需补齐当时完整配置摘要；无法恢复时标为未知，不拿当前配置冒充历史。
5. 每人员+标签合并为唯一逻辑记录，来源拆到TagContribution；已有source/sourceReference/sourceOrganizationId保留为兼容摘要，完整来源以贡献为准。旧REMOVED映射SUPPRESSED；任何来源上的有效人工删除先保留为整体抑制，待人工确认解除。
6. RecipientRecord以tenant+taskId+personId唯一；taskVersionId/state/sentAt保留为旧投影且taskVersionId/state变为可选。旧sentAt只有确认是首次成功送达才迁到firstDeliveredAt。多版本接收记录应合并稳定ID，同时保留旧ID映射用于外部回执匹配，不能丢失尝试/回执/阅读/撤回。
7. VersionTargetsRecipient从一对多改为多对多，每个版本引用同一稳定名单。ReadReceipt关联原内容版本，RecipientVersionState保存版本阅读投影；deadlineAt按最初确认时限保留，不以新版本创建时间重算。
8. TaskHasRecipient、RecipientForPerson为稳定关系；任务currentPublishedVersionId、pendingVersionId分别指当前发布与待审。旧snapshotId不能直接同时充当两者；根据审核/发布证据判定，证据不足标为待核实。
9. ReviewRound及ContentSafetyCheck/MediaConfirmation需要保留每轮审核及确认。无法补足的历史仅作为遗留审计记录，不伪造审核/逐图确认人。
10. AuthorityGrant为业务授权事实契约；现有mirror_accounts/权限表可作适配来源，不自动复制密码至UserAccount，不允许客户端改角色扩权。
11. 旧TransferAssignment未包含完整校验且now为字面量，已退出执行注册。新的权威来源同步通过SynchronizePersonFacts契约执行，所有Handler需单独落实后才可开放。

迁移后应验证：关系端点/唯一键一致、旧时间和外部ID可追溯、人工抑制未丢失、原接收总数不因版本膨胀、原截止时间不延长、未授权操作者不能通过新对象关系获取旧数据。

提醒处理器接入时新增了可选的selectionId、draftSequence、contentCheckId、activeReviewId、criteriaJson、entriesJson等查找投影，以及ReviewRound的taskId/versionId/organizationId投影。它们与对应关系、冻结版本保持一致；未审草稿可以新建版本，但旧冻结版本不覆盖。旧任务缺少这些依据时返回需要核实迁移，不把旧原型自动包装成已审核任务。历次驳回/撤回轮次的接收记录可保留，发送与目标人数应使用当前有效审核/发布版本的VersionTargetsRecipient成员，而不是历史接收记录并集。

## 0.2.1 → 0.2.2

- 新增IssueForOrganization关系，允许尚无人员的组织独立记录来源异常；不更改现有关系基数。
- Person、Organization、UserAccount、AuthorityGrant补充可选来源版本/观察时间等字段。旧记录允许空，必须从来源核实后补齐，不用迁移时间冒充业务观察时间。
- 新增SynchronizeOrganizationFacts与SynchronizeAccountAuthority业务契约，不注册为Foundry YAML可执行动作，也不自动替换当前账号数据库。
- 新增组织/授权/专项参与状态词表，完善参与退出、修订冻结及迟到回执迁移。处理器仍需实现对应前置条件，不能把状态文件更新视为已有数据状态已转换。
- tagPending保留PERSON_TAG粒度并显式返回affectedPeople人数。消费方须标清单位，不能将问题项计数显示为人数。

这是增量定义修订，没有执行运行库迁移；原0.1.0升级仍须遵循上文历史/接收记录语义迁移要求。

发送处理器接入后新增可选作业租约、重试名单、渠道模式和最新尝试ID投影，以及尝试的版本/受保护身份引用。旧原型缺少可信独立审核快照的发送作业不会被新worker执行；不能从旧sentAt猜测送达。V5新增Mock请求账本和功能权限，运行环境应始终区分模拟记录与真实送达。

## 0.2.2 → 0.2.3

新增ReminderTask.revisionState（缺省NONE）和ReminderTaskVersion.basePublishedVersionId可选字段。已发送任务修订不重置主任务状态，不更换接收记录。新增SaveReminderRevision用于保存可独立确认的修订草稿，再由既有SubmitReminderRevision冻结提交。批准与发布分为两个持久事务；发布按内容版本幂等。旧冻结版本和已读/逾期历史继续保留，旧发送作业恢复不得覆盖最新发布指针。

## 0.2.3 → 0.2.4

新增撤回派发契约及WithdrawalRecord状态模型；接收记录增加latestWithdrawalId/withdrawnAt，撤回记录增加任务/版本/原发送请求/渠道/租约及最近原始回执投影。缺历史渠道或请求标识的旧尝试拒绝自动撤回，不能猜测第三方标识。撤回以原完整名单汇总，并结束未发布修订；失败/未知仍可正常阅读和产生逾期。V7只新增Mock账本/权限及旧账本可空错误码，未执行任何真实渠道迁移。新元数据字段与对象历史一起保存，不物理删除原发送/阅读/审核记录。

## 0.2.4 → 0.2.5

VersionTargetsRecipient新增可选tagVersionIdsJson，固定每名接收人在提交时的标签版本；人员选择快照也带该证据，修订复制原关系属性。旧记录缺失表示未知，不能用当前标签回填；新增记录以空数组明确“当时无标签”。保存中的旧确认可能因版本依据变化而失效，须重新保存并核对。

统计增加明确的当前单位/发送时单位、目录/末级历史版本、任务/事件时间和撤回历史口径。大屏不再使用硬编码演示数；原Mock业务记录仍按来源标识，不能因为切换配置而改变性质。缓存属于短期查询投影，不迁移为领域事实。

## 0.2.5 → 0.2.6

补充CreateTagRule，允许先登记未启用规则再预览/发布，修正原PreviewRuleChange必须已有规则却缺建立入口的契约缺口。新增RuleProcessingCursor技术索引；规则、预览、批次、评估与贡献添加处理/关联投影，Position添加可选standardCode。新字段不猜测旧自由文本岗位的标准含义。

TagEvaluation增加SKIPPED，表示对象资格或配置已不适用而跳过，不冒充规则结论。规则UNKNOWN暂停本来源并保留原因，其他来源和人工抑制不被更改。未能可靠识别的旧来源不当成当前规则所有，需先治理；本次未运行生产迁移。

## 0.2.6 → 0.2.7

新增MappingImpactPreview及三个证据关系，不改变已有关系基数。PublishClassificationMapping改为绑定预览ID、预期版本和确认摘要，原直接提交配置的定义签名不再有效；该动作尚无运行处理器，因此不提供旧签名兼容执行入口。历史映射版本及EvaluationUsesMapping证据保留，不伪造旧预览。

新增DeactivateTagRule契约、TagRule/ClassificationMapping/MappingImpactPreview状态及相关贡献失效迁移。现有数据不因修改状态词表而自动停用；实际接通时需验证停用与在途批次隔离和中断恢复。

## 0.2.7 → 0.2.8

TagRule补充停用时点/批次投影；TagBatch补充停用规则、冻结贡献/问题ID；TagProcessingIssue补充ruleVersionId。已停止的规则立即不作为当前有效来源，历史状态仍保留，后台逐项结束贡献和局部问题。规则清理评价使用SKIPPED说明停止应用，不假装未命中。再次启用发布新规则版本，旧清理不修改新来源；目录重新启用不会恢复旧规则。旧贡献缺ruleId的兼容记录仍需人工核实来源，不能猜测归属后批量删除。

## 0.2.8 → 0.2.9

显式声明原已写入的Person.title可选只读摘要。Foundry从本阶段起校验所有新建/更新的声明属性、必填、类型、唯一和不可变约束。已有缺字段/未知属性的记录不被自动修正；新更新须满足完整模型，治理需有来源依据。当前演示初始化补OrganizationParent.startedAt，规则赋标补PersonHasTag.linkedAt；不能用这些代码补丁伪造既有行的历史业务日期。

DateTime输入可为Instant或ISO字符串，统一保存/返回ISO字符串。原型消费方已兼容该表示；时间生效语义仍按Foundry的独立effectiveAt接口处理，不从业务属性名字推断生效时间。升级前应先在副本核对当前数据是否符合声明；本轮没有运行生产回填。
