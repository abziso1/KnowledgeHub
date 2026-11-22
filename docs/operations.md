# KnowledgeHub 运维手册

## 初始化与 HTTPS

从项目根目录运行命令。将 `.env.example` 复制为 `.env` 并填写数据库密码、初始管理员用户名和密码。首次启动执行 Flyway 迁移，随后初始化管理员。再次启动会保留已有管理员凭据。

`web` 是唯一对宿主机发布端口的服务。局域网部署时，将 `KH_WEB_BIND` 设为主机的局域网地址，或设为 `0.0.0.0` 并通过主机防火墙限制访问。配置 HTTPS 时，将证书链和私钥放入独立目录，并设置：

```dotenv
KH_WEB_BIND=0.0.0.0
KH_WEB_MODE=https
KH_SESSION_SECURE=true
KH_TLS_DIR=./tls
KH_TLS_CERT_FILE=server.crt
KH_TLS_KEY_FILE=server.key
```

用 `https://<主机名>:<KH_WEB_PORT>` 访问，证书须覆盖该主机名。Nginx 只读挂载证书目录，将请求协议和来源写入转发头，同源请求在浏览器内携带会话 Cookie。只需本机访问时，保留示例中的 HTTP 和环回地址。

查看服务状态及错误日志：

```powershell
docker compose ps
docker compose logs --tail 100 web backend db
docker compose exec storage cat /data/storage/logs/storaged.log
docker compose exec tracker cat /data/tracker/logs/trackerd.log
```

FastDFS 写入若在返回文件标识前超时，后端日志会记录图片请求 ID。若已知文件标识、但无法完成补偿或入队，日志会同时记录内部文件标识。维护时用这些记录对照 FastDFS 和数据库状态。

## 同批备份 PostgreSQL 与 FastDFS

以下示例使用 PowerShell 7，项目名和环境文件按部署情况调整。备份期间停止 Web 和后端，待 PostgreSQL 转储完成后停止 FastDFS 写入进程并归档两个卷。备份目录需要保存三个数据文件与配置版本记录。

```powershell
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$sourceProject = 'knowledgehub-acceptance'
$sourceEnv = '.env.acceptance'
$backupDir = (New-Item -ItemType Directory -Path "backups/$(Get-Date -Format yyyyMMdd-HHmmss)").FullName

docker compose -p $sourceProject --env-file $sourceEnv stop web backend
docker compose -p $sourceProject --env-file $sourceEnv exec -T db sh -ec 'pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /tmp/knowledgehub.dump'
docker compose -p $sourceProject --env-file $sourceEnv cp db:/tmp/knowledgehub.dump (Join-Path $backupDir 'postgres.dump')
docker compose -p $sourceProject --env-file $sourceEnv stop storage tracker

foreach ($volume in 'tracker-data', 'storage-data') {
    docker run --rm --network none `
        --mount "type=volume,source=${sourceProject}_${volume},target=/data,readonly" `
        --mount "type=bind,source=$backupDir,target=/backup" `
        debian:bookworm-slim tar -czf "/backup/$volume.tar.gz" -C /data .
}

Copy-Item ops/fastdfs/versions.env (Join-Path $backupDir 'fastdfs-versions.env')
Get-FileHash ops/fastdfs/tracker.conf, ops/fastdfs/storage.conf, ops/fastdfs/entrypoint.sh |
    Select-Object Hash, @{Name='File';Expression={Split-Path $_.Path -Leaf}} |
    ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $backupDir 'fastdfs-config-hashes.json')
Get-FileHash (Join-Path $backupDir 'postgres.dump'), (Join-Path $backupDir 'tracker-data.tar.gz'), (Join-Path $backupDir 'storage-data.tar.gz') |
    Select-Object Hash, @{Name='File';Expression={Split-Path $_.Path -Leaf}} |
    ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $backupDir 'backup-hashes.json')

docker compose -p $sourceProject --env-file $sourceEnv up -d --wait
```

Windows 下用 `docker compose cp` 复制二进制转储文件，卷归档通过绑定目录写到宿主机。

## 恢复到独立实例

选择从未使用过的 Compose 项目名及空闲 Web 端口。先核对归档内容和 FastDFS 配置散列，再恢复 PostgreSQL 与两个 FastDFS 卷。备份环境与目标环境使用兼容的 FastDFS 版本，其目录结构遵循上节的配置。示例使用与源实例相同的部署凭据和新的端口。

```powershell
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$restoreProject = 'knowledgehub-restore'
$restoreEnv = '.env.acceptance'
$backupDir = (Resolve-Path 'backups/<备份批次>').Path

foreach ($entry in (Get-Content (Join-Path $backupDir 'backup-hashes.json') -Raw | ConvertFrom-Json)) {
    $actual = Get-FileHash (Join-Path $backupDir $entry.File)
    if ($actual.Hash -ne $entry.Hash) { throw "归档校验失败 $($entry.File)" }
}
foreach ($entry in (Get-Content (Join-Path $backupDir 'fastdfs-config-hashes.json') -Raw | ConvertFrom-Json)) {
    $actual = Get-FileHash (Join-Path 'ops/fastdfs' $entry.File)
    if ($actual.Hash -ne $entry.Hash) { throw "FastDFS 配置与备份不一致 $($entry.File)" }
}
$existingVolumes = @(docker volume ls -q)
foreach ($volume in 'pg-data', 'tracker-data', 'storage-data') {
    if ("${restoreProject}_${volume}" -in $existingVolumes) { throw '请选择没有既有数据卷的项目名' }
}

$env:KH_WEB_PORT = '18081'
docker compose -p $restoreProject --env-file $restoreEnv up -d --wait db
docker compose -p $restoreProject --env-file $restoreEnv cp (Join-Path $backupDir 'postgres.dump') db:/tmp/knowledgehub.dump
docker compose -p $restoreProject --env-file $restoreEnv exec -T db sh -ec 'pg_restore --exit-on-error --no-owner --no-privileges -U "$POSTGRES_USER" -d "$POSTGRES_DB" /tmp/knowledgehub.dump'

foreach ($volume in 'tracker-data', 'storage-data') {
    docker volume create --label "com.docker.compose.project=$restoreProject" `
        --label "com.docker.compose.volume=$volume" "${restoreProject}_${volume}"
    docker run --rm --network none `
        --mount "type=volume,source=${restoreProject}_${volume},target=/data" `
        --mount "type=bind,source=$backupDir,target=/backup,readonly" `
        debian:bookworm-slim tar -xzf "/backup/$volume.tar.gz" -C /data
}

docker compose -p $restoreProject --env-file $restoreEnv up -d --wait
Remove-Item Env:KH_WEB_PORT
```

在目标实例使用原有账号登录，打开待检笔记，在预览中核对正文和图片。

## 浏览器验收

安装锁定的依赖和 Chromium 后，将 Playwright 指向验收实例。管理员变量使用该实例首次初始化时的值。

```powershell
npm --prefix frontend ci
npm --prefix frontend exec -- playwright install chromium --no-shell
$env:KH_E2E_BASE_URL = 'http://127.0.0.1:18080'
$env:KH_E2E_ADMIN_USERNAME = '<验收管理员>'
$env:KH_E2E_ADMIN_PASSWORD = '<验收管理员密码>'
npm --prefix frontend run test:e2e -- knowledgehub.spec.ts
```

持久化演练先用 `seed` 创建带图笔记并记录于 `frontend/e2e/.state/persistence.json`。完成重启或独立恢复后，用 `verify` 重新登录，核对同一篇笔记的正文、可读图片及图片 SHA-256。

```powershell
$env:KH_PERSISTENCE_MODE = 'seed'
npm --prefix frontend run test:e2e -- persistence.spec.ts
docker compose -p knowledgehub-acceptance --env-file .env.acceptance restart
docker compose -p knowledgehub-acceptance --env-file .env.acceptance up -d --wait

$env:KH_PERSISTENCE_MODE = 'verify'
npm --prefix frontend run test:e2e -- persistence.spec.ts
$env:KH_E2E_BASE_URL = 'http://127.0.0.1:18081'
npm --prefix frontend run test:e2e -- persistence.spec.ts
```

## 2026-11-22 验收记录

- `docker compose -p knowledgehub-acceptance --env-file .env.acceptance config` 确认只有 Web 发布 `127.0.0.1:18080`。同一配置通过 `up -d --build --wait` 的五项服务健康检查。
- Maven 后端验证通过 22 项测试。隔离网络中的 `FastDfsRoundTripIT` 实际完成写入、读取和删除。
- `npm --prefix frontend ci` 安装锁定的依赖，Vitest 通过 11 项测试，`vue-tsc` 和 Vite 构建成功。
- `npm --prefix frontend run test:e2e -- knowledgehub.spec.ts` 通过。覆盖双用户隔离、Markdown 图片和搜索、内容与整库恢复、管理员停用和旧会话失效。
- `npm --prefix frontend run test:e2e -- persistence.spec.ts` 的 `seed` 与重启后的 `verify` 通过。按上面的备份命令导出同批 PostgreSQL、Tracker 和 Storage 数据，恢复到 `knowledgehub-restore` 的独立数据卷和端口 `18081` 后，`verify` 再次通过。验证使用原账号、原笔记正文和同一图片 SHA-256，原实例的三个卷仍可用。
- 在恢复实例临时启用 HTTPS，`GET /api/auth/csrf` 返回带 `Secure`、`HttpOnly` 和 `SameSite=Lax` 的会话 Cookie。测试结束后恢复 HTTP 配置。
- 收尾复测通过后端 22 项、前端 12 项及 Chromium 全流程测试。取消退出登录会保留原会话与未保存草稿。CSRF 令牌失效后，下次手动提交会获取新令牌。
