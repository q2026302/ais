# 前端消息缓存架构需求

## 项目背景
ais-web (Vue 3 + Vite + TS + Pinia) + ais-api (Spring Boot + GraalVM)

## 当前问题
每次进入会话都全量拉取消息列表，网络压力大。

## 当前数据流
```
selectSession(id)
  → GET /api/sessions/{id}/messages
    → 全量返回 Message[]（几十~几百条）
      → normalizeMessage()
        → messages.value = 全量数组
```

编辑/删除后也调 selectSession() 全量重拉。

## Message 实体关键字段
- id: Long
- content: String
- edited: boolean
- createdAt: LocalDateTime
- updatedAt: LocalDateTime（内容/状态更新时前进）

## 约束
1. 消息量级小（几十~几百条/会话）
2. 后端在 GraalVM native image 下运行
3. 用户可能多设备使用
4. 页面刷新后缓存可接受丢失（不是强需求）

## 需要分析的维度
1. 缓存应该做在哪一层？（前端 Pinia / IndexedDB / Service Worker / 后端加条件请求）
2. 后端需要提供什么端点变化？
3. 编辑/删除后缓存如何同步？
4. 增量拉取 vs 全量+ETag vs 纯前端缓存，哪个更适合？
5. 多 tab / 多设备场景如何处理？

给出 2-3 个可选方案，含优缺点和推荐。
