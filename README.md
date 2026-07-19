# AIS — AI 创作工作台

AIS（AI Studio）是一个基于 Spring Boot + Vue 3 的 AI 图像创作工作台，支持多种模型及供应商，提供 Web 与移动端友好的交互体验。

## 功能特点

- **多模型支持** — 支持多种模型及供应商（Agnes AI、GPT Image 2 等），后台可灵活切换
- **图生图 / 文生图** — 支持文本提示词生成、参考图重绘、IP-Adapter 服装迁移
- **会话管理** — 历史对话自动保存，支持断点续画、重新生成、图片引用
- **移动端适配** — 独立的飞书 H5 视图，手势缩放、长按菜单、消息气泡操作
- **PC 端完整工作台** — 侧边栏会话列表、参数面板、历史图库、批量操作
- **验证码与鉴权** — 内置 SVG 验证码，GraalVM Native Image 兼容
- **可配置上下文路径** — 单二进制部署，支持任意反向代理上下文

## 架构

```
用户 → 反向代理 → AIS (单一 Native Image)
                    ├── API 层 (Spring Boot)
                    ├── 前端资源 (Vue 3, 内嵌)
                    └── 数据层 (SQLite / 文件系统)
```

## 快速开始

```bash
# 下载二进制
chmod +x ais
# 启动
./ais
# 访问 http://localhost:11111/ais/
```

详细部署和构建指南请参阅 [BUILD.md](BUILD.md)。

## 目录结构

```
ais/
├── ais              # Native Image 可执行文件
├── config/          # 外部配置文件
├── data/            # SQLite 数据库及持久化数据
│   └── uploads/     # 生成图片与附件
└── logs/            # 应用日志
```

## 环境变量

核心配置通过环境变量注入，完整列表见 [BUILD.md](BUILD.md)。

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `APP_BASE_DIR` | `.` | 运行基础目录 |
| `APP_CONTEXT_PATH` | `/ais` | Web 访问上下文 |
| `SERVER_PORT` | `11111` | HTTP 端口 |

## 许可证

MIT — 详见 [LICENSE](LICENSE)。