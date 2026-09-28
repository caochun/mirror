# Pack事务Action与Mirror流程编排

**Mirror流程不登记到Foundry。** Pack登记的是可执行的原子事实变更；Mirror编排这些Action、持久化流程进度、等待人工操作、执行外部I/O。当前不是完整业务应用，也不是所有事务边界都已实现。

## 已登记的八个Action

| Action | 权限 | 同一事务内的效果 | 可信宿主必须补充的校验 |
| --- | --- | --- | --- |
| `DecideObjectMembership` | `OBJECT_MEMBERSHIP_WRITE` | 原子写准入决定及操作组织；同状态也可更新依据。 | 真实actor当前组织=decisionOrganization、人员范围、来源权威及状态迁移权限；组织来自服务上下文而非信任请求。 |
| `TransferCurrentOrganization` | `PERSON_SOURCE_WRITE` | 在一个事务中结束旧归属边并创建新边；不推断岗位/标签。 | 仅可信主数据接入身份；核对source事件、当前关系版本/来源权威及两端访问，按源事件幂等键执行。 |
| `SuppressPersonTag` | `TAG_WRITE` | 仅改变人工抑制，保留所有来源贡献；原命令重放不产生新事实。 | 功能权限+当前人员组织范围+停用处理；操作单位记业务审计，不能把TAG_WRITE名称当授权实现。 |
| `ApplyManualTagContribution` | `TAG_WRITE` | 针对已有PersonTag，原子增加人工依据、关联版本/组织、解除抑制；旧抑制字段只留痕，是否抑制以suppression为准。 | 规范贡献身份与原命令绑定、操作组织/人员范围/当前组织可用性；首次不存在PersonTag的赋标须用另一个完整原子命令，不能分次提交。 |
| `RecordMatterParticipation` | `MATTER_WRITE` | 原子登记一次阶段参与及人员/阶段关系；不直接计算标签或发送。 | 事项及人员数据范围、角色真实性、同一业务参与去重；后续标签评估由已提交事件触发。 |
| `RecordRiskSignal` | `RISK_INGEST` | 原子登记未匹配风险及治理组织，不虚构人员、不形成违纪结论。 | 可信来源身份/事件、组织范围、敏感字段处理及时间/来源引用校验；sourceSystem不可由未认证回调伪造。 |
| `DecideReminderReview` | `REMINDER_REVIEW` | 只保存本轮独立审核决定；已批准事件驱动后续发布/派发，不把授权决定等同于发送。 | actor当前单位等于任务创建单位、有效独立审核员、快照摘要/媒体/名单/内容一致、本轮为当前待审轮次；发布需要重新核验该决定。 |
| `RecordFirstRead` | `RECEIVER_READ` | 原子保存当前版本首次阅读及其两端关系；不依赖送达状态。唯一readKey只防重复，命令回执才负责网络重放。 | 可信本人会话绑定recipient.person、已批准名单成员、正文渲染证据、规范(recipient,version) readKey/receiptId（均服务端生成）；不存在读取他人、旧内容或伪造hash即算已读的通道。 |

所有可执行定义在`schema/actions.odl`，每个Manifest一个文件。权限声明位于ODL的`@actionType(permission:...)`，YAML前置条件键为`expr`；不是之前示例中的Manifest顶层permission或expression。

## 不登记为Action的Mirror流程

| Mirror流程 | 编排/计算职责 | Foundry写入边界 |
| --- | --- | --- |
| 基础数据治理 | 来源认证、匹配、权威判断、异常处理与局部重算计划 | 已有对象的准入/组织调动Action；新增人员及任职完整写入边界待定义 |
| 人工赋标/恢复 | 选人、范围复验、确认、分项进度 | 已有PersonTag使用ApplyManualTagContribution原子写贡献并解除抑制；首次关联需要创建PersonTag及贡献的一体化Action，不能拆两次提交 |
| 规则/AI评估 | 计算、调用模型、版本复验、建议人工复核、局部批次 | 接受建议并写贡献、结束旧来源等须各自构成原子状态转换，不能空effects注册为已实现 |
| 专项事项管理 | 阶段推进、人员角色确认、风险匹配、结束贡献 | 已实现参与/未匹配风险登记；结束阶段与关联贡献的动态集合变更待定义 |
| 提醒准备 | 图文清洗、选人、排除、媒体/内容/人数确认 | 冻结内容+AudienceSnapshot/全名单+ReviewRound须一次原子发布，当前未注册此复合Action |
| 审核与发送 | 等待审核、处理决定、定时调度、渠道调用 | DecideReminderReview记录决定与通用完成事件；发布批准内容/固定名单/发送意图须另一个原子转换并重新检查审核证据，不能仅根据一个APPROVED字符串发消息 |
| 接收端阅读 | 本人票据、正文服务、渲染证据、接口幂等适配 | RecordFirstRead提交阅读事实；最新未读/逾期查询立即按ReadReceipt判断；OPEN逾期留档及无送达阅读的不一致DataIssue通过事件幂等收敛，若要求与阅读同事务须扩展该原子边界 |
| 撤回/回执/统计 | 实际外部请求、失败/未知重试、结果归并、同权限统计下钻 | 接受回执并更新当前投递事实、撤回成功及相关逾期变化须原子Action；当前尚未定义，不开放直接对象CRUD |

## 原子性与能力缺口

Foundry目前的ActionExecutor内置有限effects，不能注入任意Java Mirror Handler；Pack Loader还要求Action声明与Manifest逐一对应。它的executeBatch逐个执行Action，不是共享事务。不能把动态循环拆成多次调用后声称全部回滚，也不能用一个空Manifest代表复杂服务已接入。

对冻结大名单、首次赋标、结束阶段的多来源集合等必要事务，下一实施阶段先定义并测试完整原子Action：可静态表达的直接给Manifest；无法静态表达的，需要最小通用事务计划/受控效果扩展（解析后的类型化变更、同事务版本和授权复验、写审计/回执/outbox），或受限Java效果扩展。具体业务算法仍由Mirror计算，不能登记整个Mirror流程到Foundry。该扩展目前未实现，也未在本轮修改Foundry代码。

另一种可选设计是先分批准备不可见数据，最后一次原子激活批准指针；采用前必须证明准备态不会进入业务查询/投递，失败可恢复，不能当作现有Manifest已自然保证的语义。

规则求值、文本清洗和远程调用可以在事务前进行；它们生成的结果必须带输入版本和摘要，在写入Action事务内重新验证，禁止以客户端布尔值“已验证”替代实际校验。

## 执行与授权的真实边界

已登记Action默认不等于向HTTP开放。生产宿主须提供：注册Manifest不可替换、认证actor/tenant、非空幂等键、真实目标ID解析、expectedVersion/关系状态检查，以及可信ActionAuthorizer。所有用户上下文来自服务端；SYSTEM接入、管理员、审核员和本人会话采用不同权限策略。

Foundry默认ActionAuthorizer拒绝。通用ApplicationService检查Manifest注册和类型权限、解析对象，但其内置资源授权并不自动执行本级审核/本人绑定等Mirror规则；它会自行装配授权器，不能假定给底层ActionExecutor配置自定义策略就自然保留。Mirror须使用能注入业务策略的受控装配方式，或扩展通用授权组合入口后使用ApplicationService。测试用ActionExecutor+测试授权器验证机制，不是生产Mirror身份/组织授权实现。

业务约束应尽量在Manifest CEL中表达，比如真实关系路径、当前版本、当前目录版本、末级/准入和预期技术版本；不能表达的组织范围、规范key、媒体摘要、本人渲染证据由可信策略在事务内检查。参数中的organization、readKey等也不是调用者可自由选择的事实。

Action执行前/事务内/效果后及回执重放都会调用授权钩子。失败或拒绝不得提交部分effects。成功命令写通用审计、来源和outbox；命令回执仅在提供幂等键时产生。本应用要求所有变更使用稳定命令键。readKey的唯一约束仅防重复，换一个请求键撞到同一readKey应由受控入口返回原合法阅读证据，不能伪称唯一键自动实现成功重放。

## 审计和outbox不是业务流程注册

目前无sideEffects的Manifest成功产生的是`openfoundry.action.completed`，payload包含action/affected，未声明的ReminderApproved等领域主题不会自动出现。Mirror订阅器根据action及已提交事实生成本地作业，按事件ID去重；延迟消费必须核对事实是否仍适用、命令结果版本及取消/撤回屏障。需不可变外发载荷时应在相应Action中写发送意图或正式sideEffect快照，不在投递时随意读取最新版拼原任务。

通用成功审计记录actor/action/affected/事务，技术历史有状态；它不是完整业务审计（操作组织、拒绝原因、敏感查看、名单摘要、图片确认等）。失败/拒绝及读取审计尚需Mirror受控入口补齐；不把成功审计当成所有拒绝路径均自动留痕。

数据库事务提交后再投递outbox，至少一次而非远端恰好一次；远程成功但确认丢失仍可能重投，需稳定外部请求ID和对账。创建流程/任务本身无须在Foundry登记工作流；Foundry只看到受控的原子Action及其事实和事件。

## 验证范围

模型加载测试之外，DomainActionTest直接用真实CEL、真实Manifest和内存/JDBC-H2 Provider运行全部八个Action，验证默认拒绝、撤权重放、晚拒绝整体回滚、技术版本冲突、非法目录/版本引用、不同任务/旧版/撤回阅读拒绝、FAILED送达仍可阅读、审核独立性/过期、唯一阅读及每次成功仅一份审计/outbox。测试角色只验证授权钩子，并未完成Mirror真实组织/本人身份接入。
