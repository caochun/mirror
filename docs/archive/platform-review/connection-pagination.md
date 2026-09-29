# Foundry默认Connection与双向分页

日期：2026-09-28。Foundry提交：`17b7bcd`。完整核心覆盖目标继续。

## 本阶段

默认GraphQL复数字段改为上游的Connection形状，例如`items { edges { node { id } } totalCount pageInfo { ... } }`。旧数组列表明确选择`QueryMode.LEGACY_LIST`；已有itemsConnection后缀保留为同一连接接口。ActionMode独立选择动作契约，createLegacy同时启用旧JSON动作和旧数组列表。

接通first/after/last/before、双边界、零条查询及默认20/最大100的页大小。所有分页在对象授权、字段查询权限、过滤和排序之后执行，totalCount只统计完整可见匹配集合。修正固定上游last单独使用未取末页、before接近开头时可能越界的问题。REST `POST /api/v1/<Type>/query`采用同一页模型；Java/REST支持历史状态下的反向分页。

默认GraphQL返回形状和HTTP默认页大小需要调用方迁移，旧Java列表及旧GET列表保留。配置示例、游标及pageInfo边界见[ADR-0020](../../../foundry/spec/adr/0020-connection-pagination.md)。

## 验证

常规根reactor **512项**，其中Foundry **402项**，全部通过，无失败/错误/跳过。新增19项覆盖memory/H2、跨130条隐藏记录的完整双向遍历、末页/before/双边界/零条/越界、默认及最大页大小、字段/对象权限、排序、历史状态、租户/撤权及实际REST/GraphQL和显式旧列表。

独立探针在两种Provider均确认：默认GraphQL末页为a5/a6，可见总数7；before索引1且last=3只返回a0；first=0保留正确总数；旧数组列表仍可显式调用。运行中的服务JAR和业务库未改动。

## 尚未覆盖

游标仍为可见结果序列的位置，不是跨请求快照；对象更新、撤权或改变查询后可能移位。原生存储下推、批量授权、资源预算、跨读取快照、时间关系API、Consent、ObjectSet及其余核心范围继续推进。
