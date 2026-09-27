# Pack 契约到运行实现的登记

当前定义基线：0.2.7。这里区分“定义存在”和“处理器已接入”，不是生产完成清单。

0.2.2新增的SynchronizeOrganizationFacts、SynchronizeAccountAuthority目前仅有业务签名、规则和验收规格，未登记处理器。新增专项/回执状态迁移须逐一接入，下文记录已完成的发送子集。

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

它们覆盖文字/列表/可信链接和受控图片；ConfirmReminderContent已校验当前全部图片确认并保存逐图证据。发送审批创建QUEUED作业，DispatchReminder已由下述worker登记；真实渠道尚未接通。到期作业由服务端内部上下文触发，没有面向用户的系统权限绕过入口。

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
- 确定性规则已按下文接通，并有自动重算保持人工抑制的浏览器验收；AI评估/复核处理器仍未接通。

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

## 持久发送和Mock渠道

DispatchReminder、RecordDeliveryReceipt和RetryFailedRecipients由DeliveryService接入。调度器领取到期作业，核对对应版本的独立审核及摘要，以当前批准版本的VersionTargetsRecipient为名单。单次最多500项，事务持久化尝试后再调用渠道；租约恢复重放相同请求标识，Mock渠道以数据库请求账本返回最初结果与时刻。

真实人员身份不会因使用Mock自动成功；仅`mock:`演示身份返回模拟送达。默认delivery-mode=disabled，不创建虚假成功。示例Mock立即回执，但业务接口允许FAILED/UNKNOWN及可信迟到回执；旧尝试失败不能覆盖新尝试结果，迟到成功可修正任务汇总，首次送达时间只允许用更早可信证据修正，绝不延长截止。

内部动作有独立白名单，不允许账号调用；重试每次重新检查功能权限、当前创建单位和任务归属。结果查询需要REMINDER_READ及OVERDUE_READ（阅读/逾期查看），审核员只有审核快照权限，不能查看逐人阅读结果。Mock手动执行仅在demo=true且mode=mock时开放，并需REMINDER_WRITE；页面明确模拟状态。

新增投影字段：作业leaseOwner/leaseUntil/recipientIdsJson、接收记录channelMode/latestAttemptId、尝试taskVersionId/channelMode/受保护identityReference、回执channelMode。它们与已有对象关系一致；没有把发送状态写成阅读状态。HTTP提供任务delivery、retry和受控mock-dispatch，React显示逐人送达/阅读/首次成功/截止及失败重试。

H5本人阅读已由下述处理器接通；发送不会自动产生READ。正式渠道幂等/回执签名、修订/撤回、正式身份适配和50k查询性能仍须后续实现与验证。

## 接收端、首次阅读与逾期

ReadOwnReminder、RecordFirstRead由ReceiverService/ReceiverSessions和ReadingService接入；EvaluateOverdue、ReadOverdueList由ReadingService接入；RecordIntegrationIssue由受校验的接收端异常路径接入。DomainContracts区分内部调度/适配动作、本人动作和管理账号动作；账号不能直接调用本人写入来代读。

Mock入口只允许demo+mock、任务创建单位且有REMINDER_WRITE的操作者，为mock人员签发120秒一次性随机票据。数据库只保存票据摘要，绑定HttpSession ID；交换后每条提醒获得独立15分钟授权，保存在该服务端会话中。登录轮换会话ID、退出、账号停用/上下文变更或权限撤销使授权失效。票据置于URL fragment，避免明文人员标识及票据进入请求URL；每次内容/图片/阅读请求仍校验授权和当前发布版本。真实外部身份入口尚不存在，这不是生产鹿路通免登实现。

正文响应不带人员名单、标签、审核、风险或联系方式；图片仅允许当前版本的TaskVersionUsesMedia成员，响应no-store。服务端返回5分钟渲染确认随机值，绑定当前版本和内容摘要；前端正文DOM挂载后提交bodyRendered确认。该协议证明已授权正文曾被服务端返回且客户端确认显示，不证明人的注意力或学习程度，不采集停留/滚动/点击。单张图片失败不阻断正文阅读，正文失败不发阅读请求；异常只接收页面/正文/图片类别，每会话每版本每类/媒体去重。

首次阅读按recipient+version唯一，通过同一Foundry事务保存ReadReceipt、RecipientVersionState、逾期解除及审计/outbox，重试不覆盖第一次时间。渠道未确认时阅读仍有效，同时创建READ_WITHOUT_DELIVERY异常；可信迟到成功在回执事务中解除该异常，保留原证据。

读取/诊断等幂等处理器通过executeDefinedWithRetry对明确的事务竞争最多执行5次；身份、当前版本或业务规则拒绝不在重试范围内。每次都重新执行授权，外部调用不在此重试体中。浏览器图片失败与正文阅读并发已覆盖，避免将可恢复的锁竞争展示成永久阅读失败。

逾期扫描只处理当前发布名单、有真实成功起点和已到截止且当前版本未读的记录。预警产生时主管组织保存为历史，查询与下钻范围按人员当前组织重新判定。ReadOverdueList写访问审计，分条数/人数，按截止排序和分页；没有手机号标为空缺状态，受保护引用不直接当作号码输出。当前查询仍使用完整SPI分页后过滤，尚未达到5万级性能验收；正式联系方式解析、接收会话多节点存储及真实身份对接仍待完成。

## 已发布提醒修订

Pack 0.2.3新增SaveReminderRevision，解决“提交修订需先有版本和安全确认”的准备契约。ReminderRevisionService接通保存、ConfirmReminderContent、SubmitReminderRevision、DecideReminderReview、WithdrawReminderReview和PublishReminderRevision。修订使用独立revisionState，原主任务送达状态不退回草稿；角色和当前创建单位在每次命令前重验。

只接受标题/正文（含受控图片及链接），显式拒绝额外期限/名单字段；复制基准发布版本的冻结名单、时限、分类和来源/标签依据，提交和发布再次校验基准及成员集合。每次编辑产生新版本并清除内容确认，逐图确认绑定摘要。待审修订不允许直接编辑，自审或非本级审核拒绝；驳回/撤回保留旧轮次，重新保存形成新修订。

审批先持久化APPROVED，HTTP响应不等待全名单状态重置。专用后台执行器触发发布，定时扫描恢复中断或队列满时的批准记录。发布按版本幂等，将旧版SUPERSEDED、新版PUBLISHED、最新指针和每人新版UNREAD原子提交，旧阅读保持审计，旧OPEN逾期以SUPERSEDED原因关闭并按原截止重新评估新版。任务原计划时间不用于修订审核过期判定。

发送中也可修订；原发送作业恢复仍使用自己的批准快照，允许其版本已SUPERSEDED，但不能覆盖最新发布指针。成功通知不重发，尚未成功接收人的第一次实际成功仍决定其原阅读起点。legacy snapshotId保留原始发送准备引用，当前内容及修订一律使用currentPublishedVersionId/pendingVersionId。

前端新增独立内容修订页和确认/复审入口；审核工作台把修订待审纳入待审筛选。本人H5只取最新发布版，旧渲染确认不能把新版本标为已读。逐人撤回由下述独立处理器接通，撤回审核与撤回已发送消息仍是不同操作。

## 逐人撤回与在途取消

RequestReminderWithdrawal、DispatchReminderWithdrawal、RecordWithdrawalResult、RetryWithdrawal由WithdrawalService接通，Pack 0.2.4新增撤回派发定义。请求必须有REMINDER_WITHDRAW、当前任务操作权限、非空原因及原名单内的全部目标；逐项验证后整批提交意图。仅最新失败/未知撤回可重试，成功者不可重复申请。请求会结束未发布修订的准备/审核/发布，保留原已批准审核事实，避免后台发布与撤回冲突。迟到成功使全任务撤回时，也会终止其间新产生的待审/待发布修订。

每人每次撤回独立WithdrawalRecord，固定所有已登记发送请求标识。持久租约及后台执行器/调度器执行，数据库事务外调用渠道；恢复复用同一请求，结果未知不伪报成功。从未登记发送者本地取消，代码阻止所选人员后续登记新发送；其他未选人员继续原作业。所有目标成功撤回才为WITHDRAWN，部分目标成功为PARTIAL_WITHDRAWN；汇总分母是原完整名单，不是本次勾选人数。

Mock发送与撤回在数据库中按接收记录串行处理，保存取消屏障和请求结果。即使撤回先于对应发送到达，后续发送也返回MOCK_WITHDRAWN失败；已经成功过的发送结果仍作为历史事实保留。尚未确认的撤回不关闭正文或解除逾期；成功后拒绝H5/图片/新阅读，关闭OPEN逾期并注明WITHDRAWN，不冒充已读。

有效撤回结果不被迟到失败降级，但lastResultState/lastResultAt/lastEventId/lastErrorCode记录每个新响应，底座历史保留所有回执。迟到成功可使排队重试无需再次远程调用。HTTP提供申请、失败/未知重试及分页历史，结果读取仍需任务查看和阅读/逾期权限，审核员不能操作或查看逐人结果。

V7增加功能权限和Mock渠道锁/取消/撤回账本；旧发送账本结果兼容读取。真实接口能力与签名仍未交付，不能将Mock结果认作正式撤回；不能支持撤回的适配器明确返回失败，超时才作为未知。声明租约与Mock取消屏障不替代正式渠道幂等、取消及对账验收。大范围查询仍需要后续数据库投影优化。

## 业务指标与同快照下钻

QueryBusinessMetrics由BusinessMetricsService接入，启动时核对12个已登记指标。MetricsSnapshotReader是单独的只读JDBC投影适配器，按租户、业务类型白名单，在同一REPEATABLE_READ连接中批量读取当前对象和有效关系；不修改Foundry，不引入SQL/脚本输入，属性白名单排除身份证、手机号、档案原文、正文、提示词和凭据。它依赖当前Foundry JDBC表布局，Provider布局变化时须更新适配与测试。

统计与下钻仅返回明确DTO。METRICS_READ之外重新校验PERSON_READ/REMINDER_READ/OVERDUE_READ及当前账号，范围使用当前组织或授权根及下级。人员/标签按当前归属，提醒汇总限有权任务及发送时组织，逾期按当前主管范围；关联的可见创建单位可作为筛选，不能用组织/任务ID扩大范围。

对象基数不被标签选择改变；标签覆盖分子按所选目录含下级，目录汇总按人员去重，来源分布按贡献且另列人数，待处理总数按人员+标签去重、类别分布允许重叠。提醒时间以首次发布（批准待发送按创建），对接事件/重试用发生/申请时间。新建发送/撤回尝试计重试，不把同请求幂等恢复当新尝试。

目标按当前有效审批/发布版本的稳定接收成员去重，原驳回名单不计；可包括或排除撤回历史，逾期始终排除成功撤回。送达者阅读率仅计送达且已读，全目标覆盖率可包含有效已读但送达未知。首次成功依据或阅读状态缺失标为待核实，零分母为不适用。逾期由合法截止及当前版本未读事实判定，不等待预警扫描产生OverdueRecord后才计数。

为防止历史按当前标签漂移，选人摘要增加当时人员标签版本，提交时把逐人依据写入VersionTargetsRecipient.tagVersionIdsJson，同时关联TaskVersionUsesTagVersion；修订原样保留。父目录版本变化也使保存中的确认失效。旧关系无字段表示未知，不能拿今天的标签回填；空数组表示已确认当时无标签。标签目录采用当前树归类，历史版本筛选针对末级标签。

每次查询生成120秒、绑定完整账号上下文的不可猜测快照ID；缓存只保存已授权DTO和指标行，最多16份、合计200万行引用，超限拒绝要求缩小范围而不截断。下钻重新校验授权、当前归属及相关任务有效版本，版本切换后拒绝旧阅读快照。其他业务变化在同一版本内保留同次统计时点，处置需进入当前业务页面核对。缓存丢失或过期要求重新查询，不把缓存当业务事实。

前端工作台/独立大屏均使用API，静态演示数字已移除。数据模式按记录来源标识Mock/混合/普通；问题统计仅反映已入库事实，未产生评估记录不等于无问题。审核员默认工作台跳转本级审核列表。

## 确定性规则与持久批次

CreateTagRule、PreviewRuleChange、PublishRuleVersion、StartTagBatch、ApplyRuleEvaluation、CompleteTagBatch接入规则主链路。预览创建独立DRAFT RuleVersion与排队RuleImpactPreview，后台计算后保存可解释逐人结果、变更/未知/抑制分类及源数据摘要；变化或跨业务日时发布拒绝。人事数据和标准字段只读，缺失/不规范按规则局部UNKNOWN处理，无SQL/脚本/正则或关键词赋标。

发布创建持久RULE批次，冻结人员、规则版本及当前标签版本，后台每次最多100项并保存租约/游标。每项用当前输入重新计算，旧配置工作项SKIPPED；评估、对应规则贡献、逻辑标签、异常、索引、进度和审计/outbox同事务。只结束该规则的旧贡献；其他来源保留，SUPPRESSED不会被恢复。UNKNOWN明确暂停本规则依据，不假作NO_MATCH；无先前赋标也可创建独立待处理问题。

来源/配置版本扫描在输入变化后比较逐人规则摘要，仅安排变化人员；年龄/入职月数按业务日期检查，任职有效期相关条件按分钟触发检查，生日或任职边界不靠用户访问页面触发。索引RuleProcessingCursor是技术支持对象，实际评估历史仍不可变。当前实现先保证正确性，事实读取仍含逐人SPI调用，五万对象规则批次性能尚未验收。

规则页提供条件组编辑、全库影响预览与确认、批次进度和结果。批次成功计数包含MATCH及NO_MATCH，不等同于新增标签人数。全市配置预览仅超级管理员可用，人工维护标签拒绝规则；普通管理员不能发布配置或创建批次。映射读取支持最近上级性质继承、最高优先级职务和多岗位领域，但映射在线维护/发布、AI候选流程及完整规则停用管理仍待完成，不能以此阶段宣称整个标签中心完成。

0.2.7新增PreviewClassificationMapping、MappingImpactPreview与DeactivateTagRule，PublishClassificationMapping改为消费确认的预览。三种配置动作目前均未登记为可执行处理器，不能绕过预览直接发布。职务/领域映射的组织适用范围仍须在后续映射实现中落实和验证；当前读取能力不能宣称完整映射契约已实现。
