# Java Foundry 与 Open Foundry 覆盖对照

日期：2026-09-27。**以下矩阵及探针结果为ff944f7的审计基线，后续修复见[治理修复记录](governance-repair.md)、[时间修复报告](temporal-repair.md)、[属性约束修复](property-validation-repair.md)、[持久回执修复](transactional-receipts.md)、[声明模型修复](declarative-model-repair.md)、[关系导航](relationship-navigation.md)、[事务筛选删除](filtered-link-actions.md)、[事件恢复](event-delivery-recovery.md)、[Action副作用与补偿](action-side-effects.md)、[本体权限映射](ontology-authorization.md)、[组合Pack](pack-bundles.md)、[计算字段](computed-fields.md)、[类型化Action API](typed-action-api.md)、[受控对象查询](governed-object-queries.md)、[受控聚合](governed-aggregations.md)、[受控搜索](governed-search.md)、[Connection分页](connection-pagination.md)、[持久模型注册表](persistent-schema-registry.md)、[模型激活/写入门禁](schema-activation.md)、[读/应用模型绑定](schema-read-binding.md)、[ObjectSet](object-sets.md)、[Consent](consent.md)、[事务Consent效果](consent-effects.md)、[自定义scalar](custom-scalars.md)、[Action关系路径](action-relationship-paths.md)、[事务血缘与同步](transactional-lineage-and-ingestion.md)、[映射语言](datasource-mapping-language.md)、[事务关系物化](relationship-ingestion.md)、[托管JDBC与运行](managed-jdbc-extraction.md)及[Debezium CDC](debezium-cdc.md)；不要将历史缺陷表当成当前全部状态。** 本次比较平台能力，不把Mirror的标签规则、廉洁提醒或政务流程作为Foundry应实现的功能。

## 固定比较基线

| 仓库 | 本地路径 | 比较提交 |
| --- | --- | --- |
| 上游TypeScript Open Foundry | `open-foundry/`，`git@github.com:syzygyhack/open-foundry.git` | `1d7e1aa62208d32ed91d358f4503cf2d95523a7e`，标签`v0.3.0` |
| Java重制Foundry | `foundry/`，`git@github.com:caochun/foundry.git` | `ff944f7222720bc3e81d8c38ea373e666974f58b` |

参考仓库已加入Mirror的`.gitmodules`并检出，不加入Maven reactor，不代替Java依赖。两份代码均保持原样。本报告依据源码、现有测试及独立探针，不依据README或已勾选任务单直接判断完成。

**总体结论：Java版目前是具有对象/关系历史与事务能力的基础实现，尚不是上游v0.3.0的功能等价重制。** 除尚未移植的功能外，现有通用API、Action治理、字段校验、时间语义也有可复现缺陷。应先补核心契约与可信执行路径，再决定可选能力的优先级，不宜继续以“平台功能已齐全”为前提扩展业务。

## 已有能力、部分覆盖与缺失

“已有”表示有实际实现，并不表示生产验收；“部分”包括组件存在但未接入执行链；“缺失”是本次基线未发现对应平台实现。

| 能力 | 上游代码依据 | Java实际情况 | 结论 |
| --- | --- | --- | --- |
| ODL基础解析 | `packages/odl/src/parser` | OdlParser读取命名空间、ObjectType、LinkType、ActionType和部分属性指令 | 部分覆盖 |
| 完整ODL语义 | parser/types.ts含enum、interface、@link、@computed、@constraint、@default、@readonly、@searchable、权限等 | OntologySchema只保留三类类型；@link/@computed字段被过滤，枚举/接口定义不进入模型；action permission丢失 | 明显缺失；不应静默忽略语义 |
| 本体编译产物 | `odl/src/codegen`生成GraphQL、OpenFGA、SDK；`storage-postgres/src/schema`生成表/索引等 | SchemaCompiler主要做基础校验、摘要和名称索引；GraphQL生成另有简化类；没有授权模型/SDK生成 | 部分覆盖；“编译”范围不同 |
| Pack装载组合 | `api/src/schema-loader.ts`加载schema/action/permissions/field-permissions/connectors/seeds/capabilities并合并 | DomainPackLoader加载schema/actions；loadAll检查依赖后返回独立Pack，版本约束仅精确或>=，没有完整合并及权限/种子/连接器载入 | 部分覆盖；上游Pack不能保证直接使用 |
| Schema演进 | `odl/src/diff`、`storage-postgres/src/schema-registry`有持久注册、迁移记录 | SchemaDiffer和内存Registry已有；JDBC applySchema只初始化通用表并设置进程内schema | 部分；缺持久注册、迁移执行与漂移检测，类型变化分类存在缺陷 |
| 对象、关系CRUD | SPI、ObjectManager、LinkManager、memory/postgres providers | 内存与JDBC provider，关系有稳定ID/属性/版本，乐观并发、端点与基数检查已有 | 核心已有；属性校验不足 |
| 通用写入校验 | `engine/src/objects/validation.ts`有字段类型、必填、枚举、唯一、不可变及约束校验 | 两Provider只做部分对象类型/ID/版本/关系校验，没有对应完整ObjectManager校验层 | 明显缺失；已实测错误数据可写入 |
| 对象历史、时间查询 | SPI提供getObjectAtVersion/getObjectAtTime | Java同时提供对象和关系版本/双时间读取及traverseAsOf | 接口扩展；默认更新与历史查询有实测语义问题，不能声称双时态完整 |
| 查询过滤/排序/分页 | SPI有FilterExpression、orderBy、ObjectPage/totalCount等，PG实现filter-to-sql | QueryOptions只有limit/offset/asOf/includeDeleted；queryObjects按类型取列表，无条件/排序/总数契约 | 主要查询能力缺失 |
| 聚合、全文检索、索引 | SPI aggregateObjects/searchObjects/index管理，PG aggregate/search实现 | SPI无对应查询，capabilities全文搜索=false；属性存通用JSON文本，@indexed未转领域字段索引 | 缺失 |
| 图遍历 | PG/AGE traverse有路径、范围与上限 | Java有基于关系的历史路径遍历，无等价图查询能力，recursiveTraversal=false | 有意替换图存储，查询能力仍部分覆盖 |
| ObjectSet保存查询 | `engine/src/object-sets`及PG持久store | 未发现通用保存过滤/聚合查询的对象集服务 | 缺失 |
| 计算属性/函数/血缘 | `engine/src/computed`、`engine/src/lineage`，ODL函数声明 | Java ODL丢弃computed字段；Provenance等数据类型存在，无对应通用运行与字段血缘服务 | 缺失或仅数据类型 |
| CEL前置条件 | 上游Go gRPC CEL及Action变量解析 | Java嵌入CEL库，预条件与actor.hasRole转换已有 | 部分；对象ID解析、上下文及效果表达式不等价，Go进程不必照搬 |
| 受控Action执行 | 上游executor显式校验→授权→consent→前置条件→事务效果→副作用→审计/事件 | Java有有限效果、事务、成功审计/outbox；ApplicationService.execute未执行Action权限检查且未传类型定义 | 部分且有关键缺陷 |
| Action完整效果/扩展 | 上游deleteLink支持filter/expect；side-effects及补偿，工具注册、consent效果 | Java仅updateObject/createObject/createLink/deleteLink(id)；效果值转换为String；reversible无对应补偿执行 | 缺失；不能悄悄接受未执行的sideEffects/rollback |
| 幂等与批量 | 上游storage bulkMutate及Action批量路径，各自需独立评估可靠性 | JavaAction幂等仅可选InMemoryStore，按裸key读写且在事务外；批量逐项循环，异常可能中断 | 部分；跨租户回放和崩溃窗口已确认或可由代码判断 |
| 身份/授权/字段策略 | OIDC、OpenFGA check/list/grants、权限生成/override、field redaction及consent | Java JWT/JWKS验证、Check HTTP适配、FieldPolicy.redact方法已有；未形成全入口完整治理链，无consent服务 | 组件部分覆盖，不能当安全执行闭环 |
| GraphQL/REST | 上游生成过滤/排序/聚合、关系/历史、typed action、订阅及API制品 | Java基础单查/列表/历史路由、JSON字符串Action；关系字段与分页体系不足，普通属性读取有缺陷 | 部分且需修复 |
| 实时订阅/事件总线 | subscriptions及Redpanda event bus | Java有CloudEvents模型与EventSink，无通用WebSocket订阅及消息总线适配 | 部分 |
| 事务审计/outbox | 上游Action审计/事件与PG store；不要假定所有发事件路径都有事务outbox | JavaTransaction能把业务变更、审计、outbox同事务提交，Dispatcher失败可再次派发 | 已有；是值得保留的设计，消费去重崩溃恢复仍不足 |
| 同步与冲突 | connectors、mapping transforms、Debezium消费/Checkpoint、overlay、conflict及对账相关代码 | JDBC/REST基本读取、字段映射、独立ConflictResolver已有；MaterializedSyncService未调用冲突器，也未持久化MappedRecord.provenance | 部分；CDC、overlay和完整血缘/冲突执行缺失 |
| 国产关系库 | 上游主要PG17+AGE | Java通用JDBC，H2/PG/openGauss/Kingbase/Dameng dialect入口 | 方向差异；不能把几个类型名替换当作国产库验证通过 |
| 运维/发布工具 | observability、Compose、Helm/HPA/PDB、CI、SBOM、API制品 | Java独立仓库主要Maven库与测试，无同等平台启动/治理/部署/观测发行套件 | 缺失；不要求机械照搬微服务数量 |
| FHIR/CDM等领域适配 | 上游能力门控的FHIR只读、NHS CDM等 | Java无对应适配 | 可选领域扩展，不是Mirror当前必需，也不应硬编码进核心 |

## 关键问题的实际复现

脚本：[ReferenceProbe.java](../scripts/foundry-audit/ReferenceProbe.java)，运行器：[run.py](../scripts/foundry-audit/run.py)，原始结果：[foundry-audit-observations.json](foundry-audit-observations.json)。所有写入仅发生在探针自己的内存和H2内存库，不连接Mirror数据或外部通知。

### P0：治理与校验没有贯穿通用入口

1. **Action授权未执行。** 创建拒绝所有资源的AuthorizationService，经ApplicationService.execute运行createObject，结果仍success=true且对象写入。该入口直接调用ActionExecutor，并将ActionTypeDefinition传null；ActionExecutor也没有授权器。该问题是通用底座入口的问题，不能用Mirror自己的BusinessCommands鉴权作为完成证据。
2. **字段策略未接入读取。** schema的secret声明@sensitive，ApplicationService.getObject仍返回其原值。AuthorizationService.redact方法存在，但get/list/history未自动应用FieldPolicy；单有注解不是脱敏策略，单有策略工具也不是执行链。
3. **数据约束未执行。** memory和JDBC/H2均接受缺少必填name、name写整数、重复@unique serial和修改@immutable serial。上游有专门的对象校验层；Java版应明确由通用受控写入层/Provider分别承担哪些校验，并验证无法绕过。
4. **幂等没有隔离。** 同一ActionExecutor/Store在租户A成功后，租户B用相同裸key和不同参数得到A的actionId。还需解决请求指纹、主体与动作绑定、原子抢占及与业务提交的同事务回执，当前测试仅覆盖同key重复返回。

这些不是要求Foundry认识“职务层级”等业务语义，而是通用平台必须提供的正确性边界。

### P0：时间语义不能只看接口名

探针先创建name=before，记下当时时点，等待后普通更新为after，再查“更新前validTime＋当前recordedTime”：两个Provider都返回after。原因是普通更新的历史记录复用了前一状态的updatedAt/validFrom，表现成追溯更正，而非默认从本次变更生效。

同时，queryObjects传入更新前的asOfValidTime/asOfRecordedTime仍返回after；当前列表路径并未执行这两个时间参数。Transaction也没有明确的effectiveAt参数，难以区别正常更新、补录和追溯更正。

Java新增了getLinkAtVersion/getLinkAtTime/traverseAsOf，是正确的扩展方向；但应先规定对象与关系对称的时间写入语义，再以创建/变更/解除/补录/更正的同一组测试验收。不能把这些接口存在等同于完整双时态保证。

### P1：上游兼容与API真实行为

- 直接装载上游`examples/library-pack`失败，根因`ReturnBook`的deleteLink使用filter/expect，Java要求linkId。不是缺少命名空间。
- 单独解析上游Book schema只保留id/isbn/title/author/status；borrower的@link字段被丢弃，@searchable及BookStatus枚举定义无法完整保留。
- BorrowBook manifest解析成功，但上游sideEffects及rollback配置没有进入Java的ActionManifest；这比显式拒绝更容易制造“已兼容”的错觉。`now`在Java效果赋值中也只是字符串，并非统一时间表达式求值。
- interface提供id主键、对象继承时，Java编译报缺少主键，未处理接口继承。
- String!改为Int!被SchemaDiffer归类为COMPATIBLE，缺少正确的破坏性演进判断。
- 用真实声明的Item.name:String!执行GraphQL查询，返回非空字段null错误、item=null。根解析器返回ObjectRecord，未为properties中的领域字段建立解析器；原测试仅查询id，未覆盖该问题。

## 其他重要限制

- **OpenFGA适配未经过真实服务验证。** Java构造`/stores/{store}/authorization-models/{model}/check`，其测试用本地HTTP stub接受同一路径；标准Check应核对`/stores/{store}/check`和body中的authorization_model_id。还应核验多租户对象编码、超时、tuple管理及模型载入。当前stub测试不能证明真实联通。
- **事件消费去重有崩溃窗口。** JdbcIdempotentEventSink先提交claim，再调用delegate；若进程在两者之间退出，永久claim使重放跳过。源码和ADR-0003都提示需要租约/状态恢复。Dispatcher当前没有完整多worker领取、退避、死信和运维状态。
- **同步“有类”不等于“有闭环”。** RecordMapper保留Provenance，ConflictResolver能独立计算，但MaterializedSyncService直接create/update/delete，没有接入它们；JDBC读取先把全部结果存List，REST也无完整分页/Checkpoint协议。字段来源在持久化与查询层仍待贯通。
- **存储可替换不等于查询已抽象。** Mirror已经通过MetricsSnapshotReader直接读取of_objects/of_links补统计，这能满足业务阶段需求，却说明底座缺少可复用查询能力；它不是Java Foundry已支持聚合的证据。业务计算应留Mirror，通用过滤/聚合/一致快照契约应评估回到底座。
- **关系基数检查仍需并发验收。** 存在串行端点/基数检查不代表多进程竞争下数据库必定保持基数；本轮未对真实数据库并发执行此验证，不提前下保证。

## 有意保留的重制差异

1. **Java而非TypeScript/Go运行时。** 采用JVM内CEL，不必为形式一致再加Go sidecar。
2. **关系数据库而非AGE作为必需依赖。** PostgreSQL/国产关系库保存权威事实合理；图可以是可选投影。缺失的是明确查询能力，不是“没装AGE”本身。
3. **关系与对象对称历史。** 上游当前SPI只有对象的atVersion/atTime，虽有关系CRUD与版本字段，但没有对等关系历史读取接口。Java增加关系历史与双时间契约，应保留并修正实现。
4. **模块化库/单体部署。** 不必复制上游多个进程；但启动装配、权限执行、配置检查、观测和可交付制品仍需要有承担者。
5. **业务仍在Mirror。** 标签算法、人工抑制政策、审核员同级规则、提醒时限、大屏指标口径都不应移入Foundry；底座提供通用执行扩展点，具体Handler仍由应用提供。

## 不应算成Java缺失的上游未完成项目

上游README明确：备份恢复Provider、跨实例联邦协议、FHIR写、通用应用UI组件、部分性能基线和git-backed Registry仍未完成或仅规格。本次不能把这些都算作“上游已经提供而Java遗漏”。上游SDK生成器有代码，但`packages/sdk-typescript/src/index.ts`仍为空占位，不能声称现成SDK发行包已经完整可用。

上游仍是pre-1.0，开发模式默认有allow-all治理替身。本次只读取上游实现/测试与制品，未运行其Node/Compose栈或真实OIDC/OpenFGA/PG集成；不能把上游所有宣称等同于本次验证通过，也不建议照搬默认开发部署。

## 验证结果与重现方法

```bash
git submodule update --init --recursive
mvn -f foundry/pom.xml test
python3 scripts/foundry-audit/run.py
```

- Java现有测试：50项，0失败、0错误、0跳过；其中conformance本轮是memory/H2，PG_TEST_URL未配置，PostgreSQL用例没有被注册执行，不能因“0跳过”说PG已验证。
- 独立探针：编译/执行成功，得到上述缺陷观察结果。脚本是审计工具，输出true表示“该现象确实发生”，不表示需求通过；它不替代后续应失败/应拒绝的回归测试。
- 未运行真实国产库、上游整栈、生产授权或性能测试。本轮不更改两个子模块中的实现，不宣称已修复。

## 建议的后续顺序与验收门槛

| 顺序 | 平台工作 | 验收门槛 |
| --- | --- | --- |
| P0 | 统一Action/同步的校验与授权入口，读侧字段策略，租户绑定和事务幂等 | 拒绝主体不能写；普通/历史/关系读取不泄漏受限字段；并发/跨租户/重启命令回放不串用、不重复 |
| P0 | 明确对象/关系的普通更新、有效时间、记录时间与补录更正 | 同一用例在memory/JDBC验证旧时点、当前、跨时间遍历和列表一致；明确effectiveAt写入契约 |
| P1 | ODL中间表示、严格拒绝未支持语义、最小参考Pack兼容 | Library Pack可按既定兼容范围加载并执行借还；关系字段、枚举、权限不丢失；不支持项明确报错 |
| P1 | 修复GraphQL属性/关系/Action参数，并补通用过滤、排序、聚合和分页 | 同一查询REST/GraphQL/SPI口径一致；有权数据分页总数正确；Mirror不必扫描全库实现普通筛选 |
| P1 | 持久Schema Registry及演进门禁 | 重启保留版本；类型变化、唯一性变化等正确分类；同版本不同内容拒绝，迁移可验证 |
| P2 | 同步provenance/冲突规则/Checkpoint与事件恢复 | 断点续传、重复乱序、来源更正、消费进程崩溃均有证据与恢复结果 |
| P2 | ObjectSet、计算属性、订阅、SDK/CLI/观测与部署完善 | 按明确的Java重制范围逐项验收，不承诺所有上游周边都首期实现 |
| 发布门槛 | 目标国产库和真实身份授权集成 | 在实际驱动/数据库版本、真实OpenFGA/OIDC下验证；不能用H2替代 |

先将这份差异转为Foundry独立项目的修正规约/任务，再继续Mirror功能扩展。已有业务代码中的通用能力可评估提炼，但不能把Mirror的规则、审核和渠道流程搬进平台。

## 重点代码入口

- [上游SPI](../open-foundry/packages/spi/src/storage-provider.ts) / [Java SPI](../foundry/foundry-spi/src/main/java/org/openfoundry/foundation/spi/StorageProvider.java)
- [上游ODL AST](../open-foundry/packages/odl/src/parser/types.ts) / [Java解析器](../foundry/foundry-schema/src/main/java/org/openfoundry/foundation/schema/OdlParser.java)
- [上游Action流水线](../open-foundry/packages/actions/src/executor/action-executor.ts) / [Java执行器](../foundry/foundry-actions/src/main/java/org/openfoundry/foundation/actions/ActionExecutor.java)
- [上游Pack装载](../open-foundry/packages/api/src/schema-loader.ts) / [Java Pack装载](../foundry/foundry-pack/src/main/java/org/openfoundry/foundation/pack/DomainPackLoader.java)
- [上游对象校验](../open-foundry/packages/engine/src/objects/validation.ts) / [Java JDBC](../foundry/foundry-storage-jdbc/src/main/java/org/openfoundry/foundation/storage/jdbc/JdbcStorageProvider.java)
- [Java通用API边界](../foundry/foundry-api/src/main/java/org/openfoundry/foundation/api/ApplicationService.java) / [GraphQL运行时](../foundry/foundry-api/src/main/java/org/openfoundry/foundation/api/GraphqlApiRuntime.java)
