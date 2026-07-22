# 出图任务孤儿恢复 + 按供应商隔离队列

## 背景

ImageGenerationQueueService 目前使用单一全局队列：

- 单 `ExecutorService` + 单 `activeCount` 控制 `maxConcurrent=2` 全局并发
- 所有供应商的 IMAGE 模型共用同一并发槽位，相互阻塞
- 队列状态（pendingFutures、processingInfo）全在内存，重启后丢失

问题：

1. **孤儿任务**（P1）：服务重启后 `DRAW_RESPONSE` 消息状态仍为 `PENDING`，永远无人改为 `FAILED`，前端卡在"生成中"无法重试
2. **无供应商隔离**（P2）：供应商 A 占满槽位时，供应商 B 的任何 IMAGE 模型都在空转等待

---

## 改动一：孤儿任务恢复（P1）

### 现象

- 服务崩溃（如 AWT `NoClassDefFoundError`）或正常重启后
- DB 中 `message_type='DRAW_RESPONSE'` 且 `status='PENDING'` 的记录永远停留在该状态
- 前端轮询到 `PENDING` 一直显示"生成中"

### 方案

1. **`MessageRepository.java`** — 新增
   ```java
   List<Message> findByMessageTypeAndStatus(MessageType messageType, MessageStatus status);
   ```

2. **`ImageGenerationQueueService.java` — `@PostConstruct init()`**
   - 扫描所有 `DRAW_RESPONSE` + `PENDING` 的消息
   - 标记为 `FAILED`，errorMessage = "服务重启，出图任务已终止，请重新提交。"
   - 记录 warn 日志（含 messageId）

3. **`ImageGenerationQueueService.java` — `@PreDestroy shutdown()`**
   - 遍历 `pendingFutures`，cancel 未完成的 future
   - 标记 DB 中仍然 `PENDING` 的消息为 `FAILED`，errorMessage = "服务关闭，出图任务已终止。"
   - 清空 `pendingFutures`、`processingInfo`

### 验收标准

1. DB 中有 `DRAW_RESPONSE/PENDING` 的记录 → 服务启动后变为 `FAILED`
2. 正常 shutdown → 队列中 PENDING 任务被标记为 FAILED
3. 不阻塞正常出图流程

---

## 改动二：按供应商隔离队列（P2）

### 现状

```java
// 全局单队列
private final AtomicInteger activeCount = new AtomicInteger(0);
@Value("${ais.image.queue.max-concurrent:2}") private int maxConcurrent;
```

### 方案

将单 `activeCount` + `maxConcurrent` 改为**按 ModelProvider.id 隔离**：

```java
private final ConcurrentHashMap<Long, ProviderQueueState> providerQueues = new ConcurrentHashMap<>();

record ProviderQueueState(
    AtomicInteger activeCount,
    int maxConcurrent,
    ConcurrentHashMap<Long, CompletableFuture<Void>> pendingFutures
) {}
```

#### 并发上限来自 DB 字段

在 `ModelProvider` 实体新增 **`imageQueueConcurrency`**（仅 IMAGE 类型使用）：

| 项目 | 说明 |
|------|------|
| 字段名 | `imageQueueConcurrency` |
| 数据库列 | `image_queue_concurrency` |
| 类型 | `Integer` |
| 默认值 | `null` — 表示使用全局默认值（在 yml 中配置，或硬编码为 1） |
| 适用模型 | 仅 `type=IMAGE`，CHAT 类型忽略 |

读取代码逻辑：
```java
int concurrency = provider.getImageQueueConcurrency();
if (concurrency == null || concurrency <= 0) {
    concurrency = defaultMaxConcurrent; // 从 yml 或硬编码默认 1
}
```

#### 涉及的后端改动

1. **`ModelProvider.java`** — 新增 `imageQueueConcurrency` 字段 + getter/setter
2. **`ModelProviderRequest.java`** — 新增 `imageQueueConcurrency` 字段（`@Schema` 标注"图像模型并发上限，留空/0 则使用全局默认值"）
3. **`ProviderModelRequest.java`** — 新增 `imageQueueConcurrency` 字段
4. **`ModelProviderResponse.java`** — 新增 `imageQueueConcurrency` 字段
5. **`ModelProviderService.java` — `applyModelSettings()`／`toModelRequest()`** — 映射该字段（仅 IMAGE 类型）
6. **`ImageGenerationQueueService.java`** — 改为 per-provider 队列：
   - 移除全局 `maxConcurrent`、`activeCount`
   - `submitDraw()` / `submitExistingDraw()` 已知 `imageProviderId` → 获取/创建对应 `ProviderQueueState`
   - `processDraw()` 用 per-provider 容量检查
   - `cancelPending()` 遍历所有 `providerQueues`
   - `shutdown()` 遍历所有 `providerQueues`
   - `processingInfo` 仍按 messageId 索引，不涉及 provider
   - `@Value("${ais.image.queue.default-max-concurrent:1}")` 作为全局默认并发

7. **`application.yml`** — 新增配置：
   ```yaml
   ais:
     image:
       queue:
         default-max-concurrent: ${AIS_QUEUE_DEFAULT_CONCURRENCY:1}
   ```

#### 涉及的前端改动

8. **`types/index.ts`** — `ModelProvider` + `ModelProviderRequest` + `ProviderModelRequest` 接口新增 `imageQueueConcurrency?: number | null`
9. **`components/ProviderDialog.vue`** — 在图像模型的"失败重试"行后面或同一行，新增输入控件：
   ```html
   <el-form-item v-if="model.type === 'IMAGE'" label="并发上限">
     <el-input-number v-model="model.imageQueueConcurrency" :min="1" :max="10" controls-position="right" />
     <small style="color:#8d97ac;font-size:12px;margin-left:8px;white-space:nowrap;">同时生图数</small>
   </el-form-item>
   ```
   - `newModel('IMAGE')` 中默认 `imageQueueConcurrency: null`（表示使用全局默认值）
   - 编辑回填时 model 已有该值
   - `handleTypeChange()` 切换类型时 image 模型不丢失该字段

#### 关键逻辑变更

`processDraw()` 中容量检查改为 per-provider：

```java
ProviderQueueState state = providerQueues.computeIfAbsent(imageProviderId, pid -> {
    int concurrency = resolveConcurrency(imageProviderId);
    return new ProviderQueueState(new AtomicInteger(0), concurrency, new ConcurrentHashMap<>());
});
while (true) {
    int current = state.activeCount.get();
    if (current < state.maxConcurrent && state.activeCount.compareAndSet(current, current + 1))
        break;
    Thread.sleep(1000);
}
try {
    // 原有逻辑
} finally {
    state.activeCount.decrementAndGet();
}
```

### 验收标准

1. 两个不同 IMAGE 模型的出图可**同时**运行，互不阻塞
2. 同一模型的并发上限受 `imageQueueConcurrency` 控制
3. 前端配置页可正常增删改查 `imageQueueConcurrency` 字段
4. null / 未设置时使用全局默认值（1）
5. 现有行为兼容（单模型场景下与原来一致）

---

## 不改动范围

- 不动 FeishuEventService / ChatService / LlmClient 等调用方
- 不动 DB schema（新增列，不改旧列）
- 不动 `ModelProviderDefaults` / `ModelProviderService` 的非相关方法
- 不动 CI / 部署配置

## 边界说明

- `processDraw` 已接收 `imageProviderId` 参数，可直接用于路由
- `wasCancelled()` 只查 DB（per messageId），不受队列结构影响
- `getProcessingInfo()` / `getMessageStatus()` / `getMessage()` 返回值不变
- `OrphanFileCleanupService` 无关，不涉及
- 前端 `ProviderDialog.vue` 中已有 IMAGE-only 字段（adapterType、maxRetries）的完整模式，新字段照做即可
