# Mirror

Mirror 是基于 Java Foundry 和 Domain Pack 构建的政务系统对象库业务应用，采用标准 Spring Boot 项目结构，React/Tailwind 前端与领域定义随同一个可执行 JAR 发布。

当前已实现人工建档、准入决定、人工赋标/抑制/恢复和历史查询，使用独立 JDBC-H2 文件库、本地登录账号及组织权限。提醒、自动标签等后续业务仍待实现，当前尚未完成正式部署验收。

## 构建与启动

本地首次构建（JDK 21+、Maven、Node.js/npm）：

```bash
mvn -f foundry/pom.xml -pl foundry-api,foundry-storage-jdbc,foundry-storage-memory -am install -DskipTests
mvn package
python3 scripts/run-mirror.py --init-local
```

也可用 `python3 scripts/run-mirror.py --build --init-local` 完成上述步骤。

打开 `http://127.0.0.1:8090`。首次账号密码位于 Git 忽略的 `.runtime/personnel-v1/credentials.txt`。构建产物为 `target/mirror.jar`，包含前端、Domain Pack 与运行依赖，无须部署源码目录。配置和独立 JAR 运行方式见 [运行说明](docs/development/running.md)。

## 仓库导航

| 目录 | 内容 |
| --- | --- |
| [src/main/java/](src/main/java) | Spring Boot 后端 |
| [src/main/resources/domain-pack/](src/main/resources/domain-pack) | 领域对象、关系与原子 Action，作为应用资源打包 |
| [src/test/java/](src/test/java) | 模型契约与应用集成测试 |
| [web/](web) | React/Tailwind 源码；构建产物打入 JAR 的静态资源 |
| [docs/](docs/README.md) | 业务规约、开发计划、当前进度与历史归档 |
| [foundry/](foundry) | 独立构建的 Java 底座子模块，应用通过 Maven 制品依赖 |
| [open-foundry/](open-foundry) | 上游只读参考子模块 |

阅读业务从 [业务自述](docs/business/mirror-business.md) 开始；开发状态以 [当前进度](docs/development/progress.md) 和 [任务清单](docs/development/tasks.md) 为准。DOCX、XLSX 和 `.runtime/` 继续保留在本地并受 Git 忽略。
