\# 项目名称

Mini CDC Sync



\# 项目定位

这是一个面向个人开发者和小团队的轻量级业务数据同步工具。

项目初期目标是跑通核心链路，后续可逐步演进为可复用的独立同步组件，并作为求职项目展示我对数据同步、Kafka 和基础中间件能力的理解。



\# 项目背景

在业务开发中，经常需要将 MySQL 中的数据同步到 Redis、Elasticsearch 等中间件中。

传统方案通常是在业务代码中通过“本地表 + MQ”或直接写同步逻辑来完成数据分发，这种方式存在以下问题：



\- 业务代码侵入性强

\- 相同同步逻辑在不同项目中重复开发

\- 后续维护成本高

\- 业务系统需要额外关心数据同步链路



因此，我希望实现一个轻量级的 CDC 数据同步工具，通过监听 MySQL 数据变更并发送到 Kafka，再由下游消费者完成 Redis 等中间件的数据更新，从而降低业务层重复开发成本。



\# 项目目标

第一版目标聚焦于“跑通最小闭环”，即：



\- 监听 MySQL 指定表的数据变更

\- 解析 insert 和 update 事件

\- 将变更事件以 JSON 格式发送到 Kafka

\- 由消费者订阅 Kafka 消息并同步写入 Redis



\# 典型使用场景

\- MySQL 数据同步到 Redis，作为缓存数据源

\- MySQL 数据变更解耦后转发给其他下游系统

\- 减少项目中针对 Redis / ES 同步编写重复逻辑的工作量



\# 目标用户

\- 当前阶段：自己使用

\- 后续阶段：希望沉淀为一个可分享给他人的小型同步工具



\# MVP 范围

第一版仅支持以下能力：



\- 仅支持 MySQL

\- 仅支持单机运行

\- 仅支持监听一张业务表

\- 仅支持 insert 和 update

\- 仅支持发送到 Kafka

\- 仅支持下游同步到 Redis

\- 通过配置文件写死监听的数据库名和表名

\- 消息格式统一为 JSON

\- Java 服务本地启动

\- MySQL 本机运行

\- Kafka 和 Redis 通过 Docker 运行



\# 非目标

第一版暂不考虑以下能力：



\- delete 同步

\- 断点续传

\- 异常恢复

\- 失败重试

\- 多表监听

\- UI 管理界面

\- 高可用部署

\- 封装成 jar / starter

\- Elasticsearch 同步演示



\# 核心链路

MySQL -> Binlog 监听 -> 事件解析 -> Kafka -> Redis Consumer -> Redis



\# 第一版演示场景

以用户表作为演示对象：



\- 修改 MySQL 用户表数据

\- CDC 服务监听到变更

\- 变更消息发送到 Kafka Topic

\- Redis 消费者收到消息后，用主键作为 key 保存最新用户数据



\# 演示表设计

建议第一版使用 user 表，字段如下：



\- id：主键

\- username：用户名

\- nickname：昵称

\- email：邮箱

\- status：状态

\- created\_at：创建时间

\- updated\_at：更新时间



示例：



CREATE TABLE user (

&#x20; id BIGINT PRIMARY KEY AUTO\_INCREMENT,

&#x20; username VARCHAR(64) NOT NULL,

&#x20; nickname VARCHAR(64),

&#x20; email VARCHAR(128),

&#x20; status INT NOT NULL DEFAULT 1,

&#x20; created\_at DATETIME NOT NULL,

&#x20; updated\_at DATETIME NOT NULL

);



\# Redis 存储设计

\- key：user:{id}

\- value：用户最新数据 JSON



示例：

user:1 -> {"id":1,"username":"tom","nickname":"Tom","email":"tom@test.com","status":1,...}



\# Kafka 设计

第一版仅使用一个 Topic：



\- topic：user-change-topic



后续再考虑多表对应多 topic 或路由策略。



\# 事件消息设计

第一版消息体建议如下：



{

&#x20; "database": "demo\_db",

&#x20; "table": "user",

&#x20; "eventType": "UPDATE",

&#x20; "primaryKey": {

&#x20;   "id": 1

&#x20; },

&#x20; "before": {

&#x20;   "nickname": "Tom"

&#x20; },

&#x20; "after": {

&#x20;   "id": 1,

&#x20;   "username": "tom",

&#x20;   "nickname": "Tommy",

&#x20;   "email": "tom@test.com",

&#x20;   "status": 1

&#x20; },

&#x20; "timestamp": 1710000000000

}



\# 为什么消息中保留主键

虽然第一版只做简单同步，但主键仍然必须保留：



\- Redis 写入时需要明确 key

\- 后续支持 delete 时必须依赖主键定位数据

\- 后续做幂等、去重、覆盖更新时需要唯一标识

\- Kafka 消息也可以基于主键进行分区或 key 设置



\# 项目价值

这个项目的核心价值不在于“重复造 Canal”，而在于：



\- 面向个人和小团队场景做轻量化裁剪

\- 聚焦业务数据同步场景，而不是大而全中间件

\- 降低业务代码同步逻辑侵入

\- 为后续封装成可复用 jar 打基础

\- 作为求职项目展示我对 CDC、Kafka、Redis、数据同步链路的理解



\# 技术选型

\- 开发语言：Java

\- JDK：17

\- 框架：Spring Boot

\- 消息中间件：Kafka

\- 缓存：Redis

\- 数据源：MySQL

\- 接口文档：Knife4j（后续用于测试展示）



\# 第一版成功标准

满足以下条件即可认为第一版完成：



\- 服务可以启动

\- 能监听指定 MySQL 用户表的 insert / update

\- 能将变更消息发送到 Kafka

\- Redis 消费者能消费消息并写入 Redis

\- 按照演示流程可以清晰展示完整链路



\# 后续演进方向

第二版及以后计划补充：



\- 支持 delete

\- 支持断点续传

\- 支持配置化多表监听

\- 支持失败重试

\- 支持更通用的数据路由能力

\- 支持封装成独立 jar 或 starter

\- 支持更多同步目标，如 Elasticsearch



\# 演示流程

\- 启动 MySQL

\- 启动 Kafka 和 Redis

\- 启动 CDC 服务

\- 向 user 表插入或更新一条数据

\- 观察 Kafka 收到变更事件

\- 观察 Redis 中生成或更新 user:{id} 数据



\# 项目亮点

\- 面向业务数据同步场景，减少重复开发

\- 通过监听数据库变更实现低侵入同步

\- 采用 Kafka 解耦数据变更与下游消费

\- 具备后续演进为通用同步组件的潜力

\- 适合作为展示中间件能力和工程抽象能力的求职项目



