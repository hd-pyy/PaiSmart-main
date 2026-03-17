# 前端文件上传完整流程（PaiSmart）

本文档按前后端链路，描述从用户选择文件到文件解析/向量化全流程，包括中间状态与关键点。

---

## 1. 前端入口：上传弹窗（UI）

文件：`frontend/src/views/knowledge-base/modules/upload-dialog.vue`

### 1.1 表单字段
- 组织标签 `orgTag`（管理员可选），`orgTagName`
- 是否公开 `isPublic`
- 文件列表（`NUpload`，支持单文件、`uploadAccept`）

### 1.2 提交逻辑
- 用户点击“保存”调用 `handleSubmit`
- 调用 `store.enqueueUpload(model.value)`

---

## 2. 前端业务层：任务管理 + 分片上传

文件：`frontend/src/store/modules/knowledge-base/index.ts`

### 2.1 入口：`enqueueUpload(form)`
- 计算文件 MD5 (`calculateMD5(file)`)
- 已存在任务判断：
  - 已完成 -> 直接提示文件已存在
  - 正在上传 -> 提示重复上传
  - 中断状态 -> 恢复任务并 `startUpload()`
- 新任务对象：
  - `file`, `fileMd5`, `fileName`, `totalSize`, `uploadedChunks`, `progress`, `status`, `orgTag`, `isPublic`
  - `status` 初始为 `UploadStatus.Pending`
- 任务入队 `tasks.value.push(newTask)`，启动 `startUpload()`

### 2.2 并发管理：`startUpload()`
- 最大并发（`activeUploads`）为 3
- 从 `Pending` 任务中提取第一个
- 任务状态更新为 `Uploading`, `activeUploads.add(fileMd5)`

### 2.3 分片循环上传
- 计算 `totalChunks = ceil(totalSize / chunkSize)` (`chunkSize=5MB`)
- 遍历每个 `i`:
  - 如果 `i` 在 `uploadedChunks` 中，跳过
  - 设置 `task.chunkIndex = i`
  - 调用 `uploadChunk(task)`
  - 如果失败，抛异常并置 `status = UploadStatus.Break`

### 2.4 `uploadChunk(task): POST /api/v1/upload/chunk`
- 请求体（multipart/form-data）：
  - `file`（当前切片内容）
  - `fileMd5`, `fileName`, `totalSize`, `chunkIndex`, `orgTag`, `isPublic`
- 后端返回: `uploaded`（已上传分片数组），`progress`（百分比）
- 更新 `task.uploadedChunks` 和 `task.progress`
- 满足 `uploaded.length === totalChunks` 后调用 `mergeFile(task)`

### 2.5 `mergeFile(task): POST /api/v1/upload/merge`
- 请求体：`{ fileMd5, fileName }`
- 成功后将 `task.status = UploadStatus.Completed`

---

## 3. 后端接口：上传分片 + 状态查询 + 合并

文件：`src/main/java/com/yizhaoqi/smartpai/controller/UploadController.java`

### 3.1 上传分片：`@PostMapping("/chunk")`
- 验证：
  - `chunkIndex == 0` 时做文件类型检验（`FileTypeValidationService`）
  - 若未指定 `orgTag` 则拉取 `userService.getUserPrimaryOrg(userId)`
- 调用 `uploadService.uploadChunk(...)`
- 返回Object：`{ code:200, message:'分片上传成功', data:{ uploaded, progress } }`

### 3.2 查询进度：`@GetMapping("/status")`
- 参数：`file_md5`, `userId`
- 调用 `uploadService.getUploadedChunks` + `getTotalChunks`
- 返回 `uploaded`, `progress`, `fileName`, `fileType`

### 3.3 合并分片：`@PostMapping("/merge")`
- 合并逻辑：`uploadService.mergeChunks(fileMd5,fileName,userId)`
- workflow：
  1. 校验任务存在与权限 `FileUpload`（`fileMd5` + `userId`）
  2. 发起 Kafka 任务 `FileProcessingTask` 执行文件解析与向量化
  3. 返回 `{code:200,message:'文件合并成功，任务已发送到 Kafka', data:{object_url}}`

---

## 4. 核心后端实现：`UploadService`（MinIO + Redis + DB）

文件：`src/main/java/com/yizhaoqi/smartpai/service/UploadService.java`

### 4.1 基本依赖
- `MinioClient`（MinIO 对象存储）
- `RedisTemplate<String,Object>`（已上传分片位图标记与状态判断）
- `FileUploadRepository`, `ChunkInfoRepository`（元数据持久化）

### 4.2 uploadChunk 关键过程：
1. file_upload 记录存在性检查；若不存在则新建
2. 通过 `isChunkUploaded` 检查 Redis bitmap（`upload:userId:fileMd5`）
3. 如果标记但数据库无直接或 MinIO 无该对象则重新上传
4. 将切片上传到 MinIO `bucket=uploads`, `object=chunks/<fileMd5>/<chunkIndex>`
5. 标记 Redis 该分片已上传 `setBit`，并写 `ChunkInfo` 保存分片记录

### 4.3 mergeChunks 关键过程：
1. 查各分片 `ChunkInfo`，校验数量
2. 逐个 `statObject` 确认分片存在
3. 合并 `minioClient.composeObject(bucket:"uploads", object:"merged/<fileName>", sources: chunkPaths)`
4. 删除分片对象 + 删除 Redis `upload:userId:fileMd5` 标记
5. 更新 `FileUpload` 状态 `status=1`、`mergedAt`，并生成 `MinIO` 预签名 URL

---

## 5. 异步任务：Kafka + 文件处理

文件：
- `src/main/java/com/yizhaoqi/smartpai/controller/UploadController.java`
- `src/main/java/com/yizhaoqi/smartpai/model/FileProcessingTask.java`
- `src/main/java/com/yizhaoqi/smartpai/consumer/FileProcessingConsumer.java`

### 5.1 生产消息
`UploadController.mergeFile` 在合并后构建 `FileProcessingTask`：
```java
new FileProcessingTask(fileMd5, objectUrl, fileName, userId, orgTag, isPublic)
```
并 `kafkaTemplate.executeInTransaction` 发送 `fileProcessingTopic`。

### 5.2 消费处理
`FileProcessingConsumer.processTask(FileProcessingTask task)`：
1. 下载文件（本地路径或 URL）
2. 解析内容 `parseService.parseAndSave(...)`
3. 向量化 `vectorizationService.vectorize(...)`
4. 异常抛出由 Kafka `DefaultErrorHandler` 触发重试/死信（`fileProcessingDltTopic`）

---

## 6. 数据和状态模型

### 6.1 前端模型 `Api.KnowledgeBase.UploadTask`
- file (File)
- fileMd5
- fileName
- totalSize
- isPublic
- orgTag/orgTagName
- chunkIndex
- uploadedChunks
- progress
- status (`UploadStatus`)

### 6.2 后端 DB 模型
- `FileUpload`：fileMd5, fileName, userId, status, totalSize, orgTag, isPublic, mergedAt...
- `ChunkInfo`：fileMd5, chunkIndex, chunkMd5, storagePath...

### 6.3 MinIO 路径
- 分片：`uploads/chunks/<fileMd5>/<chunkIndex>`
- 合并：`uploads/merged/<fileName>`

---

## 7. 错误与容错

- 文件类型错误 -> `400` 返回（第一片校验）
- `uploadChunk` 异常 -> `500` 返回
- `mergeChunks` 异常 -> 抛 Runtime` -> `500`
- 前端 `startUpload` 捕获失败，置状态 `Break`，并继续下一任务
- 断点续传：已成功分片(Redis)跳过上传

---

## 8. 快速端到端顺序图

1. 前端 `NUpload` 文件选择 -> `handleSubmit` -> `enqueueUpload`
2. `calculateMD5` -> 构建任务 -> `startUpload`
3. 循环切片 `uploadChunk` -> `POST /api/v1/upload/chunk`
4. 后端 `UploadController.uploadChunk` -> `UploadService.uploadChunk` -> MinIO `putObject` -> Redis bit + DB chunk
5. 前端 `mergeFile` -> `POST /api/v1/upload/merge`
6. 后端 `UploadController.mergeFile` -> `UploadService.mergeChunks` -> MinIO 合并 + cleanup + `fileUpload` 状态更新
7. `UploadController` 发送 Kafka 任务
8. `FileProcessingConsumer` 处理文件解析与向量化

---

## 9. 重点优化建议

- 前端可在暂停/恢复、断网重试与并发控制方面进一步增强。
- 服务器端建议在 `merge` 逻辑里加入幂等检查（同一个fileMd5多次合并时安全）。
- MinIO 对象合并可能按大小限制，必要时使用服务端拼接方式替代`
- 任务状态可添加全量流转日志，便于运维追踪。
