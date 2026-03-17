# Kafka 在 PaiSmart 项目中的用途

## 1. 作用概览

Kafka 在此系统主要用于文件异步后处理管道：
- 通过 `UploadController` 在文件合并后发送任务消息到 Kafka（生产方）
- 通过 `FileProcessingConsumer` 监听任务主题（消费方），执行文件下载、解析、向量化
- 处理过程采用重试 + 死信队列机制，保证稳定和可观测性

## 2. 配置入口（`KafkaConfig.java`）

- `spring.kafka.bootstrap-servers`：Kafka 集群地址
- `spring.kafka.topic.file-processing`：文件处理主主题
- `spring.kafka.topic.dlt`：死信主题
- `spring.kafka.consumer.group-id`：消费者组 ID
- `spring.kafka.consumer.auto-offset-reset`：偏移量重置策略
- `spring.kafka.consumer.properties.spring.json.trusted.packages`：反序列化白名单

### Producer 配置
- `acks=all`：全部 ISR 确认
- `enable.idempotence=true`：幂等生产
- `retries=3`：重试次数
- 事务前缀 `file-upload-tx-`（`producerFactory`）

### Consumer 配置
- key 反序列化：`StringDeserializer`
- value 反序列化：`JsonDeserializer`
- 默认 `ConcurrentKafkaListenerContainerFactory` + `DefaultErrorHandler`
- 重试：`FixedBackOff(3000L,4)` （最多 5 次）
- 失败后进入死信：`DeadLetterPublishingRecoverer` 到 `file-processing-dlt`

## 3. 生产者逻辑（`UploadController#mergeOrUpload相关`）

在用户提交文件合并成功后，生成 `FileProcessingTask`：

```java
FileProcessingTask task = new FileProcessingTask(
    request.fileMd5(),
    objectUrl,
    request.fileName(),
    fileUpload.getUserId(),
    fileUpload.getOrgTag(),
    fileUpload.isPublic()
);

kafkaTemplate.executeInTransaction(kt -> {
    kt.send(kafkaConfig.getFileProcessingTopic(), task);
    return true;
});
```

要点：
- 使用事务发送（`executeInTransaction`）保证原子性
- 包含权限（userId/orgTag/isPublic）

## 4. 消费者逻辑（`FileProcessingConsumer`）

监听主题：
```java
@KafkaListener(topics = "#{kafkaConfig.getFileProcessingTopic()}", groupId = "#{kafkaConfig.getFileProcessingGroupId()}")
public void processTask(FileProcessingTask task) { ... }
```

处理过程：
1. 根据 `task.filePath` 下载文件（本地路径或 http/https URL）
2. `parseService.parseAndSave(...)` 解析并存储内容
3. `vectorizationService.vectorize(...)` 向量化处理
4. 任何异常抛出会触发 `DefaultErrorHandler`，自动重试或送 DLT

## 5. 消息格式（`FileProcessingTask`）

```java
public class FileProcessingTask {
    private String fileMd5;
    private String filePath;
    private String fileName;
    private String userId;
    private String orgTag;
    private boolean isPublic;
}
```

序列化：
- 使用 Spring Kafka 的 `JsonSerializer`/`JsonDeserializer`
- JSON 格式方便扩展，例如：新增 `originType`, `chunkId`, `priority` 等

## 6. 设计选型理由

- 异步解耦：文件合并快速返回用户结果，解析+向量可放后台逐步执行
- 可扩展高吞吐：Kafka 适合大文件处理触发转后台任务
- 可用性强：幂等生产、事务、重试、DLT 带来>99.9% 可靠交付
- 追踪友好：任务对象中含 `userId/orgTag/isPublic`，便于权限审计与多租户隔离

## 7. 风险与提升建议

- 建议生产端加上清洗与验证：当 filePath 为空时直接拒绝
- 进一步与监控（Prometheus+Grafana）结合：消费延迟、重试次数、DLT 率
- 未来可加 `FileProcessingTask` 字段：`priority`, `fileSize`, `jobId`, `callbackUrl`
