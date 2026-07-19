# 构建与部署指南

## 环境要求

| 工具 | 用途 |
|------|------|
| GraalVM JDK 21+ | 后端编译 |
| `native-image` | GraalVM Native Image 编译 |
| Node.js 20+ | 前端构建 |
| Yarn | 前端依赖管理 |

---

## 构建

### GraalVM Native Image（推荐）

```bash
./package.sh
```

产物默认位于：

```
ais-api/build/native/nativeCompile/ais
```

启动：

```bash
./ais-api/build/native/nativeCompile/ais
```

### Spring Boot Jar

```bash
./package.sh --jar
```

产物：`ais-api/build/libs/ais-api.jar`

---

## 部署为 systemd 用户服务

项目提供免 `sudo` 的用户级 systemd 服务脚本。

### 初次部署

1. 将 Native Image 可执行文件复制到独立部署目录，与服务脚本放在同一目录：

```
ais/
├── ais               # Native Image 可执行文件
├── install-user-service.sh
└── uninstall-user-service.sh
```

2. 安装并启动服务：

```bash
./install-user-service.sh
```

脚本会自动创建目录结构（`config/`、`data/`、`logs/`）并启动服务。

### 服务管理

```bash
# 查看状态
systemctl --user status ais

# 查看日志
journalctl --user -u ais -f

# 重启
systemctl --user restart ais

# 停止
systemctl --user stop ais
```

### 更新部署

```bash
# 在构建机器上
rsync -avz ais-api/build/native/nativeCompile/ user@host:~/ais/bin/

# 在目标机器上重启
systemctl --user restart ais
```

### 卸载

```bash
./uninstall-user-service.sh
```

---

## 环境变量参考

| 环境变量 | 默认值 | 用途 |
|----------|--------|------|
| `APP_BASE_DIR` | `.` | 应用运行基础目录 |
| `APP_CONFIG_DIR` | `${APP_BASE_DIR}/config` | 外部配置目录 |
| `APP_DATA_DIR` | `${APP_BASE_DIR}/data` | SQLite 数据库及持久化数据 |
| `APP_LOG_DIR` | `${APP_BASE_DIR}/logs` | 日志目录 |
| `APP_UPLOAD_DIR` | `${APP_DATA_DIR}/uploads` | 生成图片和附件 |
| `APP_SQLITE_PATH` | `${APP_DATA_DIR}/ais.db` | SQLite 数据库文件 |
| `APP_LOG_FILE` | `${APP_LOG_DIR}/application.log` | 应用日志文件 |
| `APP_CONTEXT_PATH` | `/ais` | Web 访问上下文 |
| `SERVER_PORT` | `11111` | HTTP 端口 |

示例：

```bash
APP_BASE_DIR=/opt/ais \
APP_CONTEXT_PATH=/ais \
APP_DATA_DIR=/opt/ais/data \
APP_LOG_DIR=/opt/ais/logs \
./ais
```

---

## 反向代理参考

### Nginx

```nginx
location /ais/ {
    proxy_pass http://127.0.0.1:11111;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_buffering off;
    client_max_body_size 50m;
}
```

---

## 开发环境

```bash
# 后端（端口 11111）
./gradlew bootRun

# 前端热更新（端口 5173）
cd ais-web && yarn dev
```

前端开发服务器通过 `VITE_APP_CONTEXT_PATH` 指定代理上下文，默认 `/ais`。

---

## 验证码

应用使用 SVG 数据图生成验证码，不依赖 AWT，适用于 GraalVM Native Image。
验证码接口：`GET /api/auth/captcha`（相对于当前上下文路径）。

---

## 运行上下文

默认监听 `11111` 端口：

```
页面：http://127.0.0.1:11111/ais/
API：http://127.0.0.1:11111/ais/api/...
```

上下文可通过 `APP_CONTEXT_PATH` 修改。前端生产资源使用相对路径，同一个 Native Image 可以部署到不同上下文路径下。