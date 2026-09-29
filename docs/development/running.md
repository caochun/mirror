# Mirror 构建与运行

Spring Boot 同源提供 React/Tailwind 页面与 API。已实现第一个闭环：人工建档 → 待准入 → 管理员决定准入 → 人工赋标 → 抑制/恢复 → 查看事实与关系历史。

根 `pom.xml` 是单一 Spring Boot JAR 项目，后端位于 `src/main/java`，全部 Java 测试位于 `src/test/java`，领域定义位于 `src/main/resources/domain-pack`，前端源码位于 `web/`。

## 构建

Foundry 子模块独立构建，先将应用所需的底座制品安装到本地 Maven 仓库：

```bash
mvn -f foundry/pom.xml -pl foundry-api,foundry-storage-jdbc,foundry-storage-memory -am install -DskipTests
mvn package
```

根构建自动执行 `npm ci`、TypeScript/Vite 构建和 Java 测试，将 `web/dist` 复制到 `target/classes/static`，将 Domain Pack 连同应用资源打入 `target/mirror.jar`。产物包含运行依赖，可直接用 `java -jar` 启动。正式制品仓库可替代本地 Foundry 安装步骤；修改底座源码后需重新安装其制品。

仅运行 Java 测试时可指定 `-DskipFrontend=true`；正式打包使用默认构建，保证静态资源同步。生成的前端文件不写回 `src/main/resources`。

## 本地启动

在仓库根目录执行（JDK 21+、Maven、Node.js）：

```bash
python3 scripts/run-mirror.py --build --init-local
```

打开 `http://127.0.0.1:8090`。`--init-local` 显式建立本地验收环境，仅首次生成配置、账号和合成目录，不生成虚构人员、不覆盖已有事实。后续启动使用：

```bash
python3 scripts/run-mirror.py
```

默认本地目录为 `.runtime/personnel-v1/`，可用 `--local-dir /absolute/path` 指定另一套隔离环境。`--port 8091` 修改端口。

启动脚本直接运行可执行 JAR，不再从测试报告拼装 classpath，也不依赖源码目录中的 Pack 或前端文件。

- `application.properties`：独立 H2 文件库地址、BCrypt 密码哈希、角色与组织范围，仅当前用户可读写。
- `credentials.txt`：首次随机生成的本地登录密码，仅当前用户可读写。该文件及数据库均受 Git 忽略；不会在启动日志打印密码。
- `foundry.mv.db`：当前事实、关系、历史、命令回执、成功审计和 outbox。重启保留数据，不使用旧 `.runtime/mirror.mv.db`。

首次账号能力如下：

| 账号 | 能力 | 范围 |
| --- | --- | --- |
| admin | 准入决定、建档、标签维护、只读对象/模型/审计诊断 | 全部组织；操作组织为示范单位（一） |
| editor | 建档、人工标签维护 | 示范单位（一） |
| reader | 查看人员、标签依据和历史 | 示范单位（一） |
| other | 建档、人工标签维护 | 示范单位（二） |

从 `credentials.txt` 取得密码后登录。人员页按当前账号能力显示入口；用 editor 建档，用 admin 决定准入，再用 editor/admin 赋标。账号角色与组织配置修改经应用重启生效，旧会话失效；命令重放仍重新检查当前授权。当前没有账号管理页面或统一身份源接入。

合成数据只有两家组织和一个已发布末级标签，页面明确标注验收环境。正式目录的维护发布流程尚未建设，不能将合成配置直接视为正式业务目录。关闭 `mirror.foundry.seed-demo` 可禁止合成初始化，但不会删除已有合成数据；正式环境应使用另一空库并经受控流程配置目录。

上述为默认最小样例。需要更多人员数据时，使用 `python3 scripts/run-mirror.py --reference-data`，显式追加参考根目录脱敏 Excel 生成的15名人员、18个组织、10个岗位、22个标签及关联场景。新增数据只初始化一次，不覆盖用户修改；用 admin 查看，其他账号的组织授权不会自动扩大。数据来源、合成规则与限制见[参考样本初始化](reference-data.md)。

## 独立 JAR 与资源加载

复制 JAR 后可在任意工作目录启动，账号与数据库配置单独提供：

```bash
java -jar /absolute/path/mirror.jar --spring.config.additional-location=file:/absolute/path/application.properties
```

若需在独立目录生成本地验收配置，可以使用 JAR 内的初始化工具：

```bash
java -Dloader.main=gov.mirror.app.LocalSetup -cp /absolute/path/mirror.jar org.springframework.boot.loader.launch.PropertiesLauncher /absolute/path/local
```

默认 Pack 来自 classpath 的 `domain-pack/`。`PackResources` 将这些资源展开到临时目录供 Foundry 的目录加载器使用，应用关闭时删除临时副本。设置 `mirror.foundry.pack-directory=/absolute/path/custom-pack` 可覆盖默认 Pack；外部目录不会被删除，无效外部配置会明确失败，不会悄悄退回内置 Pack。

前端由 Spring Boot 默认的 `classpath:/static/` 提供。如需开发期外部静态目录，使用标准 `spring.web.resources.static-locations` 配置。原 `mirror.web.assets-directory` 配置不再使用。

## 代码与边界

- `PersonnelService`：人员查询投影、业务输入、规范身份、幂等命令和当前标签有效性计算。
- `PersonnelPolicy`：真实身份/角色及显式组织范围，在 Foundry Action 事务及效果/回执重放中检查。
- `ObjectRelationsService`：管理员双向关系查询、类型筛选与分页，以及已结束关系的变更历史。
- `ObjectPresentationService`：将已授权对象与关联事实组合成业务标题、说明和字段，供侧栏、浏览路径与关联信息统一使用。
- `FoundryRuntime`：加载 Pack、管理 JDBC 生命周期、装配只读 `ApplicationService`。
- `PackResources`：适配 JAR 内领域资源与外部 Pack 目录，管理临时资源生命周期。
- `SecurityConfiguration`：Spring Security 会话认证、CSRF 保护；不接受前端自报角色、actor 或 tenant。
- `web/`：业务工作台与管理员只读对象浏览；普通页面的写入不要求填写对象 ID 或技术版本号。

业务接口位于 `/api/mirror/people`、`/api/mirror/catalog`、`/api/mirror/commands/{command}`。目前只允许 `RegisterManualPerson`、`DecideObjectMembership`、`AssignManualTag`、`ApplyManualTagContribution`、`SuppressPersonTag` 五种命令，要求稳定 `Idempotency-Key`。Manifest 固定来自已加载 Pack，服务端补齐 actor、操作组织及规范贡献键。

业务写入使用 Foundry 的 `ActionExecutor` 与 Mirror 事务授权策略。没有重写 Action 执行器、事务、审计或命令回执；也没有假定 `ApplicationService` 会保留传入执行器的自定义策略。Mirror 流程不向 Foundry 登记。

通用 `/api/v1/`、`/graphql`、`/api/model`、`/api/events` 当前仅管理员可读。REST 查询委托 `RestApiRouter`，GraphQL 委托 `GraphqlApiRuntime`，不注册通用 mutation；通用 REST Action 写入口也拒绝。新增业务写功能必须先完成相应业务与权限契约，不能通过管理工具绕过。

管理员可在人员详情点击“对象关系”，或从“对象浏览”进入。视图按业务含义展示相关信息，支持逐层跳转、路径返回、类别筛选、分页、“查看历史关联”和逐条关联变更。原始方向和关系 ID 折叠在技术详情中。接口为 `/api/objects/{type}/{id}/relationships` 及其历史子路径；业务描述通过 `/api/objects/{type}/presentations` 和 `/api/objects/{type}/{id}/presentation` 提供。所有入口沿用管理员权限，不增加普通账号的通用访问范围。

页面用“对象库管理状态／调整管理状态”代替“对象准入／办理准入”，“全部可查看单位”代替“全部授权组织”，“取消标签”代替“人工抑制”。原文出处、状态含义与内部名称的区别见[页面术语](../business/ui-terminology.md)。

当前状态、历史和目录提供本闭环需要的投影，敏感属性从普通业务响应中剔除。审计/outbox 管理诊断显示最近 200 条；outbox 代表已提交待处理事件，尚未启动外部投递器。拒绝请求记录 trace、actor、路径和失败类别，不记录密码或请求正文。

## 验证

```bash
mvn package
npm --prefix web run test:e2e
python3 scripts/verify-jar.py
```

当前 Java 验证包括19项模型、24项Action、8项应用场景、2项资源加载、3项参考数据初始化测试，共56项。应用测试覆盖双向关系、分页、已结束边、历史、端点归属、业务描述及接口权限。4项浏览器场景覆盖业务闭环、移动布局、真实进程重启、任职业务说明、关联跳转、方向对应名称、技术信息折叠和关系历史。`verify-jar.py` 将 JAR 复制到没有仓库源码的临时目录，验证静态页面与资源、登录、完整 Pack 加载、建档及 outbox 提交。

当前 JDBC 适配限定 H2 本地验收，尚未验收国产目标数据库、数万级查询性能、正式目录发布、外部身份和渠道。查询投影仍在服务层扫描和关联读取，不承诺大规模查询性能或整个详情的跨读取一致快照。
