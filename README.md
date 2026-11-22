# KnowledgeHub

KnowledgeHub 是面向个人使用的私有 Markdown 知识库。用户可以创建多个知识库，管理嵌套文件夹，导入、编辑和搜索笔记，上传笔记图片，并恢复回收站中的内容。管理员负责停用和恢复账号。

## 本机运行

安装带 Compose 的 Docker，打开项目根目录，在 PowerShell 执行：

```powershell
Copy-Item .env.example .env
```

在 `.env` 中设置 `KH_DB_PASSWORD` 和 `KH_ADMIN_PASSWORD`，然后启动应用。

```powershell
docker compose up -d --build --wait
```

打开 [KnowledgeHub](http://127.0.0.1:8080)。普通用户从登录页注册。首次启动时，部署配置创建管理员账号。管理员登录后可管理普通用户的状态。

- 默认只监听本机的 `127.0.0.1:8080`。可通过 `KH_WEB_BIND` 和 `KH_WEB_PORT` 调整 Web 入口。
- PostgreSQL 保存笔记正文和权限数据，FastDFS 保存图片。两者使用 Compose 数据卷。
- Markdown 导入接受 UTF-8 `.md` 文件，每次一篇，最大 2 MiB。图片支持 PNG、JPEG、GIF 和 WebP，单张最大 10 MiB。
- 正文改动在点击「保存」时提交。内容回收站按知识库划分，整个知识库在账号的知识库回收站中恢复。

局域网 HTTPS、备份、恢复和验证命令见 [运维手册](docs/operations.md)。

## 开发验证

后端需要 JDK 21、Maven 3.9 和可访问的 Docker 引擎。先用 Bash 安装固定版本的 FastDFS Java SDK，再运行 Maven 验证。前端需要 Node.js 22.12 或更高版本。

```bash
bash ops/fastdfs/install-java-sdk.sh
mvn -f backend/pom.xml verify
docker compose -f compose.fastdfs-test.yaml build tracker
docker compose -f compose.fastdfs-test.yaml run --rm fastdfs-test
npm --prefix frontend ci
npm --prefix frontend test -- --run
npm --prefix frontend run build
```

启动前端开发服务器时，Vite 默认将 `/api` 代理到 `http://127.0.0.1:8080`，也可以通过 `KH_DEV_API_TARGET` 指定后端地址。
