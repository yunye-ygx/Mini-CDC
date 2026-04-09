# MySQL Binlog 位点持久化与重启恢复方案

## 一、背景

当前项目的核心链路为：

`MySQL -> Binlog 监听 -> 事件解析 -> Kafka -> Redis Consumer -> Redis`

现状是：

- 项目启动后，`BinaryLogClient` 能持续监听 MySQL binlog
- 当监听到 `WRITE_ROWS / UPDATE_ROWS` 事件时，程序会解析数据并发送到 Kafka
- Redis Consumer 再从 Kafka 消费消息并写入 Redis

当前存在的问题是：

- `BinaryLogClient` 内部虽然会自动推进 binlog 位点
- 但这个位点只是运行时的内存状态，没有持久化到外部存储
- 项目重启后，也没有能力从上次处理完成的位置继续恢复
- 因此，当服务停机期间 MySQL 产生了新的 binlog 事件时，重启后这些事件可能无法被补采并同步到 Redis

这说明当前系统缺少：

- 位点持久化能力
- 重启恢复能力

---

## 二、目标

本次方案目标如下：

1. 为 `WRITE_ROWS / UPDATE_ROWS` 事件增加位点持久化能力
2. 在项目启动时读取上次持久化的位点，并从该位点继续监听 binlog
3. 在 Kafka 成功接收当前 row event 后，持久化新的 checkpoint
4. 保证服务重启后能够继续补采停机期间未处理完成的 binlog 数据
5. 本次方案先采用 **row event 级别的 checkpoint 提交策略**
6. 本次方案目标语义为：
   - **at-least-once**
   - **允许重复**
   - **不允许丢失**

本次方案暂不处理：

- 按事务 `XID/COMMIT` 提交 checkpoint
- 消息幂等消费
- 全量快照
- binlog 被 purge 后的自动补偿恢复
- 多表统一位点管理

---

## 三、设计

### 3.1 核心概念

需要区分两个位点概念：

#### 1. 内部位点

由 `BinaryLogClient` 在运行时自动维护：

- 收到一个 binlog event，就自动推进一次
- 仅存在于内存中
- 进程退出后丢失
- 不可作为重启恢复依据

#### 2. checkpoint

由系统自行维护并持久化：

- 表示“最后一条已经安全发送到 Kafka 的 row event 之后的位置”
- 用于项目重启后的恢复
- 必须保存到外部存储

---

### 3.2 checkpoint 定义

本方案中 checkpoint 由以下信息组成：

- `binlog_filename`
- `binlog_position`

其含义为：

**下次启动时，从该 `binlog filename + position` 继续读取 binlog。**

注意：

- 这里保存的不是模糊的“当前处理到哪里”
- 而是“当前 row event 成功发送到 Kafka 后，下一次恢复应该从哪里开始”

---

### 3.4 checkpoint 提交策略
本次方案采用 row event 成功发送后提交 checkpoint。

提交规则如下：

TABLE_MAP 不提交 checkpoint
WRITE_ROWS / UPDATE_ROWS 才产生候选 checkpoint
只有在 Kafka 返回成功 ack 后，才将该 row event 对应的位点持久化
checkpoint 持久化失败时，不向前推进恢复点
这样设计的原因是：

如果在 Kafka 成功之前就提交 checkpoint，可能导致数据丢失
如果 Kafka 成功后、checkpoint 持久化前宕机，重启后会重复发送，但不会丢失
因此，本方案采用：

宁可重复，不可丢失
### 3.5 启动恢复策略
项目启动时执行以下步骤：

读取 cdc_offset 表中的 checkpoint
如果 checkpoint 存在：
使用 binlog_filename + binlog_position 初始化 BinaryLogClient
从该位置继续监听
如果 checkpoint 不存在：
采用默认首次启动策略
本方案建议首次启动策略为：

LATEST
即首次启动时从当前最新 binlog 位点开始监听
不自动回放历史数据
这样做的目的：

降低初版实现复杂度
明确系统行为
避免 checkpoint = null 时语义不清晰

### 四、链路变化
### 4.1 修改前链路
修改前链路：

项目启动
BinaryLogClient 直接连接 MySQL
未显式指定恢复位点
监听到 TABLE_MAP
更新表映射
监听到 WRITE_ROWS / UPDATE_ROWS
解析事件
发送 Kafka
Redis Consumer 消费 Kafka 写 Redis
项目宕机后，内存位点丢失
重启后无法从上次处理位置恢复
存在的问题：

停机期间产生的数据可能漏采
无法证明当前已经消费到哪个位置
缺少断点续传能力
### 4.2 修改后链路
修改后链路：

项目启动
先读取 cdc_offset 表中的 checkpoint
如果有 checkpoint，则从该位点恢复监听
如果没有 checkpoint，则按首次启动策略从当前最新位点开始监听
收到 TABLE_MAP
仅维护表映射
收到 WRITE_ROWS / UPDATE_ROWS
解析事件
构造业务消息
记录候选 checkpoint
发送 Kafka
Kafka ack 成功后
持久化新的 checkpoint
Redis Consumer 正常消费 Kafka 写 Redis
项目重启后
从最近一次成功提交的 checkpoint 继续监听
修改后能力提升：

支持位点持久化
支持重启恢复
支持停机期间变更补采
保证 at-least-once 语义
### 五、涉及模块影响
本次改动预计影响以下模块：

### 5.1 BinlogCdcLifecycle
影响最大，需要新增：

启动时读取 checkpoint
初始化 BinaryLogClient 的恢复位点
处理 row event 时生成候选 checkpoint
在 Kafka 成功后触发 checkpoint 提交
### 5.2 CdcEventPublisher
需要支持：

获取 Kafka 发送成功的确认结果
让调用方能够在“发送成功之后”提交 checkpoint
### 5.3 MiniCdcProperties
需要新增 checkpoint 相关配置，例如：

connector 标识
checkpoint 是否启用
首次启动策略
### 5.4 application.yml
需要新增配置项：

connector name
checkpoint 配置
启动恢复策略
### 5.5 新增模块
建议新增以下模块：

Checkpoint
描述位点数据结构
CheckpointStore
负责位点读写
CheckpointManager
负责位点推进和持久化时机控制
### 六、验收标准
本方案完成后，应满足以下验收标准：

### 6.1 功能验收
项目运行期间，对目标表执行 INSERT / UPDATE

能正常发送到 Kafka
Redis 能正常同步更新
每次 WRITE_ROWS / UPDATE_ROWS 成功发送到 Kafka 后

cdc_offset 表中的 checkpoint 会被更新
服务关闭后，在停机期间往 MySQL 插入或更新数据

项目重新启动后，能够从上次 checkpoint 继续监听
能够补采停机期间的数据
Kafka 和 Redis 最终可见该数据
### 6.2 异常场景验收
在 Kafka 发送前宕机

checkpoint 不推进
重启后事件会重新读取
不丢失数据
在 Kafka 已成功、checkpoint 未持久化时宕机

checkpoint 保持旧值
重启后事件会被重新读取并再次发送
允许重复，但不丢失
在 checkpoint 成功持久化后宕机

重启后从新 checkpoint 恢复
不重复处理已完成事件
### 6.3 可观测性验收
能通过 cdc_offset 表明确看到当前已提交的：

binlog_filename
binlog_position
updated_at
能根据数据库记录判断：

当前项目恢复点是否已推进
当前恢复点是否与预期一致
### 七、风险与限制
### 7.1 当前方案风险
### 1. 仅按 row event 提交 checkpoint，不按事务提交
风险：

如果一个事务中包含多条 row event
当前方案可能只处理了一部分事件就推进 checkpoint
在复杂事务场景下不够严谨
说明：

这是本次方案明确接受的简化设计
后续可升级为按 XID/COMMIT 提交 checkpoint
### 2. 允许重复，不保证 exactly-once
风险：

Kafka 成功但 checkpoint 未成功落库时
重启后可能重复发送相同消息
说明：

本次目标是 at-least-once
后续需要通过消息幂等或下游幂等消费处理重复问题
### 3. 无法处理 binlog 被 purge 的情况
风险：

如果 checkpoint 指向的 binlog 文件或位置已被 MySQL 清理
系统将无法按 checkpoint 恢复
说明：

本次方案不处理该场景
后续需要考虑全量快照或人工补偿机制
### 4. 首次启动不补历史数据
风险：

若首次启动策略设置为 LATEST
则项目只能监听启动后的新增变更
无法自动同步历史存量数据
说明：

本次为简化实现而接受该限制
后续如有需要，可新增 snapshot 能力

