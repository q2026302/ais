## PureThumbnail AWT 依赖修复

### 背景
Build #19 新增的 ImageController 在自动生成缩略图时，调用了 PureThumbnail.writeLongestEdgePng()，该方法内部使用 `Imaging.getBufferedImage()`（Apache Commons Imaging），而该方法在 GraalVM Native Image 中依赖 AWT（`java.awt.GraphicsEnvironment`），导致进程崩溃。

### 现有代码分析
- `PureThumbnail.java`（`/ais-api/src/main/java/com/gs/ais/util/PureThumbnail.java`）：
  - **问题行**：第 25 行 `Imaging.getBufferedImage(imageData)` → 返回 BufferedImage（AWT 类）
  - **可用的部分**：`scaleBilinear()`（纯 Java 双线性缩放）✅、`encodePngRgba()`（PNGJ 编码）✅
  - **classpath 已有的依赖**：PNGJ（`ar.com.hjg.pngj`）可用于 PNG 解码，`commons-imaging` 只用于 getBufferedImage
- `ImageController.java`（`/ais-api/src/main/java/com/gs/ais/controller/ImageController.java`）：
  - `generateThumbnail()` 第 78-87 行：catch Exception 但不 catch Error（`NoClassDefFoundError` 是 Error 不是 Exception）

### 修复要求
1. PureThumbnail 不再使用任何 AWT 类（`java.awt.*`）
2. 移除 `Imaging.getBufferedImage()` 调用
3. PNG 解码用 PNGJ 的 `PngReader` 完成（读 scanline → 转 ARGB int[] → 传给 scaleBilinear）
4. JPEG/其他格式的处理：
   - 可在 PureThumbnail 中检测格式，非 PNG 时 log warning 并 return（不清除不生成）
   - ImageController 已有 fallback：如果缩略图生成失败，返回原图（`Path toServe = ... thumbPath : original`）
6. ImageController.generateThumbnail() 中 `catch (Exception e)` 改为 `catch (Throwable e)` 防止任何未捕获错误导致进程退出
7. `commons-imaging` 依赖如果不再使用，从 `build.gradle` 中移除

### 不改动范围
- 不动其他 Controller/Service
- 不动前端代码
- 不动 CI 配置

### 验收标准
1. 纯 Java，零 AWT 依赖
2. PNG 图片能自动生成缩略图
3. JPEG 图片不生成缩略图，fallback 返回原图
4. 任何情况下进程不崩溃
5. 本地编译通过（`./gradlew :ais-api:compileJava`）
