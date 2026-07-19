# AIS

AIS 是一个由 Spring Boot 后端和 Vue 3 前端组成的 AI 创作工作台。应用默认使用 `ais` 作为访问上下文，完整名称为 **AIS**。

## 运行上下文

默认监听 `11111` 端口：

```text
页面：http://127.0.0.1:11111/ais/
API：http://127.0.0.1:11111/ais/api/...
```

上下文可以通过 `APP_CONTEXT_PATH` 修改，例如：

```bash
APP_CONTEXT_PATH=/studio ./ais
```

前端生产资源使用相对路径，因而同一个 Native Image 可以部署到不同上下文；开发服务器代理上下文可以通过 `VITE_APP_CONTEXT_PATH` 指定，默认值为 `/ais`。

## 默认目录与配置

应用支持通过环境变量指定基础目录及其子目录。相对路径会相对于 `APP_BASE_DIR` 解析：

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `APP_BASE_DIR` | `.` | 应用运行基础目录 |
| `APP_CONFIG_DIR` | `${APP_BASE_DIR}/config` | 外部配置目录 |
| `APP_DATA_DIR` | `${APP_BASE_DIR}/data` | SQLite 数据库及持久化数据 |
| `APP_LOG_DIR` | `${APP_BASE_DIR}/logs` | 日志目录 |
| `APP_UPLOAD_DIR` | `${APP_DATA_DIR}/uploads` | 生成图片和附件 |
| `APP_SQLITE_PATH` | `${APP_DATA_DIR}/ais.db` | SQLite 数据库文件 |
| `APP_LOG_FILE` | `${APP_LOG_DIR}/application.log` | 应用日志文件 |
| `APP_CONTEXT_PATH` | `/ais` | Web 访问上下文 |
| `SERVER_PORT` | `11111` | HTTP 端口 |

例如：

```bash
APP_BASE_DIR=/opt/ais \
APP_CONTEXT_PATH=/ais \
APP_DATA_DIR=/opt/ais/data \
APP_LOG_DIR=/opt/ais/logs \
./ais
```

## 构建

### GraalVM Native Image

需要安装 GraalVM JDK、`native-image`、Node.js 和 Yarn：

```bash
./package.sh
```

产物默认位于：

```text
ais-api/build/native/nativeCompile/ais
```

启动：

```bash
./ais-api/build/native/nativeCompile/ais
```

验证码使用不依赖 AWT 的 SVG 数据图生成，适用于 GraalVM Native Image；通过反向代理访问时，验证码接口为当前上下文下的 `GET /api/auth/captcha`，例如 `/ais/api/auth/captcha`。

如果要按用户级服务部署，请将该可执行文件复制到一个独立部署目录，并与服务脚本放在同一目录，文件名默认为 `ais`：

```text
ais/
├── ais
├── install-user-service.sh
└── uninstall-user-service.sh
```

### Spring Boot Jar

如需使用 JVM 部署：

```bash
./package.sh --jar
```

## 用户级 systemd 服务

项目提供不需要 `sudo` 的用户级 systemd 服务脚本。脚本默认要求同目录存在可执行的 `ais` Native Image，并以 Native Image 所在目录作为应用基础目录：

```bash
./install-user-service.sh
```

脚本会创建并启动：

```text
~/.config/systemd/user/ais.service
```

systemd unit 注册位置仍遵循 systemd 用户服务规范；应用自己的配置、日志和数据都位于 Native Image 所在目录：

```text
<基础目录>/config/application.env
<基础目录>/logs/application.log
<基础目录>/data/ais.db
<基础目录>/data/uploads/
```

首次安装会生成 `config/application.env`。修改后重启服务即可生效：

```bash
systemctl --user restart ais.service
```

### 服务管理

```bash
systemctl --user status ais.service
journalctl --user -u ais.service -f
systemctl --user restart ais.service
systemctl --user stop ais.service
```

默认访问地址：

```text
http://127.0.0.1:11111/ais/
```

也可以通过环境变量指定其他 Native Image 路径；此时以该文件所在目录作为应用基础目录。卸载时使用相同的变量：

```bash
AIS_BINARY=/opt/ais/bin/ais ./install-user-service.sh
AIS_BINARY=/opt/ais/bin/ais ./uninstall-user-service.sh
```

### 卸载用户级服务

```bash
./uninstall-user-service.sh
```

卸载脚本只删除 `ais.service` 的用户级 systemd 注册，不删除脚本所在目录下的 `config/`、`data/`、`logs/` 或上传文件。

### 未登录时自动运行

用户级服务默认在用户登录期间运行。如果需要开机后用户未登录时也运行，可启用 linger：

```bash
loginctl enable-linger "$USER"
```

取消：

```bash
loginctl disable-linger "$USER"
```

## 开发命令

后端：

```bash
cd ais-api
./gradlew bootRun
./gradlew test
```

前端：

```bash
cd ais-web
yarn
yarn dev
yarn build
```

## 用户与账号管理

应用内置两类账号：普通用户和管理员。登录后，用户可从顶部“个人中心”维护显示名称、邮箱并修改自己的密码；用户名创建后不可修改。密码修改需要校验当前密码，浏览器只发送客户端 MD5 摘要的 RSA-OAEP-256 加密结果，服务端以 BCrypt 保存，不接收明文密码。

管理员可进入“管理 → 用户管理”完成以下操作：

- 查看用户列表及账号状态；
- 新增用户并设置初始密码；
- 修改显示名称、邮箱、角色和启用状态；
- 删除用户；
- 重置用户密码。

管理员用户管理 API（均需要管理员权限）：

```text
GET    /ais/api/admin/users
POST   /ais/api/admin/users
PUT    /ais/api/admin/users/{id}
DELETE /ais/api/admin/users/{id}
PUT    /ais/api/admin/users/{id}/password
```

当前用户 API：

```text
GET /ais/api/auth/me
PUT /ais/api/auth/me
PUT /ais/api/auth/password
```

系统会保护最后一个启用中的管理员账号：不能删除、禁用或降级最后一个管理员，也不能删除、禁用或降级当前登录管理员账号。

### 本地前端开发

先启动后端服务，再启动 Vite 开发服务器：

```bash
cd ais-api
./gradlew bootRun

cd ../ais-web
yarn dev
```

默认可访问：

```text
http://127.0.0.1:5173/
```

也可以访问：

```text
http://127.0.0.1:5173/ais/
```

开发服务器会把 `/api/...` 自动代理到后端默认上下文 `/ais/api/...`。如果后端使用了其他上下文或端口，可配置：

```bash
VITE_APP_CONTEXT_PATH=/studio \
VITE_BACKEND_CONTEXT_PATH=/studio \
VITE_BACKEND_ORIGIN=http://127.0.0.1:11111 \
yarn dev
```
