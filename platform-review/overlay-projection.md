# Foundry Overlay源投影

日期：2026-09-28。Foundry Overlay阶段已实现，完整上游核心覆盖继续。

`OverlayEngine` 按 mapping 的 OVERLAY 模式从连接器读取源记录，执行同一 RecordMapper，返回不可变的源投影；默认 TTL 为 PT5M。数值/字符串主键规范化后共享缓存键，缓存命中不访问源端，过期、清除、缺失和删除均有明确行为。投影 mutation 永久拒绝，不写 StorageProvider、不产生对象历史、来源回执或检查点。

`ManagedOverlay` 接入托管 ConnectorRegistry 和 JDBC fullExtract，初始化/关闭时管理连接器生命周期。writeback=true、关系映射和错误模式在入口拒绝，防止把只读投影当成本地事实或未授权关系。

新增测试覆盖 TTL、键规范化、缓存清除、删除、只读、不可变 lineage、JDBC 来源和生命周期。Foundry全回归 **810项**，根项目全回归 **920项**，全部通过，无失败/错误/跳过。跨节点缓存失效、源端一致快照、REST/国产库生产验证、关系投影和 writeback 继续，详见[ADR-0034](../foundry/spec/adr/0034-overlay-projection.md)。
