# 模型契约验证

模型契约测试位于 `src/test/java/gov/mirror/model/`，已并入根 Spring Boot 项目，不再使用独立 Maven 模块。它们验证 `src/main/resources/domain-pack/` 可由 Foundry 编译、存储并执行已登记的原子 Action。

```bash
mvn test -DskipFrontend=true -Dtest=DomainModelTest,DomainActionTest
```

模型测试验证加载、关系基数/历史、事务回滚、唯一/枚举/不可变/乐观版本、独立贡献与抑制、语义版本引用和阅读键唯一性。fixture中的显式事务变更不表示已经实现标签算法、审核、发送、阅读或权限服务。

先按[运行说明](running.md)安装 Foundry 依赖，再从仓库根目录运行。测试只创建临时内存/H2数据库，不碰`.runtime/`。模型与Action共43项；应用集成测试与资源加载测试分别位于 `src/test/java/gov/mirror/app/`，完整构建执行全部50项。


DomainActionTest另外通过真实ActionExecutor、CEL和Manifest运行14个事务Action；测试内授权器显式允许/拒绝，不是Mirror生产身份/组织策略。命令回执、成功审计及outbox验证均发生在隔离内存/H2库。未实现HTTP入口、动态名单发布或外部渠道。
