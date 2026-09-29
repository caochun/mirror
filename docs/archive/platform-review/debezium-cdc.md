# Foundry Debezium CDC消费

日期：2026-09-28。Foundry本阶段：CDC解码与消费测试已完成，完整上游核心覆盖目标继续。

## 本阶段

新增Debezium JSON envelope解码，支持`c/u/d/r`、tombstone、Kafka Connect Decimal/Date/Timestamp/Time及嵌套struct/array/map。解码器校验topic、数据库/schema/table、主键、重复key、尾随JSON、长度/深度和UTF-8，事件位置固定为topic/partition/offset。

`CdcTransport`由宿主提供固定分区会话；Kafka实现关闭自动提交，使用`read_committed`，本地检查点优先seek，逐条`commitSync`。`CdcConsumer`把对象/关系、来源、回执、检查点和审计/outbox交给已有事务同步服务，只有本地事务成功后才确认 broker 消息。失败停止消费，重投由回执幂等处理；tombstone只确认，不自行创建删除事实。

## 验证

Foundry全回归 **804 项**，无失败/错误/跳过；新增测试覆盖解码及消费确认、失败停点、重投、授权、暂停/恢复和关闭。此前所有测试语义保持不变。

## 边界

Kafka真实连接、Schema Registry格式、rebalance/租约、断线重连、跨进程背压、死信/告警、延迟吞吐、自动调度及生产数据库验收仍待完成。详见[ADR-0033](../../../foundry/spec/adr/0033-debezium-cdc-consumption.md)。Mirror运行库和服务JAR未改动。
