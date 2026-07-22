# AIS 遗留问题 v2 — 修复后回归的 Bug

## 问题一：缩略图生成导致进程崩溃（P0）

### 现象
部署 Build #19（含 ImageController）后，服务进程反复崩溃重启：
```
java.lang.NoClassDefFoundError: java/awt/GraphicsEnvironment
  at ...PureThumbnail.writeLongestEdgePng(PureThumbnail.java:25)
  at ...ImageController.generateThumbnail(ImageController.java:81)
```
用户打开含历史图片的会话时，前端请求缩略图 → ImageController 调用 PureThumbnail 生成 → AWT 缺失 → 进程崩溃 → systemd 重启 → 请求重试 → 又崩。

### 根因
PureThumbnail.writeLongestEdgePng() 内部调用了 `Imaging.getBufferedImage()`（Apache Commons Imaging），该方法在 GraalVM Native Image 中依赖 AWT 的 `GraphicsEnvironment`，而 AWT 在 Native Image 中不可用。

PureThumbnail 中**非 AWT 的代码**（缩放 scaleBilinear + PNG 编码 encodePngRgba）工作正常，仅**图像解码入口**有问题。

### 预期
- 调用缩略图 API 不导致进程崩溃
- 能对历史图片自动生成缩略图

### 实现方向（自行决定）
- PNG 解码可直接用已有的 PNGJ（PngReader → ImageLineInt → 转 ARGB int[]）
- JPEG / 其他格式需纯 Java 解码方案，或跳过自动生成、直接返回原图

### 验收标准
1. 访问 `/api/images/{id}/thumbnail` 不导致进程退出
2. 有原图无缩略图的自动生成并返回
3. 不存在的 id 返回 404
4. 纯 Java，零 AWT 依赖

---

## 问题二：服务重启后生成中的任务沦为孤儿（P1）

### 现象
后台出图任务在队列中状态为"排队中"或"生成中"，但服务重启后没有任何线程处理这些任务。前端持续轮询该状态，无法超时也无法重试。

### 预期
- 服务启动时检测是否有遗留的"生成中"任务，将其重置为"失败"或"待重试"
- 或给生成任务设超时机制，超时后自动标记为失败
- 前端对长时间无变化的生成任务应有重试入口

### 验收标准
1. 服务重启后，"生成中"/"排队中"的任务自动标记为失败
2. 用户在界面上可以看到失败状态并重新提交
3. 不阻塞新的出图请求

---

## 问题三：前端打开会话时卡顿（P2）

### 现象
用户进入含大量历史图片的会话时，前端卡顿、nginx 日志出现 upstream timeout。

### 可能方向
- 缩略图并发请求数过高导致后端线程池耗尽
- 图片列表 API 响应慢
- 前端渲染大量图片时 DOM 压力大（虚拟滚动 / 懒加载）

### 验收标准
- 打开含大量图片（>50）的会话不出现明显卡顿
- nginx 无 upstream timeout

---

## 边界说明

- 不动 ErrorResponse / AuthFilter / CaptchaService（Build #18 已修复）
- 不动前端 API 调用格式
- 实现方案自行决定
