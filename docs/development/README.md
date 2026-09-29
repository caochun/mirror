# Mirror 开发文档

Mirror 在仓库根目录采用单一 Spring Boot 项目，后端位于 `src/main/java`，领域定义位于 `src/main/resources/domain-pack`，React/Tailwind 源码位于 `web/`。首个人员闭环已实现，使用独立 JDBC-H2 持久库、本地登录账号与组织范围校验。

| 文档 | 内容 |
| --- | --- |
| [当前进度](progress.md) | 已实现能力、测试证据和未验收边界 |
| [任务清单](tasks.md) | 已完成事项与后续工作 |
| [首个人员闭环](personnel-loop.md) | 页面、服务命令、权限、持久化和验收条件 |
| [参考样本初始化](reference-data.md) | Excel 参考数据的合成方式、场景与安全重放边界 |

开发遵循 [业务规约](../business/README.md)，原文分歧见 [待确认决定](../business/review-decisions.md)。只补齐实际业务需要的 Foundry 能力，不恢复完整上游复刻目标。

运行与配置见 [应用说明](running.md)，模型契约验证见 [测试说明](model-verification.md)。后续建设正式目录发布、来源接入及其他业务闭环，不将本地验收视为全系统上线。
