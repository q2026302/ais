# AIS 构建部署 — 遗留问题

## 问题一：构建版本信息缺失

### 现象
`GET /api/version` 返回全部为 unknown：
```json
{"version":"unknown","commit":"unknown","buildTime":"unknown"}
```

### 预期
- `version`：取当前构建的版本号（如 git tag 或 CI 传递的版本变量）
- `commit`：取构建时 HEAD 的 git commit SHA
- `buildTime`：取构建时间戳

### 验收标准
1. `GET /api/version` 返回值均为实际值，非 unknown
2. 前端个人中心底部能正确展示版本信息（已有 `ProfileView.vue` 版本展示组件）

### 兼容性约束
- 不改变现有 `/api/version` 的响应结构（已有前端依赖该字段）
- 不改变 CI workflow 整体流程

---

## 问题二：缩略图请求返回 500

### 现象
`GET /api/images/{id}/thumbnail` 始终返回 500：
```json
{"status":500,"error":"Internal Server Error",
 "message":"No static resource {id}/thumbnail for request '/ais/api/images/{id}/thumbnail'."}
```
注意：此 500 非 `ErrorResponse` 序列化崩溃（该问题已在 Build #18 修复），现在是 route 匹配问题。

### 预期
- `GET /api/images/{id}/thumbnail` 正常返回对应图片的缩略图文件
- 不存在的 id 返回 404
- 如果原始图片文件存在但缩略图文件尚未生成（历史数据），**应自动生成缩略图并返回**，无需人工干预
- `GET /api/images/`（图片列表）不受影响，保持正常工作

### 验收标准
1. 有缩略图的图片返回 200 + 缩略图文件
2. 原图存在但无缩略图的（历史数据），自动生成后返回 200 + 缩略图
3. 原图也不存在的 id 返回 404
4. 任何情况下不返回 500
5. 图片列表 API（`GET /api/images`）不受影响
6. 纯 Java 实现，不依赖 AWT（已有 PureThumbnail 工具类，GraalVM Native Image 兼容）

### 兼容性约束
- 不改变现有 Controller 的请求/响应结构
- 前端已通过 `/api/images/{id}/thumbnail` 加载缩略图（`imageUrl.ts`），URL 格式不可变

---

## 边界说明

- ErrorResponse / AuthFilter / CaptchaService 已在 Build #18 修复完成，不动
- 前后端改动范围由你自行判断，保持一致即可
- 实现方案、技术路线自行决定
