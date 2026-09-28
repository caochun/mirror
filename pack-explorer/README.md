# Mirror Pack 模型体验台

这是一个本地、隔离、合成数据的 Domain Pack 预览程序。它读取 `domain-pack/`，自动展示对象类型、字段、关系、历史和已注册 Action；不恢复旧 Mirror 应用，不连接 `.runtime/`，不连接外部渠道。

体验台的写入口只接受 Pack 中已注册的 Action，禁止通用对象 CRUD。默认是只读预览；切换“演示操作员”并携带本地预览 token 后，才能在内存数据上执行 Action。权限策略是演示策略，不是生产组织授权。

首次构建并启动（JDK 21+、Maven、Node.js）：

```bash
python3 scripts/run-pack-preview.py --build
```

已有构建可直接启动；端口可通过 `--port 8091` 修改：

```bash
python3 scripts/run-pack-preview.py
```

脚本从Maven测试报告提取依赖classpath，不使用旧应用JAR。后端直接提供构建后的React/Tailwind静态文件和API，无需另起Vite开发服务器。

打开 `http://127.0.0.1:8090`。页面支持对象列表/详情、关系跳转、历史时间线、模型定义、Action 表单及审计/outbox查看。数据随进程退出消失，刷新不会重置，重启进程才恢复初始合成数据。

当前验证：5项HTTP测试、37项模型/Action测试、React/TypeScript/Tailwind构建、2项Playwright桌面/移动端测试通过。

浏览器测试在独立8091端口启动临时服务，不修改正在体验的8090进程：

```bash
npm --prefix pack-explorer/web exec -- playwright install chromium
npm --prefix pack-explorer/web run test:e2e
```

建议先浏览“人员→关系→组织/任职/标签”，再选择“人员标签关联→人工抑制标签”：只读身份下可看到拒绝；切换演示操作员后确认执行，可看到历史新增与一条成功审计/outbox；“重放同一请求”应返回相同actionId且不重复写。首次阅读演示模拟人员p，不代表生产H5身份认证。

UI结构、字段/关系列表、枚举与参数表单来自实时Pack元数据；中文显示名、合成样例和演示授权策略是体验台的辅助配置。预览入口不是完整自动生成的Mirror业务流程，未启用GraphQL HTTP入口。

现有样例覆盖主要关联，部分对象类型没有实例，可在“模型定义”查看；对象列表每页100条，关系每类每方向最多100条。实例内存不持久化，不能用于生产验收。

模型与 Action 验证仍运行：

```bash
mvn -pl model-verification -am test -Dtest=DomainModelTest,DomainActionTest -Dsurefire.failIfNoSpecifiedTests=false
```

它是模型确认工具，不是 Mirror 生产管理端。
