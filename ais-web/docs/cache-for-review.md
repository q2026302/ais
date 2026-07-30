# 缓存方案分析（Claude Code 产出）

## 方案一：纯前端 Pinia 内存缓存 + 乐观更新
- 后端零改动
- Pinia store 维护 Map<sessionId, { messages, loadedAt }>，TTL 30s
- 编辑/删除先改本地缓存，再 API，失败回滚
- 页面刷新缓存丢失（可接受）

## 方案二：增量拉取 `?since=` 时间戳游标
- 后端加 `?since` 查询参数
- 需要软删除（deleted 字段）
- 首次全量，后续增量 upsert 合并

## 方案三：ETag 条件请求（不推荐）
- 消息量小，ETag 省body收益低
- 增加了后端计算 + 前端304处理复杂度

## Claude Code 推荐：方案一 → 按需升级方案二
理由：消息量小，TTL 缓存解决等待感足够，后端零改动。

## 需要你审查的角度
1. Claude Code 的推荐是否合理？
2. 方案二的软删除引入成本是否值得？
3. 乐观更新的竞态风险（多设备场景）
4. 是否有 Claude Code 遗漏的考虑？
