# Phase 3 重构收尾任务

## 当前状态

- `src/views/mobile/FeishuMobileLayout.vue` 已存在（214行），有 header + bottom-nav + `<router-view>`
- 4 个页面组件已完成：FeishuChatPage / FeishuGalleryPage / FeishuProfilePage / FeishuSessionsPage
- 3 个 composable：useLongPress / useMobileKeyboard / useImageActions
- 当前 router 中 `/feishu/sessions` `/feishu/chat/:id` `/feishu/gallery` `/feishu/profile` 都是**平级独立路由**，没有嵌套在 Layout 下
- 旧路由 `/feishu` → FeishuH5View.vue 仍在

## 任务 1：路由重构 — FeishuMobileLayout 接入路由

将 `/feishu/*` 和 `/mobile/*` 的 4 个页面路由改为**嵌套路由**，父路由用 FeishuMobileLayout：

```
/feishu              → FeishuMobileLayout (父路由，有 router-view)
  /feishu/sessions   → FeishuSessionsPage
  /feishu/chat/:id   → FeishuChatPage
  /feishu/gallery    → FeishuGalleryPage
  /feishu/profile    → FeishuProfilePage

/mobile              → FeishuMobileLayout (父路由)
  /mobile/sessions   → FeishuSessionsPage
  /mobile/chat/:id   → FeishuChatPage
  /mobile/gallery    → FeishuGalleryPage
  /mobile/profile    → FeishuProfilePage
```

注意：
- 父路由 `/feishu` 和 `/mobile` 本身 meta 保持 `{ embedded: true, mobileEntry: 'feishu'/'mobile' }`
- 子路由的 meta 保持现有值（如 `hideBottomNav: true`）
- `/feishu` 父路由默认重定向到 `/feishu/sessions`，`/mobile` 同理
- FeishuMobileLayout 的 `entryPrefix` 计算依赖 `route.meta.mobileEntry`，嵌套路由中需要确保 Layout 能读取到父路由的 meta

## 任务 2：清理旧路由

删除 `/feishu` → FeishuH5View.vue 的旧路由映射（当前 `/mobile` 和 `/feishu` 都指向 FeishuH5View.vue）。

完成后 `/mobile` 和 `/feishu` 都应该走 FeishuMobileLayout。

## 任务 3：FeishuChatPage 死 CSS 清理

FeishuChatPage.vue 约 2075 行，其中包含约 350 行从 FeishuH5View 搬过来的 CSS，部分已经不再被使用（因为 Layout 已经提取了 header/bottom-nav 的样式）。

需要：
1. 识别并删除 FeishuChatPage 中不再使用的 CSS 规则（如 `.mobile-header`、`.bottom-nav` 等已移至 Layout 的样式）
2. 保留 ChatPage 独有的样式（消息气泡、composer、drawer 等）
3. 不要删除任何仍在 template 中引用的 CSS class

## 验证标准

- `npx vue-tsc --noEmit` 无报错
- `npm run build` 成功
- `/feishu` 访问时显示 FeishuMobileLayout + 默认重定向到 sessions 页
- `/feishu/chat/:id` 访问时底部导航隐藏（hideBottomNav）
- 旧 FeishuH5View.vue 可以保留不删除（做回退），但路由不再指向它