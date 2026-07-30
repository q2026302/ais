# 实施需求：消息缓存（增量拉取 + 软删除）

## 后端改动

### 1. Message 实体加 deleted 字段
- `Message.java`: `@Column(nullable = false) private boolean deleted = false;`
- 注意 GraalVM native image，字段更新后需要注册反射

### 2. getMessages 加 ?since 参数
- `SessionController.java`: `getMessages()` 加 `@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) LocalDateTime since`
- `ImageGenerationService.java`: 新方法 `getMessagesSince(sessionId, since)` → `where session.id = ?1 and updatedAt > ?2 and deleted = false`
- 无 since 参数时行为不变（全量返回，过滤 deleted = false）

### 3. deleteMessage 改为软删除
- `ImageGenerationService.deleteMessage()`: 不再 `messageRepository.delete(m)`，改为 `m.setDeleted(true); messageRepository.save(m)`
- 配套清理附件文件逻辑不变（但消息本身保留，只是标记删除）

### 4. getMessages 查询始终过滤 deleted
- 所有查询接口加 `deleted = false` 条件，确保已软删的消息不返回

## 前端改动

### 1. API 层
- `sessions.ts`: 加 `getMessagesSince(id, since)` 方法

### 2. Pinia store 改动
- `session.ts`: 加 `messagesCache = ref<Map<number, { messages: Message[], maxUpdatedAt: string }>>()`
- `selectSession()`: 查缓存 → 有则调 `?since=` 增量合并，无则全量拉
- `editMessage()`: 成功后直接 patch messages.value 中对应消息，不调 selectSession
- `deleteMessage()`: 成功后直接从 messages.value 中移除，不调 selectSession
- 增量合并逻辑：按 id 去重，同 id 取 updatedAt 较新的覆盖

### 3. 注意事项
- 竞态控制复用现有的 `sessionSelectionGeneration + selectionTargetId` 模式
- 新建消息（generate/chat）后仍需要调增量拉取
- 图片生成 PENDING 轮询不受影响
