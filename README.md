# 政务系统对象库业务项目

本仓库是基于 [Open Foundry](https://github.com/caochun/foundry) 构建的政务对象库具体业务项目。Foundry 作为 Git submodule 位于 [`foundry/`](foundry/)；本仓库维护政务领域对象模型、业务 Action、标签/风险/提醒等上层逻辑和部署配置。

## 目录

- [`business-spec/`](business-spec/)：政务业务规约、对象模型、工作流和任务计划。
- `domain-pack/`：按业务规约从零实现的政务 Domain Pack。
- `foundry/`：通用对象关系和状态历史底座，来自独立仓库。
- 根目录 DOCX/XLSX：原始业务材料和测试数据，已通过 `.gitignore` 忽略。

## 领域边界

基础 Domain Pack 只描述对象、关系和生命周期。标签计算、风险判断、廉洁提醒、鹿路通送达和具体监督流程在本业务仓库扩展，Foundry 不包含这些政务业务语义。

## 获取和验证 Foundry

```bash
git clone --recurse-submodules git@github.com:caochun/mirror.git
cd mirror/foundry
mvn test
```

Foundry 的版本通过 submodule 固定；更新平台版本时，在本仓库更新 submodule 指针并运行业务验证。
