# 仅模型验证

本模块只有测试，无`src/main`、HTTP接口、业务处理器或前端。用于证明新Pack可以被当前Foundry编译并由内存/JDBC-H2存储表达，同时验证已登记Action的真实执行。

```bash
mvn -pl model-verification -am test -Dtest=DomainModelTest,DomainActionTest -Dsurefire.failIfNoSpecifiedTests=false
```

模型测试验证加载、关系基数/历史、事务回滚、唯一/枚举/不可变/乐观版本、独立贡献与抑制、语义版本引用和阅读键唯一性。fixture中的显式事务变更不表示已经实现标签算法、审核、发送、阅读或权限服务。

只创建临时内存数据库，不碰`.runtime/`。历史920项是已删除旧应用的阶段回归，不能继续当作当前Mirror应用验收；本轮以新的模型测试报告为准。


DomainActionTest另外通过真实ActionExecutor、CEL和Manifest运行14个事务Action；测试内授权器显式允许/拒绝，不是Mirror生产身份/组织策略。命令回执、成功审计及outbox验证均发生在隔离内存/H2库。未实现HTTP入口、动态名单发布或外部渠道。
