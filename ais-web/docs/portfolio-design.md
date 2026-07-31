# 作品库（Portfolio）功能设计

## 概述
每个用户拥有自己的作品库，可以从对话中收藏满意的生成图片，按目录管理，
并可在绘画时引用作品库中的图片作为参考图。

---

## 一、数据模型

### Portfolio（作品库）
```
每个用户自动拥有一个作品库（创建用户时同步初始化）。
不需要显式建库。
```

### PortfolioDirectory（作品目录）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键 |
| user_id | Long | 所属用户 |
| name | String | 目录名（必填） |
| description | String? | 目录描述 |
| parent_id | Long? | 父目录（支持两级嵌套，不允许多级） |
| sort_order | int | 排序 |
| created_at | LocalDateTime | |
| updated_at | LocalDateTime | |

约束：同级目录下 name 唯一。最多两级（根目录 → 子目录，不可再递归）。

### PortfolioItem（作品条目）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键 |
| user_id | Long | 所属用户 |
| directory_id | Long? | 所属目录，null 为未分类 |
| title | String? | 用户自定义标题 |
| description | String? | 用户自定义说明 |
| image_url | String | 图片 URL（引用原图） |
| draw_prompt | String? | 生成提示词 |
| draw_size | String? | 尺寸 |
| draw_quality | String? | 质量 |
| draw_format | String? | 格式 |
| provider_id | Long? | 生成模型 |
| source_session_id | Long? | 来源会话（可追溯） |
| source_message_id | Long? | 来源消息（可追溯） |
| sort_order | int | 自定义排序 |
| created_at | LocalDateTime | |
| updated_at | LocalDateTime | |

说明：
- image_url 直接引用原图路径（不复制文件），原图删除后作品库中的引用会失效
- 不会因为消息被软删除而同步删除作品库条目（作品库是独立的用户资产）

---

## 二、后端 API

### 目录管理
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/portfolio/directories | 获取当前用户的目录列表（树形） |
| POST | /api/portfolio/directories | 创建目录 |
| PUT | /api/portfolio/directories/{id} | 修改目录 |
| DELETE | /api/portfolio/directories/{id} | 删除目录（含其下作品移到未分类） |

### 作品管理
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/portfolio/items | 获取作品列表（支持 dir_id 过滤、分页） |
| POST | /api/portfolio/items | 添加作品（从消息添加） |
| PUT | /api/portfolio/items/{id} | 修改作品（标题/说明/目录） |
| DELETE | /api/portfolio/items/{id} | 删除作品 |
| PUT | /api/portfolio/items/{id}/move | 移动作品到其他目录 |
| PUT | /api/portfolio/items/batch-move | 批量移动 |

### 从会话中添加作品
```
POST /api/portfolio/items
Body: {
  messageId: Long,          // 来源消息 ID（必填）
  directoryId?: Long,       // 目标目录（可选，不填则未分类）
  title?: string,           // 自定义标题
  description?: string,     // 自定义说明
}
```

后端从 Message 中提取：
- imageUrl ← message.imageUrl
- drawPrompt ← message.drawPrompt
- drawSize ← message.drawSize
- drawQuality ← message.drawQuality
- drawFormat ← message.drawFormat
- providerId ← message.drawProviderId
- sourceSessionId ← message.session.id
- sourceMessageId ← message.id
- userId ← 当前认证用户

---

## 三、前端 UI

### 3.1 作品库入口
- 导航栏新增"作品库"入口（与"会话列表"同级）

### 3.2 作品库页面（PortfolioPage.vue）

布局：
```
[左侧目录树]          [右侧作品网格]
  ├─ 全部作品            图片网格（3-4列）
  ├─ 未分类              每张卡片：缩略图 + 标题 + 提示词预览
  ├─ 精选                 悬停操作：编辑/删除/复制提示词
  └─ 人像
      ├─ 室内
      └─ 室外
```

操作：
- 点击目录过滤右侧作品
- 搜索框搜索标题/提示词
- 多选 → 批量移动/删除
- 点击大图预览（复用现有 ImageGallery 的大图预览）

### 3.3 添加到作品库（在对话页面）

在 DRAW_RESPONSE 消息的菜单中增加"加入作品库"按钮：
```
消息菜单：[重新生成] [复制提示词] [下载] [加入作品库 ✦]
```

点击后弹出对话框：
```
┌─ 加入作品库 ──────────────┐
│ 标题：___________________  │
│ 目录：[下拉选择 ▼]         │
│ 说明：___________________  │
│                           │
│ 图片预览 + 提示词          │
│                           │
│    [取消]    [加入]        │
└───────────────────────────┘
```

### 3.4 从作品库引用图片（在绘画面板）

在 ChatInput 的参考图面板中，现有"相机/相册"旁边新增"作品库"来源：

```
[📷 拍照] [🖼 相册] [✦ 作品库]
```

点击"作品库"弹出选择面板，与历史图片网格类似，勾选后确认添加到参考图列表。

---

## 四、实现路线

| 阶段 | 内容 | 工作量 |
|------|------|--------|
| **Phase 1** | 后端：Entity + Repository + Controller（CRUD） | ~2天 |
| **Phase 2** | 前端：作品库页面（目录树 + 作品网格） | ~2天 |
| **Phase 3** | 前端：对话中"加入作品库"入口 + 对话框 | ~1天 |
| **Phase 4** | 前端：绘画面板"从作品库引用" | ~1天 |
