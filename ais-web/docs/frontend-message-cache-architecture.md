# 前端消息缓存架构方案讨论

> 日期：2026-07-30  
> 项目：ais-web (Vue 3 + Vite + TypeScript + Pinia)  
> 后端：ais-api (Spring Boot + GraalVM)

---

## 0. 现状回顾

### 数据流

```
selectSession(id)
  → sessionApi.getMessages(id)         // GET /api/sessions/{id}/messages
    → 全量返回 Message[]
      → normalizeMessage() 处理 URL
        → messages.value = 全量数组
```

### 当前的问题

| 问题 | 表现 |
|------|------|
| **全量拉取** | 每次进会话都请求全部消息，几百条时网络延迟明显 |
| **编辑/删除后全量重拉** | `editMessage()` / `deleteMessage()` 调 `selectSession()` 全量刷新 |
| **无持久缓存** | `messages` 数组在 Pinia 内存中，页面刷新或 tab 切换后丢失 |
| **后端无增量接口** | 只有全量端点，没有 `?since=` 或 `?after=` 参数 |

### 有利条件

- 消息量级小（几十~几百条/会话，不超千条）
- 消息有 `id`、`createdAt`、`updatedAt` 时间戳字段
- 已实现 `normalizeMessage()` 做数据清洗
- Store 有 `AbortController` 管理，可做请求竞态控制

---

## 方案一：Pinia 内存缓存 + 后端增量拉取

### 思路

根本性方案：后端加增量端点，前端用内存缓存 + SWR 模式。

### 改动清单

#### 后端（ais-api）

新增 `GET /api/sessions/{id}/messages?since={updatedAt}`：

```
GET /api/sessions/{id}/messages?since=2026-07-30T10:00:00Z
→ 只返回 updatedAt > since 的消息

如果无参数或 since 为空 → 返回全量（兼容老客户端）
```

Spring Boot 实现：在 Service 层加 `if (since != null) return repo.findBySessionIdAndUpdatedAtAfter(id, since)`。不会影响现有接口签名。

#### 前端

```
messagesCache = ref<Map<number, Message[]>>()   // sessionId → messages
messagesLastFetch = ref<Map<number, string>>()   // sessionId → last updatedAt

selectSession(id):
  1. 查 cache：有缓存吗？
  2. 有 → 增量拉取：sessionApi.getMessagesSince(id, lastUpdatedAt)
     → 合并到缓存（去重 by id）
     → 返回
  3. 无 → 全量拉取：sessionApi.getMessages(id)
     → 写入缓存
     → 记录 lastFetchTime
```

编辑/删除后不调 `selectSession()`，改为直接修缓存：

```
editMessage(messageId, newContent):
  → sessionApi.editMessage(...)  // 后端写入
  → 直接更新 messagesCache[sessionId] 中对应 message 的 content/edited
  → 不调 selectSession()

deleteMessage(messageId):
  → sessionApi.deleteMessage(...)
  → 从 messagesCache[sessionId] 中 filter 掉该条
  → 不调 selectSession()
```

### 优点

- **网络请求最小**：正常浏览仅传增量数据，百条消息场景从 ~100KB 降到几百字节
- **响应极快**：缓存命中后瞬间渲染，后台静默拉增量
- **实现清晰**：增量和合并逻辑直白
- **无持久化开销**：不涉及 IndexedDB/localStorage 的序列化/反序列化

### 缺点

- **必须改后端**：这是最大成本。需要后端加端点、加数据库查询
- **内存不持久**：页面刷新后缓存丢失，首次还是全量拉
- **多 tab 同步问题**：同浏览器两个 tab 打开同一会话，A 编辑后 B 无感知——需等下一次增量拉取
- **离线不可用**：不持久化，断网场景无意义

### 冲突处理

- 编辑/删除：悲观策略——先调后端确认成功，再更新本地缓存
- 增量合并：以 `id` 去重，同 id 取 `updatedAt` 较新的覆盖
- 后台静默刷新间隔：切换会话时立即拉增量，同会话内建议设 30-60s 定时轮询（低优先级）

### 实现成本评估

| 层 | 改动量 | 难度 |
|----|--------|------|
| 后端 | 1 个新 Controller 方法 + 1 个 Repository 查询 | ★☆☆ |
| 前端 Store | ~50 行（cache map + 合并逻辑 + 增量请求适配） | ★☆☆ |
| 前端 API | ~10 行（新增 `getMessagesSince()`） | ★☆☆ |
| **总计** | **低** | |

---

## 方案二：IndexedDB 持久缓存 + 全量+增量混合

### 思路

使用 IndexedDB（通过 idb-keyval 或 Dexie）做持久缓存，同时后端也建议增加增量端点。前端启动时从 IndexedDB 加载缓存，然后后台静默刷新。

```
用户打开会话
  → 先从 IndexedDB 读取缓存渲染（瞬间）
  → 同时后台发起增量/全量请求
  → 数据到后更新 IndexedDB + Pinia
```

### 数据模型

```
IndexedDB: 'message-cache' store
  key: `session-${sessionId}`
  value: {
    messages: Message[],
    fetchedAt: string,           // 缓存时间
    updatedAt: string,           // 最新的 message.updatedAt
    version: number              // 缓存格式版本号
  }
```

### 前端改动

```
selectSession(id):
  1. 从 IndexedDB 读缓存 → 立即渲染（如果有）
  2. 发起请求：
     a. 如果后端有增量接口 → 传 ?since=cachedUpdatedAt
     b. 如果后端只有全量接口 → 全量拉
  3. 数据到达 → 合并 → 更新 IndexedDB → 更新 Pinia
```

编辑/删除：

```
editMessage():
  → API 调用 → 成功后同时更新 IndexedDB + Pinia messages

deleteMessage():
  → API 调用 → 成功后同时从 IndexedDB + Pinia 移除
```

### 优点

- **页面刷新不丢**：缓存持久化，刷新后直接读 IndexedDB
- **离线体验好**：已缓存的会话断网可读
- **启动即渲染**：无网络等待，提升感知性能

### 缺点

- **IndexedDB API 繁琐**：需异步操作、事务管理，比 Pinia 复杂
- **缓存一致性问题**：多 tab 同时写 IndexedDB 需协调（锁或版本号）
- **序列化开销**：Message 对象进出 IndexedDB 需序列化/反序列化，百条消息不重但有空对象（`tokenUsage`）增加存储
- **存储空间管理**：需考虑缓存大小上限、LRU 淘汰策略
- **必须改后端**：增量接口对方案价值很大（否则每次全量写入 IndexedDB 徒增写入开销）

### 冲突处理

- IndexedDB + Pinia 双写：以 API 响应为准，Pinia 为 UI 源，IndexedDB 为持久源
- 版本号升级时（Message 结构变更），清空全部缓存重新拉
- 缓存 TTL：建议 5 分钟，超时后后台静默刷新；缓存超过 30 分钟未刷新则标记 stale，显示"正在刷新..."

### 实现成本评估

| 层 | 改动量 | 难度 |
|----|--------|------|
| 后端 | 建议加增量端点（同方案一，非必须） | ★☆☆~★★☆ |
| 前端 | ~150-200 行（IndexedDB 封装 + 缓存策略 + 合并逻辑 + 多 tab 协调） | ★★☆ |
| 引入依赖 | 建议用 `idb-keyval`（~1KB）或 `Dexie`（更强大但 ~30KB） | - |
| **总计** | **中** | |

---

## 方案三：Service Worker 缓存 + 当前架构近乎不变

### 思路

不触及 Pinia 和业务代码，在 Service Worker 层用 Cache Storage API 缓存 `GET /api/sessions/{id}/messages` 的响应。

```
请求发出 → Service Worker 拦截
  → 有缓存且未过期 → 返回缓存（Network-First / Stale-While-Revalidate）
  → 无缓存 → 发网络请求 → 缓存响应 → 返回
```

### 策略模式

选择 **Stale-While-Revalidate**：

```
SWR 策略：
  1. 立即返回缓存（极快）
  2. 同时发起网络请求更新缓存
  3. 下一个请求使用新缓存
```

缓存 key：`/api/sessions/{id}/messages`  
缓存 TTL：在 SW 中通过 `Cache-Control` 或自定义头部控制，建议 30 秒

### 前端改动量

几乎为零——注册 Service Worker 即可，业务代码完全无感。

但编辑/删除后，需要主动使缓存失效：

```ts
// 在 editMessage / deleteMessage 成功后
if ('serviceWorker' in navigator && navigator.serviceWorker.controller) {
  navigator.serviceWorker.controller.postMessage({
    type: 'INVALIDATE_CACHE',
    url: `/api/sessions/${sessionId}/messages`
  })
}
```

### 优点

- **侵入性最小**：业务代码几乎不改，纯基础设施层
- **网络请求拦截自然**：Vite PWA 插件可自动生成 SW
- **多 tab 共享缓存**：Cache Storage 跨 tab 共享
- **离线能力**：已缓存的请求断网可用

### 缺点

- **全量响应的缓存**：即使缓存命中，请求的仍是全量数据，只是响应时间缩短（缓存读取快于网络）
- **缓存无效化麻烦**：编辑/删除后必须手动 invalidate，否则展示旧数据
- **无法增量**：SW 层面向 HTTP 响应，不能做"只返回新增的几条"这种粒度控制
- **调试困难**：SW 的生命周期、更新、scope 问题可能导致"明明改了代码但看到旧数据"
- **百条消息场景收益有限**：缓存只是省了网络传输时间，序列化/反序列化开销仍在

### 冲突处理

- 编辑/删除后 invalidate 缓存，下次读取时触发全量拉取
- 如果需要立即展示编辑结果，仍需在前端手动更新 Pinia messages（即不能完全"无感"）

### 实现成本评估

| 层 | 改动量 | 难度 |
|----|--------|------|
| 前端 SW | ~50 行（sw.js + Vite PWA 配置） | ★☆☆ |
| 前端 Store | ~10 行（postMessage invalidate） | ★☆☆ |
| **总计** | **低** | ★☆☆ |

---

## 方案对比

| 维度 | 方案一：增量 + 内存缓存 | 方案二：IndexedDB 持久缓存 | 方案三：Service Worker |
|------|------------------------|---------------------------|----------------------|
| **网络传输** | ✅ 极小（仅增量） | ✅ 极小（增量）或 ⚠️ 全量 | ⚠️ 全量（仅省传输时间） |
| **首屏速度** | ✅ 有缓存时瞬间 | ✅ 有缓存时瞬间 | ✅ 缓存命中时快 |
| **页面刷新保持** | ❌ 不保持 | ✅ 保持 | ✅ 保持 |
| **离线可用** | ❌ 不可用 | ✅ 已缓存的可用 | ✅ 已缓存的可用 |
| **多 tab 同步** | ⚠️ 需轮询 | ⚠️ 需轮询或 BroadcastChannel | ✅ 天然共享 |
| **代码侵入性** | ⚠️ 中 | ⚠️ 中~大 | ✅ 小 |
| **需改后端** | ✅ **必须** | ✅ 建议 | ❌ 不必须 |
| **调试难度** | ✅ 低 | ⚠️ 中 | ❌ 高（SW 生命周期） |
| **实现成本** | ✅ 低 | ⚠️ 中 | ✅ 低 |
| **长期可维护性** | ✅ 好 | ✅ 好 | ❌ SW 逻辑分散 |

---

## 推荐方案 🏆

### 首选：方案一（Pinia 内存缓存 + 后端增量拉取）

理由：

1. **消息量级决定取舍**：几十~几百条，不值得引入 IndexedDB 或 SW 的复杂度
2. **非持久化可接受**：消息列表刷新频率低（每次进会话才触发），页面刷新后首次全量拉 + 后续增量是合理的
3. **后端改动最小且独立**：一个 `?since=` 参数即可，不影响现有接口
4. **代码最清晰、最好调试**：所有缓存逻辑在 Pinia store 里，CR 同学一看就懂

### 建议的路线图

```
Phase 1（1-2 天）：
  后端加 ?since 参数
  前端新增 getMessagesSince() API
  Pinia 中加 messagesCache、messagesFetchedAt
  selectSession() 改为先查缓存
  editMessage/deleteMessage 改为直接操作缓存 + 不调 selectSession()

Phase 2（可选，后续优化）：
  加 30s 后台轮询检测新消息（当前会话内自动接收）
  多 tab 场景用 BroadcastChannel API 同步缓存无效化
```

### 不推荐方案三

Service Worker 方案看似"无侵入"，但实际上**缓存无效化**和**无法增量**这两个问题导致它在当前场景下收益很有限。最适合 SW 的是静态资源缓存和离线优先的场景，而非动态 API 数据的部分刷新。

### 方案二做备选

如果后续需求变为"离线可用"或"页面刷新不能闪白"，可以用方案二（IndexedDB 持久化）作为 Phase 2 的增强。在 Phase 1 的增量接口和 Pinia 缓存基础上，加入 IndexedDB 层做持久化是相对自然的扩展。

---

## 附录：关键实现要点（方案一）

### 1. 增量合并逻辑

```ts
// 伪代码：合并增量消息到缓存
function mergeMessages(
  cached: Message[],
  incremental: Message[],
): Message[] {
  const map = new Map<number, Message>()
  for (const msg of cached) map.set(msg.id, msg)
  for (const msg of incremental) {
    const existing = map.get(msg.id)
    if (!existing || (msg.updatedAt && existing.updatedAt &&
        msg.updatedAt > existing.updatedAt)) {
      map.set(msg.id, msg)
    }
  }
  return Array.from(map.values()).sort((a, b) =>
    a.createdAt.localeCompare(b.createdAt)
  )
}
```

### 2. 编辑/删除后直接操作缓存

```ts
async function editMessage(messageId: number, newContent: string) {
  if (!activeSessionId.value) return
  await sessionApi.editMessage(activeSessionId.value, messageId, { content: newContent })
  // 直接更新缓存，不做全量拉取
  const cached = messagesCache.value.get(activeSessionId.value)
  if (cached) {
    const msg = cached.find(m => m.id === messageId)
    if (msg) {
      msg.content = newContent
      msg.edited = true
      msg.updatedAt = new Date().toISOString()
    }
    messagesCache.value.set(activeSessionId.value, [...cached])
  }
  // 同时更新 Pinia 的 messages 响应式引用
  messages.value = messagesCache.value.get(activeSessionId.value) ?? []
  editingMessageId.value = null
}
```

### 3. 竞态控制

现有 `sessionSelectionGeneration + selectionTargetId` 的竞态模式已足够好，增量请求复用同一机制——只有最新 generation 的响应才能更新缓存。

### 4. 边界情况

- **新建消息**：`generate()` / `chat()` 后后端会返回新消息，调用 `reloadSessionIfCurrent()` 时改为增量拉取而非全量
- **消息状态轮询**：DRAW_RESPONSE 的 PENDING 状态轮询不影响缓存，status 更新走单独端点
- **切换会话过快**：前一个全量请求可能比后一个增量请求后返回，generation 机制会丢弃过期响应
