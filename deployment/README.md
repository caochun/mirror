# 部署配置

`application.yaml` 是业务仓库的部署参数模板。生产环境通过环境变量注入 openGauss/国产关系数据库、鹿路通和 OpenFGA 地址；Foundry 的 JDBC 方言负责数据库差异，业务服务只依赖 Storage SPI。

启动前需要：

1. 应用加载 `domain-pack/` 并执行 Schema Registry 迁移。
2. 配置 `MIRROR_JDBC_URL`、数据库账号、`LULUTONG_ENDPOINT` 和 `OPENFGA_ENDPOINT`。
3. 先部署 OpenFGA 模型，再启用业务写入。
4. 生产环境保留 outbox、审计和双时态历史表，不得使用内存 Provider。
