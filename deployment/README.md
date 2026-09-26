# 应用启动与部署边界

目前可运行的是应用骨架、人员目录、标签配置与人工赋标：Spring Boot后端、数据库账号/会话、组织和功能授权、React + Tailwind页面。自动规则、AI复核、任务审核、鹿路通和正式H5仍未接通，不能作为完整生产系统部署。

## 本地运行

按根目录README构建并运行。后端默认读取JAR内的 `application.yaml`；本地使用H2文件数据库 `.runtime/mirror.mv.db`。首次设定 `MIRROR_BOOTSTRAP_PASSWORD` 创建admin账号（12–72字符），之后不会覆盖密码。`MIRROR_DEMO=true` 才创建演示数据和unit/area/reviewer账号，演示账号同样使用配置的初始密码。

账号密码以BCrypt散列存储；会话采用HttpOnly、SameSite=Strict cookie，写请求需要CSRF token。每次目录请求重新读取账号有效状态和组织权限。当前会话为单实例内存会话，服务重启需要重新登录，尚未提供生产单点登录、多节点会话、完整账号管理和登录限速。

## 配置来源

| 配置 | 默认值/用途 |
| --- | --- |
| MIRROR_PORT / MIRROR_BIND_ADDRESS | 8080 / 127.0.0.1 |
| MIRROR_JDBC_URL | 本地H2文件库；生产必须显式配置 |
| MIRROR_JDBC_USERNAME / MIRROR_JDBC_PASSWORD | 数据库账号密码 |
| MIRROR_DB_DIALECT | h2；可选postgresql/openGauss，后两者尚待真实环境测试 |
| MIRROR_DOMAIN_PACK | 仓库根下 `./domain-pack` |
| MIRROR_BOOTSTRAP_PASSWORD | 首次创建账号使用，留空不创建 |
| MIRROR_DEMO | false；显式true初始化虚构演示资料 |
| MIRROR_SECURE_COOKIE | 本地HTTP为false；HTTPS部署必须true |
| MIRROR_API_TARGET | Vite开发代理目标，默认http://127.0.0.1:8080 |

生产覆盖文件 `deployment/application.yaml` 已改为应用实际支持的配置键，使用以下方式读取：

```bash
java -jar mirror-server/target/mirror-server-0.1.0-SNAPSHOT.jar \
  --spring.config.additional-location=file:deployment/application.yaml
```

此文件强制Secure cookie、关闭演示模式并要求显式数据库凭据。默认openGauss方言只是部署意图；当前使用PostgreSQL JDBC驱动，Flyway对目标版本的识别、DDL兼容性和业务一致性需要在真实openGauss环境验证后才能宣称可生产使用。

## 迁移与静态资源

应用身份和功能权限表通过Flyway的 `db/migration/` 版本脚本管理。Foundry负责自身对象/关系/历史表初始化；当前Domain Pack载入不等同于已经实现完整在线业务Schema升级流程。

前端 `npm --prefix web run build` 生成 `web/dist`，包含管理端 `index.html` 和独立的 `receiver.html` 构建入口。`scripts/build-and-run.sh` 会先构建前端，再将 `web/dist` 复制进 Spring Boot JAR，由同一个后端HTTP服务提供 `/` 管理端和 `/r/**` 接收端入口；不再需要单独启动 Vite 才能访问应用。当前H5明确显示未接通，不产生阅读回执。

`openfga-model.fga` 仍为早期参考，当前应用未接入OpenFGA，不能把部署该文件视为已经启用授权。实际目录权限由Mirror服务端的账号、租户和组织关系校验执行。鹿路通及AI正式配置也待相应里程碑接入，不保留看似启用但没有消费者的配置项。

## 已有验证与剩余工作

后端HTTP测试覆盖认证、CSRF、停用会话、下级组织范围、兄弟单位拒绝、审核员拒绝、跨租户隔离和超过100条的分页。文件库测试跨两次应用启动验证账号、人员和历史保存。Playwright直接连接测试后端，不以浏览器存储或角色模拟替代认证。

当前查询投影先完整分页读取再过滤，结果不截断，但尚未满足5万级性能门槛，后续需数据库查询投影。持久化写业务、后台作业、完整审核链路和生产环境验收仍见业务清单。
