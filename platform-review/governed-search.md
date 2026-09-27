# Foundry受控文本搜索

日期：2026-09-28。Foundry提交：`47c966a`。完整核心覆盖目标继续。

## 本阶段

固定上游的memory按词匹配并计出现次数，PostgreSQL按完整字符串匹配并计命中字段数。Java以显式TERMS/PHRASE模式覆盖这两种规则，memory/JDBC行为一致，默认TERMS。

搜索先验证身份、类型、所有搜索字段及筛选条件，仅对可见对象/字段进行匹配、评分、高亮、统计和分页。隐藏字段不会让对象命中或提高分数，隐藏对象不占页。默认仅选可见的声明文本属性，显式隐藏字段拒绝，显式空字段列表不回退。评分相同时按ID稳定排序，返回位置游标和正确可见总数。

GraphQL新增与上游同名的search<Type>s、SearchHit_<Type>/SearchResult_<Type>。REST提供GET/POST `/api/v1/<类型>/search`；Java/REST还支持双时间搜索和includeDeleted。GET的search路径成为保留操作，ID恰为search的对象可通过Java/GraphQL单查；详见[ADR-0019](../foundry/spec/adr/0019-governed-search.md)的兼容说明。

## 验证

常规根reactor **493项**，其中Foundry **383项**，全部通过，无失败/错误/跳过。新增21项覆盖memory/H2、两种模式、评分、中文/Unicode/特殊字符、130条隐藏对象、字段/角色和高亮、过滤、历史/删除/租户/撤权、分页/越界/limit=0以及实际GET/POST/GraphQL。

独立探针在两种Provider均确认可见命中数2、TERMS首条得分3、PHRASE两条各1分；隐藏字段拒绝且highlights不含隐藏文本。运行中的服务JAR和业务库未改动。

## 尚未覆盖

当前为应用层文本子串搜索，不是全文索引系统。原生Storage SPI搜索、数据库查询/搜索/聚合及授权下推、资源预算、实际国产库和生产规模验收仍待完成。多词高亮合并为一份可见字段原文，默认字段根据Schema挑选；这些与上游内存的细节差异已记录。

Consent、ObjectSet、反向分页及其余核心能力继续按完整对齐计划推进。
