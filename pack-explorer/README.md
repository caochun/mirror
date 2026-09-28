# Mirror Pack 模型体验台

这是一个本地、隔离、合成数据的 Domain Pack 预览程序。它读取 `domain-pack/`，用 **Spring Boot** 承载 HTTP 服务，使用 Foundry 已有的 `ApplicationService`、`RestApiRouter` 和 `GraphqlApiRuntime` 提供对象、关系、历史和 Action API。

体验台不恢复旧 Mirror 应用，不连接 `.runtime/`，不连接外部渠道。写入口只接受 Pack 中已注册的 Action，禁止通用对象 CRUD。默认是只读预览；切换“演示操作员”并携带本地预览 token 后，才能在内存数据上执行 Action。权限策略是演示策略，不是生产组织授权。

首次构建并启动（JDK 21+、Maven、Node.js）：

```bash
python3 scripts/run-pack-preview.py --build
```

已有构建可直接启动；端口可通过 `--port 8091` 修改：

```bash
python3 scripts/run-pack-preview.py --port 8091
```

脚本启动 `PreviewSpringApplication`，后端直接提供构建后的 React/Tailwind 静态文件和 API，无需另起 Vite 开发服务器。

打开 `http://127.0.0.1:8090`。页面支持对象列表/详情、关系跳转、历史时间线、模型定义、Action 表单及审计/outbox查看。

API边界：

- `GET /api/v1/{Type}`、`/{Type}/{id}`、`/{Type}/{id}/history`：Foundry `RestApiRouter`。
- `POST /api/v1/actions/{Action}`：Foundry 注册 Manifest、`ApplicationService` 和 `ActionExecutor`。
- `POST /graphql`：Foundry `GraphqlApiRuntime` 动态生成的 Schema。
- `/api/model`、`/api/events`：体验台专属元数据和诊断接口。

对象列表、详情、历史、关系字段和 Action 不在体验台中重复实现；Controller 只保留前端兼容投影，内部统一委托 Foundry `RestApiRouter`。GraphQL 直接调用 `GraphqlApiRuntime`。

验证：

```bash
mvn -pl model-verification,pack-explorer -am test \
  -Dtest=DomainModelTest,DomainActionTest,SpringPreviewTest \
  -Dsurefire.failIfNoSpecifiedTests=false
npm --prefix pack-explorer/web run build
npm --prefix pack-explorer/web run test:e2e
```

它是模型确认工具，不是 Mirror 生产管理端。数据随进程退出消失，重启进程恢复初始合成数据。
