# Foundry 平台核查历史归档

本目录保留 Foundry 对照 open-foundry 的历史审计、阶段实现报告和验证快照。文中的“当前”、完整覆盖目标、提交号和测试数量均属于记录当时，不代表当前开发目标或最新验收结果。

当前 Mirror 业务约束见[业务规约](../../business/README.md)，实际实现与验证见[开发进度](../../development/progress.md)。底座的设计决定与契约以 [Foundry 规约](../../../foundry/spec)及其代码、测试为准。

## 主要入口

- [与上游的能力对照](foundry-vs-open-foundry.md)：当时的审计范围与差异。
- [Mirror 所需底座范围](mirror-foundation-scope.md)：停止完整上游复刻时的范围判断。
- [关系路径与读取证据](action-relationship-paths.md)、[Action 副作用](action-side-effects.md)、[持久回执](transactional-receipts.md)：动作与恢复机制的阶段记录。
- [计算字段](computed-fields.md)、[Connection 分页](connection-pagination.md)、[关系导航](relationship-navigation.md)：查询能力的阶段记录。
- [模型激活](schema-activation.md)、[模型读绑定](schema-read-binding.md)、[时间语义修复](temporal-repair.md)：模型及历史机制的阶段记录。
- [原始审计快照](foundry-audit-observations.json)、[后续探针快照](foundry-audit-current.json)：迁移时原样保留的机器可读结果。

其余专题报告保留在同一目录，供追溯对应提交及验证过程。

## 配套审计工具

旧审计工具与报告一同归档于 [tools/](tools/)，不参与 Mirror 应用构建。需要追溯验证时，从仓库根目录运行：

```bash
mvn -f foundry/pom.xml test
python3 docs/archive/platform-review/tools/run.py
```

默认输出为 `docs/archive/platform-review/foundry-audit-current.json`；再次运行会更新此文件。若要保留归档快照，可传入已有目录下的新输出路径，例如：

```bash
python3 docs/archive/platform-review/tools/run.py /tmp/mirror-foundry-audit.json
```

本次目录归档没有重新执行平台审计，也没有将历史报告改写为最新验证结论。
