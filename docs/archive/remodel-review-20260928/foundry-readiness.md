# 当前Foundry是否足够

更新：首个人员闭环已使用现有 Foundry JDBC 与 ActionExecutor 授权扩展落地，无须修改底座。当前实现及验证见[进度记录](../../development/progress.md)。下文保留重新建模阶段的核查背景与全域缺口，不将其中旧提交号、实验状态或历史测试数量视为当前运行状态。

结论：**当前Foundry足够承载37类对象/79类关系，也能执行本次14个原子Action；尚不能宣称所有Mirror业务事务都已可用。** 流程无需注册到Foundry；动态名单冻结等必须同事务的写入仍需补齐相应原子Action能力/设计，真实Mirror授权策略和外部集成也未实现。

本轮只核查/测试底座，没有修改Foundry实现来适配业务。当前子模块提交为`ef09ac3`；先前未提交的托管REST实验与范围调整仍在工作区，但新Pack及模型验证不依赖这些实验入口。旧完整重制goal保持暂停，不恢复。

## 实际验证

命令：

```bash
mvn -q -pl tests/model-verification -am test \
  -Dtest=DomainModelTest,DomainActionTest -Dsurefire.failIfNoSpecifiedTests=false
```

本次执行通过：**37项、0失败、0错误、0跳过**。包括19项模型验证和18项真实Action执行测试（内存/JDBC各9项）。37类对象、79类关系、14个Action均由当前Pack加载；执行覆盖成功/重放、默认拒绝/撤权、晚拒绝回滚、错误版本/关系/目录、FAILED送达仍可读、唯一阅读及审核过期/独立性。不是仅靠Manifest解析成功推断执行成功。

| 必要能力 | 代码/运行证据 | 结论与边界 |
| --- | --- | --- |
| Pack/ODL加载、Typed Action注册，跨文件对象与关系、枚举 | DomainPackLoader/SchemaCompiler，真实五文件Pack加载，含14个Typed Action及Manifest，Mirror流程未实现 | 满足定义承载需求；业务JSON结构由服务校验；14个原子Action有真实执行测试 |
| 身份、字段约束和复合键防重 | 真实PersonTag/TagVersion/ReadReceipt写入，唯一、枚举、不可变、错误版本拒绝 | 已有；复合key构造和端点一致仍由服务保证 |
| 当前组织唯一性及调动历史 | PersonCurrentOrganization双活动目标拒绝；结束旧边/建立新边；旧人员状态和旧关系历史可读 | 已有最大基数、对象/关系历史；缺少当前组织的异常态也能表达 |
| 原子事务/回滚 | 建人员后关联不存在组织失败，回滚后人员不存在 | 已有；生产数据库并发仍需真实验收 |
| 标签多来源与人工抑制 | 两个独立TagContribution，结束事项贡献不动规则贡献，PersonTag仍SUPPRESSED | 模型可表达；测试显式写fixture，不代表自动规则已实现 |
| 语义版本与冻结快照 | 不可变TagVersion不能改名；RecipientSnapshot保留原组织/姓名；旧ReadReceipt绑定旧版，任务指向新版 | 满足数据表达；ReminderVersion“提交后冻结”及关系成员保护必须由Mirror实施 |
| 租户与可信SPI边界 | 其他tenant读不到当前Person；本tenant可信SPI仍能读@sensitive属性且可无归属关系 | 租户隔离已有；组织范围/脱敏/最少关系数不是SPI自动执行功能 |
| 通用命令回执、审计与outbox | Transaction的get/putCommandReceipt、appendAudit、enqueueOutbox和JDBC同连接提交实现；既有底座恢复测试可参考 | 测试验证成功审计/outbox同事务且重放不重复；未实现/验证完整业务发送链路或拒绝/敏感查看审计 |
| Schema登记/激活与普通历史 | 当前JdbcStorageProvider/已有规格支持；新模型采用新namespace且只用于临时库 | 不自动迁移旧Mirror库；任意历史区间追改和未来有效事实尚未承诺 |

可重现证据在 `tests/model-verification/src/test/java/gov/mirror/model/DomainModelTest.java`，机器可读结果见 [model-validation.json](model-validation.json)。报告引用的历史810/920项仅是此前旧仓库回归；旧应用已删，不能继续将其作为当前Mirror实现证据。

## Action安全链路

详细定义见[action-boundaries.md](../../business/action-boundaries.md)。当前14个Action通过真实CEL/事务执行，并验证晚拒绝回滚及审计/outbox的数量。读取回执同一命令键重放返回原结果；readKey唯一仅防重复，不替代CommandReceipt。授权测试是明确标注的fixture策略，不是生产组织范围/本人会话实现。

ActionExecutor的ActionAuthorizer支持可信策略钩子，默认拒绝；ApplicationService具备注册/资源授权，但会自行装配策略，不能假设它已自动组合Mirror本级审核和本人权限。生产装配需明确接入策略；不能把几个权限名字当成完整安全实现。

Pack Loader当前要求ODL Action和Manifest逐一对应，不能注册仅有签名的复杂服务为可执行Action。当前无任意Java Handler执行扩展，executeBatch是逐Action事务；Mirror流程不需登记，但同一业务原子转换内的动态集合写入需完整事务方案。对于初次标签关联、动态名单冻结、阶段贡献失效、批准发布/发送意图、回执合并等尚未登记的边界，必须先实现/验证，不能绕过Action做匿名CRUD或虚构已有能力。

阅读原子Action保存事实并产生通用完成事件；当前已读/未读查询由ReadReceipt直接决定，已有OverdueEpisode清理/对接不一致问题由幂等消费者收敛。如果要求这两类派生记录也同事务改变，需要扩展该Action，而不是假装多个调用共享事务。

## 必须由Mirror补的能力，不是Foundry缺口

- 服务端功能/组织范围/本级独立审核/本人阅读权限；查询与敏感访问审计。
- 规则DSL、映射/最高层级、人工抑制、AI复核与配置版本匹配。
- 必需关系、复合key/端点一致、版本归属、发布冻结、合法状态迁移及同事务维护投影。
- 图文清洗、私有媒体、逐图确认、名单冻结、调度、送达回执合并、修订撤回与逾期。
- 来源匹配、外部身份票据/渠道协议、后台运行作业/租约/重试、指标口径、H5最小数据面和前端。

这些有清晰服务归属，不需要在Foundry加入政务对象判断或廉洁提醒逻辑。

## 对后续实施有实际影响的边界

| 边界 | 当前事实 | 后续如何处理 |
| --- | --- | --- |
| 动态业务变更的原子Action | 当前Manifest只支持有限effects；没有任意集合计划或共享事务Action组合 | 为名单冻结等实际业务设计最小通用事务计划/受控效果扩展，或证明分批准备+原子激活方案；不移植整个Mirror流程到Foundry |
| 未使用目录/内容的物理删除 | `JdbcStorageProvider.deleteObject`写deleted_at并追加历史；没有通用受控purge接口。不能把它当作原文物理删除 | 待确认业务要求后，设计引用检查/保留策略/媒体回收的专用维护通道；草稿可先放Mirror配置存储，但这不能代替已入库未引用对象的完整清理方案。若必须只经SPI物理删，再补最小通用purge机制 |
| 数万级查询/统计 | 基础查询/历史可用，通用数据库过滤/聚合下推与跨读取快照不完整 | 按新Mirror查询模型建立受控索引/投影、固定权限和快照范围，并做5万级压力验证，不先造完整查询平台 |
| 国产数据库 | 存在JDBC方言入口；本轮只验证H2 | 目标库选定后验证DDL/锁/时间精度/唯一/迁移/恢复/业务查询；方言名字不算验收 |
| 公网接收端最小数据权限 | Foundry通用对象表承载不同敏感领域，直接开放通用SPI/表权限不符合本人H5最小读取要求 | 设计只含已发布图文及本人接收记录的受控投影/服务访问层，独立账号及路由，由Mirror部署/读取模型承担 |
| 旧数据迁移与保留 | 新namespace、结构与0.2.x不兼容，本地旧库仍在 | 需单独迁移方案/备份/引用及ID映射审计；不自动apply新Pack到旧库 |
| 时间和权限策略变更 | 普通状态历史/顺序迟到支持；任意区间追溯更正、跨权限服务一致快照未完整 | 本轮不把任意时间重写作为必要业务；真有案例再定义。权限检查须按实际执行时重新评估 |

动态业务写入的原子Action支持和物理删除是已确认的特定机制边界，性能/国产库/部署隔离是必须验收的实现条件；不应把它们混称为“大量平台功能还没实现”。用户确认前本轮不补底座、不实现维护通道。

## 不构成当前前置条件

完整ODL AST/代码生成、通用CLI/SDK、全量GraphQL订阅、FHIR/CDM、医疗Consent扩展、通用Undo、全套Kafka/Schema Registry、Overlay写回/分布式缓存、FGA模型自动部署和所有数据库下推。新Mirror业务不依赖这些可选入口。

任何新依赖都要先从具体业务场景出发验证，不因为“上游有”就扩展。当前应停止在模型确认，下一步由用户确认后的业务范围决定。
