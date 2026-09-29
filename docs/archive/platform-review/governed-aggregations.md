# Foundry受控对象聚合

日期：2026-09-28。Foundry提交：`5a73285`。完整核心覆盖目标继续。

## 本阶段

对照固定上游v0.3.0，接通COUNT/SUM/AVG/MIN/MAX、单字段/复合分组、条件筛选、多字段组排序、组分页及分页前totalGroups。REST增加`POST /api/v1/<类型>/aggregate`，GraphQL增加与上游同名的`<对象小写名>Aggregate`及AggregateFieldInput/Group/Result。

所有统计在可见且匹配的对象集合中进行；统计字段、分组字段和筛选条件均执行字段权限检查，隐藏对象不会进入总数或产生独有分组。字段验证不会因为无数据而跳过，别名/分组冲突明确拒绝。

COUNT(*)与COUNT(field)区分全部对象和非null属性。无groupBy的空集合返回一个COUNT=0、其他数值指标=null的总体组；有groupBy的空集合返回0组。limit=0保留正确总组数。数值累计避免普通浮点加法误差，超出有限double范围时明确失败。Java/REST支持双时间历史统计与includeDeleted。

完整语义、上游差异和示例见[ADR-0018](../../../foundry/spec/adr/0018-governed-aggregations.md)。

## 验证

常规根reactor **472项**，其中Foundry **362项**，全部通过，无失败/错误/跳过。新增23项覆盖memory/H2、五种函数、空值/空组/JSON复合组、130条隐藏对象、角色和敏感字段、别名、排序分页、时间历史、租户/撤权、数值精度/溢出及实际HTTP/GraphQL。

独立探针在memory/H2确认：可见COUNT=3、SUM=4、AVG=2；隐藏对象的独有组不出现；limit=1时totalGroups仍为2；隐藏指标拒绝。运行中的服务JAR和业务库未改动。

## 尚未覆盖

目前聚合在应用层基于一次对象读取完成，尚未接入原生Storage SPI聚合或SQL GROUP BY/授权下推，也未完成生产规模和实际国产数据库验收。各指标来自同一对象集合，不代表跨外部权限服务的统一快照。

搜索、Consent、ObjectSet、反向分页、原生查询下推以及其余上游能力继续按完整对齐计划推进。
