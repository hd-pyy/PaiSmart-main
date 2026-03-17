# MinIO 在 PaiSmart 项目中的用途

## 1. 作用概览

本项目使用 MinIO 作为对象存储（类似 S3），主要负责文件分片、合并后的文件、文档下载/预览、与后续解析向量化任务的数据持久化。

## 2. 核心配置（`MinioConfig.java`）

- `minio.endpoint`：MinIO 服务地址
- `minio.accessKey`：访问密钥
- `minio.secretKey`：私密密钥
- `minio.publicUrl`：外部公开访问 URL

`MinioClient` 通过 Spring `@Bean` 注入全局使用。

## 3. 上传与分片存储（`UploadService`）

主要逻辑在 `UploadService.uploadChunk(...)`：
- 分片对象路径：`chunks/<fileMd5>/<chunkIndex>`;
- 存储桶：`uploads`;
- 使用 `MinioClient.putObject` 上传分片;
- `ChunkInfo` 与 `FileUpload` 表记录元信息，并通过 Redis bitmap 标记已上传分片。

检查分片存在流程：
- Redis 标记 +数据库校验 `ChunkInfo`; 
- 若存在则 `minioClient.statObject(...)` 验证 object 真实存在，避免脏数据。

合并文件后（`UploadService.mergeChunks`）:
- 读取分片，生成 `merged/<fileName>` (或类似路径)
- 继续推送 Kafka 任务 `FileProcessingTask`（fileMd5/filePath源自MinIO对象路径）

## 4. 文档映射与管理（`DocumentService`）

### 4.1 生成下载链接
使用 MinIO 预签名 URL：
- `GetPresignedObjectUrlArgs.builder().method(Method.GET).bucket("uploads").object("merged/<fileName>").expiry(3600).build()`

### 4.2 预览文件内容
- 文本文件：`minioClient.getObject(GetObjectArgs.builder()...build())`读取前10KB
- 非文本文件：返回文件元信息（大小、类型、URL）

### 4.3 删除文件
执行 `minioClient.removeObject(RemoveObjectArgs.builder().bucket("uploads").object("merged/<fileName>").build())`，同时从 `FileUpload`, `DocumentVector`, Elasticsearch 清理。

## 5. 与异步处理（Kafka）协同
`UploadController` 合并文件成功后，构建 `FileProcessingTask`：
- `fileMd5`, `objectUrl`（MinIO路径）, `fileName`, `userId`, `orgTag`, `isPublic`
- 发送 Kafka `file-processing` 主题

`FileProcessingConsumer` 任务消费后：
- 先下载 `task.getFilePath()`（可能直接 MinIO URL）;
- 解析内容并保存;
- 向量化处理。

## 6. 数据格式与存储路径设计

### 6.1 分片路径
- `uploads/chunks/<fileMd5>/<chunkIndex>`
- 便于按照文件ID和分片索引定位，支持断点续传

### 6.2 合并后路径
- `uploads/merged/<fileName>` 或 `uploads/merged/<fileMd5>/<fileName>`（具体依据服务中拼接规则）
- 支持按项目/文件名查询

### 6.3 公开访问
- `minioPublicUrl` + `objectName` 可生成可公开访问链接（文件URL）

## 7. 设计选型理由

- MinIO 是 S3 兼容对象存储，可与本地/云环境无缝部署
- 用于大文件分片存储，避免 database BLOB 和本地磁盘慢
- 可生成预签名链接做安全下载（短期有效）
- 结合 Kafka 异步任务可实现快速前端响应与后台解析

## 8. 可优化方向

- 分片上传加入 `etag`/`checksum` 验证（目前使用 MD5 + statObject 校验）
- 使用 `PresignedPutObjectUrl` 支持客户端直接直传
- 增加 MinIO 存储桶生命周期策略，自动清理旧临时分片
- 引入缓存层（CDN）加速下载
