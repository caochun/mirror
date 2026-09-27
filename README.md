# 政务系统对象库业务项目

本仓库是基于 [Open Foundry](https://github.com/caochun/foundry) 构建的政务对象库具体业务项目。Foundry 作为 Git submodule 位于 [`foundry/`](foundry/)；本仓库维护政务领域对象模型、业务 Action、标签/风险/提醒等上层逻辑和部署配置。

## 目录

- [`business-spec/`](business-spec/)：政务业务规约、对象模型、工作流和任务计划。
- `domain-pack/`：按业务规约从零实现的政务 Domain Pack。
- `business-core/`：基于 Foundry Storage SPI 的人员、标签、提醒、送达、权限和 AI 业务服务。
- `business-verification/`：内存 Provider 的业务端到端验收测试。
- `mirror-server/`：Spring Boot 应用入口、数据库账号/会话、受权限控制的业务 API。
- `web/`：React + TypeScript + Tailwind 管理端、独立 H5 构建入口和 Playwright 测试。
- `foundry/`：通用对象关系和状态历史底座，来自独立仓库。
- `deployment/`：openGauss/国产关系库、鹿路通和 OpenFGA 的部署参数模板。
- 根目录 DOCX/XLSX：原始业务材料和测试数据，已通过 `.gitignore` 忽略。

## 领域边界

基础 Domain Pack 只描述对象、关系和生命周期。标签计算、风险判断、廉洁提醒、鹿路通送达和具体监督流程在本业务仓库扩展，Foundry 不包含这些政务业务语义。

业务定义已复核为 [Domain Pack 0.2.1](domain-pack/README.md)，补充动作契约、来源依据、专项事项与版本追溯。人工标签处理器已开始接入；[运行契约登记](business-spec/runtime-contracts.md)区分定义与实现。可单独运行 `mvn -pl business-verification -am test` 验证定义；数据库迁移边界见 [MIGRATION.md](domain-pack/MIGRATION.md)。

## 获取和验证 Foundry

```bash
git clone --recurse-submodules git@github.com:caochun/mirror.git
cd mirror/foundry
mvn test
```

Foundry 的版本通过 submodule 固定；更新平台版本时，在本仓库更新 submodule 指针并运行业务验证。

## 当前可运行版本

已接通账号登录、组织权限、人员查询、档案基础信息、标签目录及人工添加/删除/恢复与历史；内容示例、受控配图、提醒草稿、选人、逐图确认、提交及独立审核已可操作。自动规则、AI复核、渠道发送和接收端阅读仍在开发；H5目前只有独立构建入口，不产生阅读回执。完整进度见 [业务任务清单](business-spec/tasks.md)。

当前目标是基于复核后的Domain Pack完成完整系统，包括Dashboard；实施进度见[任务清单](business-spec/tasks.md)。`http://127.0.0.1:8080/dashboard`目前仍是Mock价值展示，不代表生产统计，后续需替换为带权限的业务聚合与下钻。

本地需要 JDK 21+、Maven、Node.js 22.12+（建议当前 LTS）。从仓库根目录运行：

```bash
mvn -pl mirror-server -am package
npm --prefix web ci
```

在终端设置 `MIRROR_BOOTSTRAP_PASSWORD` 为自行选择的 12–72 字符初始密码。只在首次创建账号时使用，不会覆盖已有密码；不设置时不创建账号。显式启用演示模式后生成24名虚构人员及四种测试账号，禁止用于正式环境：

```bash
export MIRROR_DEMO=true
java -jar mirror-server/target/mirror-server-0.1.0-SNAPSHOT.jar
```

开发阶段也可以让后端直接提供前端：

```bash
./scripts/build-and-run.sh
```

访问 `http://127.0.0.1:8080/`。账号为 `admin`；演示模式另外提供 `unit`、`area`、`reviewer`，初始密码均取上述环境变量。管理端和未来接收端现在都由后端同源提供；修改后端端口时设置 `MIRROR_PORT`。

如果只开发前端交互，仍可单独运行 `npm --prefix web run dev`，它只用于前端热更新，不是完整应用的启动方式。

演示审核流程：以`unit`登录，从“提醒任务”创建并确认草稿、提交审核；退出后以独立的`reviewer`登录审核本单位任务。审核通过会创建待发送作业，当前尚不调用鹿路通。`admin`所在city单位默认没有独立审核员，不能绕过提交要求。演示人员身份引用标为mock，仅供本地验证，旧演示库缺少该引用的人员会显示身份待核实，不自动修改已有资料。

默认开发数据库为 `.runtime/mirror.mv.db`（H2文件数据库）；人员、历史、账号授权跨重启保存，会话重启后需重新登录。该环境不代表国产库兼容或生产验收。生产参数与验证边界见 [deployment/README.md](deployment/README.md)。

受控图片默认存于`.runtime/media`，可通过`MIRROR_MEDIA_DIRECTORY`指定持久目录，须与数据库一并备份。图片通过`/api/media/{id}`逐次鉴权，不能把该目录公开为静态文件。内容示例由`admin`维护并启用，`unit`可在提醒创建流程中引用。

## 验证应用

```bash
mvn test
npm --prefix web run build
cd web
npx playwright install chromium
npm run test:e2e
```

浏览器测试需要已构建最新后端 JAR；测试自动启动独立后端和前端，使用专用端口18080/15173、内存测试数据库及明确的测试密码，不连接本地文件库。测试覆盖真实登录、CSRF、组织范围、跨页查询、档案历史、审核员拒绝访问、标签配置/人工生命周期、弹窗键盘操作、移动宽度及失败状态；完整提醒闭环测试待后续交付。
