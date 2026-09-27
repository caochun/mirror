# 两份原始文档的业务覆盖与实现边界

定义基线：0.2.8。本包的核心是“人员、组织及履职事实 → 可解释的标签画像 → 人工选择与审核 → 精准提醒 → 送达、阅读和逾期闭环”。标签与提醒属于Mirror业务，Foundry提供通用对象、关系、历史、事务及事件能力。

最新逐条复核见[业务需求对照报告](../business-spec/business-requirements-comparison.md)。下表表示主要概念已有对应，不表示全部动作和细节已闭合；尤其专项来源绑定、AI配置生命周期和原始提醒重点仍有缺口。

## 业务覆盖

| 业务要求与来源 | 对象及关系 | 动作与验收重点 |
| --- | --- | --- |
| 全员建档、动态更新：试点三（一）2/5；需求3.1 | Person、PersonProfile、ExternalIdentity、ObjectEligibility、DataAssociationIssue | SynchronizePersonFacts、ApplySourceLifecycle、SetObjectEligibility；一人一档，缺档案仍留库，局部缺字段不影响其他规则，停用不删历史 |
| 独立组织、岗位、任职：试点附件1；需求2.2/3.1 | Organization、Position、Assignment；OrganizationParent、AssignmentUsesPosition | SynchronizeOrganizationFacts独立维护组织投影；任职事实由人员同步更新。当前单位唯一，兼任可多个；调动与组织树调整分别留历史 |
| 分级分工与独立审核：试点三（一）1/四；需求2.2 | UserAccount、AuthorityGrant；AccountCurrentOrganization、GrantScopeRoot | SynchronizeAccountAuthority接收可信授权；组织范围与功能权限共同约束。审核只限本级且不能自审；撤销权限对已有会话生效 |
| 三级目录和动态画像：试点附件4；需求3.2 | TagDefinition/TagVersion、规则/映射/AI策略版本、PersonTagAssignment、TagContribution | 目录配置、映射预览确认、规则预览发布/独立停用、规则批次、人工增删恢复、AI建议复核；多来源独立保留，人工抑制优先；候选不等于有效标签 |
| 因事阶段与风险：试点三（二）/附件4（二） | SupervisionMatter、MatterStage、MatterParticipation、RiskEvent及贡献依据 | 登记事项/参与/风险、启动或结束阶段、退出参与、赋专项标签；结束A不影响B；风险线索不是违纪结论 |
| 内容示例和任务内容：需求3.3 | ReminderContent、ContentVersion、MediaAsset、ContentSafetyCheck、MediaConfirmation | 示例版本/启停/引用保护、受控图文、逐图确认；任务副本独立；已使用内容和媒体保留 |
| 名单、确认、审核和定时：需求3.4.1—3.4.7 | RecipientSelection、SelectionEntry、ReminderTask/Version、ReviewRound、ReminderSendJob | 组织并集与标签ANY/ALL、指定叠加、持久排除、二次确认；提交冻结，独立本级审核，过期不补发，单次定时可取消 |
| 送达、阅读、修订、撤回：试点三（三）；需求3.4.8/3.5 | 稳定RecipientRecord、DeliveryAttempt/Receipt、RecipientVersionState、ReadReceipt、WithdrawalRecord | 已提交不等于送达；阅读独立；仅失败/未知重试；新版重新审核、原名单和期限不变；撤回保留历史及逐人结果 |
| 逾期督促与运营统计：需求3.5.4/3.6 | OverdueRecord、IntegrationIssue及指标契约 | 真实送达起算、真实新版阅读解除；按当前主管范围督促，不能代读；人数与人次、问题数与人数、送达率与阅读率分开 |
| AI辅助查询：需求3.7/9.1 | AssistantQuery、AssistantActionCard | 仅授权超级管理员查询；回显条件、来源、口径；动作卡只进入人工流程，不批准、不直接发送 |

每条功能需求到动作/规则的映射见`contracts/coverage.yaml`：39个需求书功能编号，另列试点专项、风险、责任分工和审计。动作的完整参数、权限、前置条件、效果及幂等见`contracts/actions.yaml`与`action-types.odl`。`scenarios.yaml`给出跨业务域的32项验收规格。

## 文档差异如何处理

| 差异 | 当前业务定义 |
| --- | --- |
| 试点要求阶段性专项，需求书首期仅长期目录 | 两者均定义；专项标为pilot-extension，不能以“首期”名义从模型中删除，也不宣称已实现 |
| 试点示例按年龄推算退休过渡期，需求书说明数据不足 | 新提拔和退休过渡期采用人工维护，不推算、不自动到期、不送AI判断 |
| 试点由鹿路通留阅读痕迹，细化需求使用本系统H5 | 保留渠道送达和本人阅读两类业务事实；具体适配不固化到人员身份模型 |
| 逾期短信要求冲突 | 3.5.1不建设短信，5.2第7条却写“通过短息接口发送提醒”；此前判定无此条款错误。当前暂不自动短信，业务分歧尚未解决 |
| 非对象标记的权限矩阵与正文不一致 | 独立权限，默认超级管理员；下放待确认 |
| 阅读率分母未完全明确 | 送达者阅读率与全目标阅读覆盖率分列，并标明最新版本及人数/人次 |
| 规则曾命中、后来字段缺失如何处理未明确 | 当前暂停该规则贡献并记录UNKNOWN，保留其他有效来源；作为显式业务假设管理 |
| 文档假设既有明镜技术栈 | 只保留权威身份/组织/档案来源与权限业务规则；来源由适配提供，可以是新建系统，不要求旧平台存在 |

未决项是显式业务假设，不是暗中删掉需求。原始来源摘要及复核记录见`../business-spec/domain-pack-review.md`。

## Foundry与Mirror的责任

- **Foundry**：管理对象及关系定义、状态事实及双时间历史、并发版本、事务、审计与outbox，以及存储提供方。国产关系数据库由对应Provider和数据库验收支撑，业务Pack不绑定某种表结构。
- **Mirror业务处理器**：解释本包的授权、唯一性、状态前置条件、规则评估、独立审核和指标口径。`String`状态字段不自动成为状态机；声明规则不等于执行校验。
- **接口与应用**：接入权威来源、AI和鹿路通，提供管理端及本人接收端。本人只能访问本次提醒与受控图片，是业务边界；采用何种票据协议、服务器或路由由实现决定。

试点附件列出的全部行权监督模型、家庭/教育档案的完整编辑系统、案件办理、廉洁指数排名，不因被引用就变成本包需重建的业务。风险来源和档案引用仍须可追溯。

性能、可用性、国产库适配、恢复和外部联调保留为应用验收要求，见coverage中的nonObjectRequirements。当前`actions: []`保留不完整动作不可执行的边界；71个契约不能冒充71个可调用API。现有接通范围见`../business-spec/runtime-contracts.md`。
