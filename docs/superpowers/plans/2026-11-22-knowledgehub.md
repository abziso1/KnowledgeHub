# KnowledgeHub Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付可在本机或局域网运行的 KnowledgeHub，支持多私有知识库、Markdown 编辑和搜索、图片、两级回收站及账号管理。

**Architecture:** Vue 与 Spring Boot 经单一 Web 入口同源通信。PostgreSQL 保存正文、目录、权限与生命周期状态，FastDFS 保存图片，Spring Boot 校验会话和资源归属后转发图片。先验证真实存储，再按依赖交付后端功能、浏览器工作流和 Docker 部署验收。

**Tech Stack:** Java 21、Spring Boot 3.5.16、Maven 3.9、Spring Security、Spring JDBC、Flyway、PostgreSQL 17、FastDFS、Vue 3、TypeScript、Element Plus、Vite、Node 22.12+、npm、Nginx、Docker Compose、JUnit 5、Testcontainers、Vitest、Playwright。

**Spec:** [docs/superpowers/specs/2026-11-22-knowledgehub-design.md](../specs/2026-11-22-knowledgehub-design.md) · [领域术语](../../../CONTEXT.md) · [存储 ADR](../../adr/0001-store-markdown-in-postgresql.md)

## Global Constraints

- 普通用户从登录页自行注册，注册后即可登录。每位普通用户可以创建和切换多个私有知识库。
- 管理员负责账号停用与恢复，账号管理权限不包含读取知识库内容。
- PostgreSQL 存储 Markdown 正文，FastDFS 存储笔记图片。FastDFS 仅接受内部文件请求。
- 支持单个 UTF-8 `.md` 导入、手动保存、离开提醒和乐观并发校验。搜索只查当前选中库的活动笔记标题和已保存正文。
- 设计默认允许 PNG、JPEG、GIF 和 WebP，单张图片上限 10 MiB，单个 Markdown 文件上限 2 MiB。初次上传的暂存图片默认保留 24 小时。
- 两类回收站都保留条目，直到用户恢复或确认永久删除。内容和整库恢复都保留相关图片。
- 首版范围止于个人知识库，不包含分享、协作、多媒体附件、ZIP 批量导入和公网部署。移动限定在同一个库内。
- 单机 Docker Compose，宿主机只发布 Web 入口，默认绑定 `127.0.0.1`。局域网监听地址和 HTTPS 可配置。

## Review Focus

1. 停用和恢复发生在同一会话两次请求之间时，旧会话仍须失效，见任务 3 的 `restoredAccountDoesNotReviveUntouchedSession`。
2. 两个请求同时将同级文件夹互相移入时，只允许其中一个提交，见任务 4 的 `concurrentMovesCannotCreateCycle`。
3. 搜索输入包含 `%` 或 `_` 时按字面字符匹配，见任务 5 的 `searchTreatsSqlWildcardsAsLiterals`。
4. 一张图片在一篇活动笔记与一篇回收笔记间复用时，后者的引用也必须阻止物理删除，见任务 7 的 `activeAndTrashedReferencesBothKeepImage`。
5. 上传开始后知识库被回收时，提交必须重新校验库状态，不能留下可访问图片，见任务 6 的 `uploadRacingLibraryTrashCannotPublishImage`。

---

## 文件布局与执行约定

这是同一个可部署应用。后端、前端和部署组成顺序交付的验证阶段，图片和回收站共用数据库约束与资源权限，组合到同一份计划中。

| 路径 | 职责 |
| --- | --- |
| `backend/src/main/java/com/knowledgehub/{auth,library,note,media,trash}` | 按功能组织控制器、业务事务和 JDBC 访问 |
| `backend/src/main/java/com/knowledgehub/common` | 错误响应和分页类型 |
| `backend/src/main/resources/db/migration/` | Flyway 顺序迁移 |
| `backend/src/test/java/com/knowledgehub/` | PostgreSQL 集成测试、FastDFS 实例测试及夹具 |
| `frontend/src/{api,auth,workspace,editor,trash,admin}` | API、会话和各工作流组件 |
| `frontend/e2e/` | 基于真实 Compose 服务的浏览器验收 |
| `ops/fastdfs/`、`ops/nginx/`、`compose.yaml` | 镜像、反向代理与服务编排 |
| `README.md`、`docs/operations.md` | 启动、初始化、备份和恢复 |

下文 `J` 指 `backend/src/main/java/com/knowledgehub`，`T` 指 `backend/src/test/java/com/knowledgehub`，`M` 指 `backend/src/main/resources/db/migration`，`F` 指 `frontend/src`。从项目根执行命令。Java 集成测试以 `*Test` 命名，由 Surefire 执行，使用真实 PostgreSQL Testcontainer 和 MockMvc；真实 FastDFS 用单独的 Maven profile。接口测试中的 `call`、`upload` 和 `actor` 由 `T/ApiTestSupport.java` 统一提供。剪裁的测试片段给出断言，执行者编写夹具与导入。

**冻结的接口约定**

- Java ID 统一为 `UUID`，HTTP 使用 UUID 字符串，时间为 ISO 8601 UTC，版本为 `long`，首版前端把版本视为安全整数。根目录是 `folderId: null`。分页为 `Page<T>(List<T> items, int page, int size, boolean hasNext)`，页码从 0 开始，每页默认 20、最大 100。跨组件 DTO `LibraryView` 定义在 `LibraryRepository`，`NoteView` 定义在 `NoteService`，均为公开嵌套 record。
- 写接口成功创建返回 `201`，状态或内容更新按任务返回实体或 `204`。错误体统一 `{code,message}`，包括 `401 AUTH_REQUIRED`、`403 FORBIDDEN`、`404 NOT_FOUND`、`409 NAME_CONFLICT`、`409 FOLDER_CYCLE`、`409 VERSION_CONFLICT`、`413 FILE_TOO_LARGE`、`422 IMAGE_UNAVAILABLE`。输入校验返回 `400 INVALID_INPUT`，登录或注册限速返回 `429 RATE_LIMITED`，存储服务不可用返回 `503 STORAGE_UNAVAILABLE`。旧账号会话用 `401 AUTH_REQUIRED`。
- 下文 `L` 表示 `/api/libraries/{libraryId}`。应用图片地址恒为 `L/images/{imageId}`。URL 中的库 ID 决定资源范围，服务端从会话获取 `accountId`。同一用户的另一个库的资源 ID 也不可用于当前库的写接口。`404` 响应对不存在与非本人资源一致。
- `LibraryAccess.requireActive(UUID accountId, UUID libraryId)` 用于读操作。`LibraryAccess.lockActive(UUID accountId, UUID libraryId)` 在业务写事务中校验库归属和状态并锁定库行；库内写入先获取这把锁，再锁定笔记或图片。知识库创建、改名、整库回收和恢复先锁账号行，再锁库行。FastDFS 网络调用放在数据库事务之外，提交阶段重新校验库状态。
- 主要客户端工具版本锁入包管理器锁文件及镜像配置。固定 FastDFS 服务端、原生依赖和 Java SDK 源码修订，并以任务 2 的真实往返测试验收组合。[Spring Boot 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html) · [Vite 兼容要求](https://vite.dev/guide/) · [FastDFS Java SDK](https://github.com/happyfish100/fastdfs-client-java)
- 实施开始时仓库若仍未初始化，在项目根执行 `git init`，将现有 spec、术语表、ADR 与本计划提交为文档基线。以下提交步骤只暂存该任务 **Files** 中的文件。项目停止或测试失败时先保留可诊断的现场。

### Task 1: Spring Boot 启动与 PostgreSQL 测试基线

**Files:** 新建 `.gitignore`、`backend/pom.xml`、`J/KnowledgeHubApplication.java`、`J/common/ApiError.java`、`J/common/ApiProblem.java`、`J/common/ApiExceptionHandler.java`、`J/common/Page.java`、`backend/src/main/resources/application.yml`、`T/PostgresTestSupport.java`、`T/BootstrapTest.java`。

**Interfaces:** 产出 `GET /actuator/health` → `200 {status:"UP"}`，`record ApiError(String code, String message)`、`record Page<T>(List<T> items, int page, int size, boolean hasNext)`、`ApiProblem(HttpStatus status, String code, String message)` 及统一异常映射，以及 `PostgresTestSupport` 的 PostgreSQL 17 动态数据源配置。

- [ ] 写 `BootstrapTest#startsWithPostgresAndFlyway`。先建立 POM、最小应用入口和测试夹具，使测试可运行。

```java
assertEquals(1, jdbc.queryForObject("select 1", Integer.class));
assertTrue(flyway.info().pending().length == 0);
mvc.perform(get("/actuator/health")).andExpect(status().isOk())
    .andExpect(jsonPath("$.status").value("UP"));
```

- [ ] 运行 `mvn -f backend/pom.xml -Dtest=BootstrapTest test`。预期健康端点或 Flyway 接线尚未实现而失败。
- [ ] 配置 web、validation、actuator、JDBC、Flyway 的 PostgreSQL 模块和测试依赖。用环境配置数据库连接，不把实际凭据或构建产物纳入 Git。Actuator 仅暴露 health，不显示敏感细节。
- [ ] 运行同一命令，预期 `Failures: 0, Errors: 0`；运行 `mvn -f backend/pom.xml verify`，预期 `BUILD SUCCESS`。
- [ ] 提交 `chore: bootstrap Spring Boot and PostgreSQL`。

### Task 2: FastDFS 真实容器与 Java 文件接口

**Files:** 新建 `ops/fastdfs/Dockerfile`、`ops/fastdfs/versions.env`、`ops/fastdfs/install-java-sdk.sh`、`ops/fastdfs/entrypoint.sh`、`ops/fastdfs/tracker.conf`、`ops/fastdfs/storage.conf`、`compose.fastdfs-test.yaml`、`J/media/BlobStore.java`、`J/media/FastDfsBlobStore.java`、`T/media/FastDfsRoundTripIT.java`；修改 `backend/pom.xml`、`backend/src/main/resources/application.yml`。

**Interfaces:** `BlobStore.put(byte[] data, String extension): String`，`BlobStore.open(String key): InputStream`，`BlobStore.delete(String key): void`，均可能抛 `IOException`。已不存在的对象按删除成功处理。`key` 为内部 FastDFS 文件标识。`FastDfsBlobStore` 从 `KH_FDFS_TRACKER` 读取 `host:port`，配置连接与读超时，关闭借用的连接。

- [ ] 写 `FastDfsRoundTripIT#trackerSelectedStorageWorksFromBackendNetwork`。先定义 `BlobStore` 及显式失败的 adapter，Maven profile `fastdfs-it` 必须执行该类，缺少 Tracker 配置时测试失败。

```java
var bytes = new byte[] { 10, 20, 30 };
String key = store.put(bytes, "png");
try (var data = store.open(key)) { assertArrayEquals(bytes, data.readAllBytes()); }
store.delete(key);
store.delete(key);
assertThrows(FileNotFoundException.class, () -> store.open(key));
```

- [ ] 运行 `docker compose -f compose.fastdfs-test.yaml run --rm fastdfs-test`。测试服务和命令先接通，预期 adapter 无法完成往返。
- [ ] 运行真正的 Tracker 与 Storage，使用内部桥接网络、持久目录、IPv4 协议和服务可达性检查。先用上游 FastDFS 6.15 系列及同系列声明的原生依赖和官方 Java SDK 校验相容性，把验证过的源码修订写入 `versions.env`。同一 `install-java-sdk.sh` 在宿主机 Git Bash 和镜像构建中安装固定修订生成的 Maven artifact，后续主应用 Maven 构建使用该 artifact。测试容器运行 `mvn -f backend/pom.xml -Pfastdfs-it -Dit.test=FastDfsRoundTripIT verify`。该 profile 只关停 Surefire，Failsafe 仍运行真实往返测试，容器无需访问宿主 Docker socket，FastDFS 无宿主端口。[上游 FastDFS 发行记录](https://github.com/happyfish100/fastdfs/releases)
- [ ] 再运行 Compose 测试命令，预期 `FastDfsRoundTripIT` 实际执行且通过。重启 Storage 后重跑，确认 Tracker 选址依然可用。宿主安装 SDK 后运行 `mvn -f backend/pom.xml verify`，预期 `BUILD SUCCESS`。
- [ ] 提交 `feat: validate FastDFS storage through the backend network`。

### Task 3: 注册、会话与管理员停用恢复

**Files:** 新建 `M/V001__accounts_and_libraries.sql`、`J/auth/AccountRepository.java`、`J/auth/AccountService.java`、`J/auth/AuthController.java`、`J/auth/AdminUserController.java`、`J/auth/SessionSecurity.java`、`J/auth/CurrentAccount.java`、`J/auth/AuthRateLimiter.java`、`J/auth/AdminBootstrap.java`、`J/library/LibraryRepository.java`、`T/ApiTestSupport.java`、`T/auth/AccountAccessTest.java`；修改 `backend/pom.xml`、`backend/src/main/resources/application.yml`。

**Interfaces:** `record CurrentAccount(UUID id, String username, Role role, long sessionVersion)`；`Role` 为 `USER` 或 `ADMIN`。`record Viewer(UUID id, String username, Role role)`。`LibraryRepository.insert(UUID ownerId, String name): LibraryView`，`record LibraryView(UUID id, String name, Instant updatedAt)`。`GET /api/auth/csrf` → `{headerName,token}`。`POST /api/auth/register` → `201 Viewer`，`POST /api/auth/login` → `200 Viewer`，二者请求均为 `{username,password}`。`GET /api/auth/me` → `Viewer`，`POST /api/auth/logout` → `204`。`GET /api/admin/users` → `Page<AdminUser>`，其中 `AdminUser(UUID id, String username, boolean enabled, Instant createdAt)`。`PUT /api/admin/users/{userId}/status` 接收 `{enabled}`，返回 `204`。测试夹具提供 `actor(String username, String password): Actor`，`call(Actor actor, String method, String path, Object body): Reply`，`upload(Actor actor, String path, String field, String filename, String type, byte[] bytes, Map<String,String> fields): Reply`，`Reply.status(): int` 与 `Reply.json(): JsonNode`。`null` actor 表示匿名，辅助方法为写请求获取并发送真实 CSRF token，测试也保留原始 MockMvc 入口。

- [ ] 写 `AccountAccessTest`。覆盖 `registrationCreatesEnabledUserAndDefaultLibrary`、`adminRoutesAndCsrfAreEnforced`、`bootstrapPreservesCredentials`、`authThrottleReturns429`，并固定以下回归测试。另对比 Spring Security encoder 的 `matches` 结果，数据库摘要不得等于密码，管理员列表只有普通用户。

```java
@Test void restoredAccountDoesNotReviveUntouchedSession() {
    var old = registerAndLogin("xiaowang");
    var admin = loginConfiguredAdmin();
    var userStatus = "/api/admin/users/" + old.accountId() + "/status";
    assertEquals(204, call(admin, "PUT", userStatus, Map.of("enabled", false)).status());
    assertEquals(204, call(admin, "PUT", userStatus, Map.of("enabled", true)).status());
    assertEquals(401, call(old, "GET", "/api/auth/me", null).status());
    assertEquals(200, call(actor("xiaowang", TEST_PASSWORD), "GET", "/api/auth/me", null).status());
}
```

- [ ] 运行 `mvn -f backend/pom.xml -Dtest=AccountAccessTest test`，预期注册或认证接口失败。
- [ ] 实现账号和知识库表。账号用户名归一为小写，唯一约束防止并发重复注册。注册事务内创建 `USER`、启用账号及名为「我的知识库」的库。知识库含 `id,owner_id,name,updated_at,trashed_at`，仅对同一所有者的活动名称施加唯一约束。管理员通过 `KH_ADMIN_USERNAME`、`KH_ADMIN_PASSWORD` 首次初始化，若配置用户名占用且角色为普通用户，启动报错并保留账号。重复启动保持原哈希。密码用 Spring Security `DelegatingPasswordEncoder` 的 bcrypt，拒绝超出 bcrypt 字节上限的输入。
- [ ] 实现同源服务端会话和角色检查。登录轮换会话 ID，通过 Spring Security 的 `SecurityContextRepository` 保存认证，注销销毁会话。按 `Clock` 控制限速，默认来源 IP 每分钟最多 10 次注册尝试，同 IP 和用户名组合每分钟最多 5 次失败登录，成功登录清除该登录计数，失败登录用统一凭据错误。限额可配置，过期计数可清理。会话 cookie 用 `HttpOnly; SameSite=Lax`，HTTPS 模式启用 `Secure`。停用事务递增 `session_version`，恢复不复原版本，每次受保护请求读当前账号状态和版本，包括后续新增的图片接口。只对认证控制器公开入口和健康检查开放匿名访问，写接口都要求 CSRF。
- [ ] 重跑该测试和 `mvn -f backend/pom.xml verify`，预期全部通过。
- [ ] 提交 `feat: add registration, sessions and account administration`。

### Task 4: 私有知识库和嵌套目录

**Files:** 新建 `M/V002__folders.sql`、`J/library/LibraryAccess.java`、`J/library/LibraryService.java`、`J/library/LibraryController.java`、`J/library/FolderRepository.java`、`J/library/FolderService.java`、`J/library/FolderController.java`、`T/library/LibraryFolderTest.java`；修改 `J/library/LibraryRepository.java`。

**Interfaces:** `LibraryAccess.requireActive(UUID accountId, UUID libraryId): void` 与 `lockActive(UUID accountId, UUID libraryId): void`。`FolderRepository.requireActive(UUID libraryId, UUID folderId): void`，`folderId=null` 表示已校验知识库的根目录。`GET /api/libraries` → `List<LibraryView>`；`POST /api/libraries {name}` → `201 LibraryView`；`PATCH L {name}` → `LibraryView`。`record FolderView(UUID id, UUID parentId, String name, Instant updatedAt)`。`GET L/folders` → `List<FolderView>`；`POST L/folders {parentId,name}` → `201 FolderView`；`PATCH L/folders/{folderId} {name}` → `FolderView`；`PUT L/folders/{folderId}/parent {parentId}` → `FolderView`。

- [ ] 写 `LibraryFolderTest`，锁定普通用户可建多个私有库、名称冲突 `409`、管理员访问 `403`、他人库和错误库中父目录 `404`。对同一库建立两个同级目录并用两个独立账号会话发请求。

```java
@Test void concurrentMovesCannotCreateCycle() {
    var tree = siblingFoldersInOneLibrary();
    var replies = runTogether(() -> tree.moveFirstUnderSecond(), () -> tree.moveSecondUnderFirst());
    assertEquals(List.of(200, 409), replies.stream().map(Reply::status).sorted().toList());
    assertEquals("FOLDER_CYCLE", replies.stream().filter(r -> r.status() == 409)
        .findFirst().orElseThrow().json().path("code").asText());
}
```

- [ ] 运行 `mvn -f backend/pom.xml -Dtest=LibraryFolderTest test`，预期接口未实现而失败。
- [ ] 实现知识库列表、建库、改名与目录列表、创建、改名、移动。名称去除前后空白，父目录 ID 用复合外键 `(library_id,parent_id)` 约束。引入 nullable `trash_batch_id`，活动兄弟节点以包含空父目录的唯一索引约束名称。写事务按全局锁顺序运行，在同一把库行锁下用递归查询拒绝自移和移入后代。
- [ ] 重跑该测试，预期全部通过且数据库不存在目录环；再跑 `mvn -f backend/pom.xml verify`。
- [ ] 提交 `feat: add private libraries and nested folders`。

### Task 5: 笔记、Markdown 导入和当前库搜索

**Files:** 新建 `M/V003__notes.sql`、`J/note/NoteRepository.java`、`J/note/NoteService.java`、`J/note/NoteController.java`、`J/note/MarkdownImport.java`、`J/note/NoteSearch.java`、`T/note/NoteWorkflowTest.java`；修改 `backend/src/main/resources/application.yml`。

**Interfaces:** `record NoteView(UUID id, UUID folderId, String title, String markdown, long version, Instant updatedAt)`；列表项 `NoteSummary(UUID id, UUID folderId, String title, long version, Instant updatedAt)`；搜索项 `SearchHit(UUID id, String title, List<String> folderPath, Instant updatedAt)`。`NoteService.save(UUID accountId, UUID libraryId, UUID noteId, long version, String title, String markdown): NoteView`。`GET L/notes?folderId&page&size` → `Page<NoteSummary>`，`GET L/notes/{noteId}` → `NoteView`；`POST L/notes {folderId,title}` → `201 NoteView`；`POST L/notes/import` 接收 multipart `file` 与可省略的 `folderId` → `201 NoteView`；`PUT L/notes/{noteId} {version,title,markdown}` → `NoteView`；`PUT L/notes/{noteId}/folder {version,folderId}` → `NoteView`。`GET L/search?q&page&size` → `Page<SearchHit>`。

- [ ] 写 `NoteWorkflowTest`，验证 `学习笔记.md` 生成标题 `学习笔记`，正文 `![截图](./images/demo.png)` 原样保存，非法 UTF-8 返回 `400`，超过 `2 * 1024 * 1024` 字节返回 `413`。同库修改后搜索「事务隔离」和大小写不同的英文，命中最新正文。另一个库的同名笔记不出现。两个版本相同的保存请求只允许第一个成功，第二个返回 `409 VERSION_CONFLICT` 且已保存正文不回退。

```java
@Test void searchTreatsSqlWildcardsAsLiterals() {
    var notes = notesInSelectedLibrary("A_1 100%", "AB1 1000");
    assertEquals(List.of(notes.firstId()), searchIds(notes, "_"));
    assertEquals(List.of(notes.firstId()), searchIds(notes, "%"));
}
```

- [ ] 运行 `mvn -f backend/pom.xml -Dtest=NoteWorkflowTest test`，预期导入或保存尚未实现。
- [ ] 实现 `MarkdownImport.decode(String filename, byte[] bytes): ImportedMarkdown`，其中 `ImportedMarkdown(String title, String markdown)`。用严格 UTF-8 解码，接受 UTF-8 BOM，清理文件名路径部分，只取去除扩展名的标题。创建空白笔记初始版本为 0；保存和移动用预期版本并递增，任何保存失败均不更新笔记。`note` 包含 `library_id,folder_id,title,markdown,version,updated_at,trash_batch_id`。搜索按库和活动状态过滤，参数化 SQL 并转义字面搜索中的转义符、`%`、`_`，用确定的 `updated_at DESC,id` 排序和 `size+1` 判断下一页。
- [ ] 重跑该测试和 `mvn -f backend/pom.xml verify`，预期全部通过。
- [ ] 提交 `feat: add Markdown import, versioned edits and scoped search`。

### Task 6: 私有图片、Markdown 引用与可靠清理

**Files:** 新建 `M/V004__images_and_cleanup_jobs.sql`、`J/media/MediaController.java`、`J/media/MediaService.java`、`J/media/ImageFormat.java`、`J/media/MarkdownImageReferences.java`、`J/media/ImageRepository.java`、`J/media/ImageCleanupQueue.java`、`J/media/ImageCleanupWorker.java`、`T/media/ImageLifecycleTest.java`；修改 `J/note/NoteService.java`、`backend/pom.xml`、`backend/src/main/resources/application.yml`。

**Interfaces:** `record ImageView(UUID id, String url)`。`POST L/images` 接收 multipart `file` → `201 ImageView`，`GET L/images/{imageId}` → `200` 图片、实际 `Content-Type` 及 `Cache-Control: no-store`。`MarkdownImageReferences.replace(UUID libraryId, UUID noteId, String markdown): void` 在笔记写事务内调用。`ImageCleanupQueue.enqueueUnreferenced(UUID libraryId, Collection<UUID> candidateIds): void` 和 `enqueueLibrary(UUID libraryId): void` 均由持有库行锁的事务调用。`ImageCleanupWorker.runOnce(): int` 返回本次成功完成的删除数。

- [ ] 写 `ImageLifecycleTest`。覆盖限额 `10 * 1024 * 1024`、格式签名和声明类型不一致、匿名 `401`、管理员 `403`、他人 `404`、未带 CSRF 的 multipart `403`、跨库或已过期引用 `422 IMAGE_UNAVAILABLE`、正文和引用在保存失败时同时回滚、存储暂不可用时普通正文仍可保存。用固定 `Clock` 验证暂存 24 小时边界。另覆盖数据库提交失败及补偿、删除失败后重试，以及以下竞争。

```java
@Test void uploadRacingLibraryTrashCannotPublishImage() {
    var upload = pauseBlobPutAfterStorageReturnsKey();
    markLibraryTrashedInSeparateTransaction(upload.libraryId());
    upload.resume();
    assertEquals(404, upload.reply().status());
    assertFalse(findPublishedImage(upload.key()).isPresent());
    assertTrue(blobWasDeletedOrQueuedForRetry(upload.key()));
}
```

- [ ] 运行 `mvn -f backend/pom.xml -Dtest=ImageLifecycleTest test`，预期图片服务与引用校验未实现而失败。除真实 FastDFS 往返测试外，本类通过可控制失败和阻塞的 `BlobStore` 测试替身运行。
- [ ] 实现 `MediaService.upload(UUID accountId, UUID libraryId, String declaredContentType, byte[] bytes): ImageView` 与 `open(UUID accountId, UUID libraryId, UUID imageId): ImageContent`，其中 `ImageContent(String contentType, InputStream body)`。用实际文件内容确定允许的图像格式，拒绝无法识别或格式声明矛盾的文件，空类型和 `application/octet-stream` 不作为格式声明。先校验归属再写 FastDFS，短事务再次锁定活动库并提交图片元数据后才返回地址。失败时以文件标识核对是否已提交，确认未发布的对象尝试补偿删除，失败记录可定位的内部标识；超时没有标识则记录请求关联 ID 和操作。读取端不暴露 FastDFS 标识和地址，并关闭响应流。
- [ ] 实现正文与图片引用更新。用 Markdown AST 读取图片目标，只有精确匹配应用图片地址的目标才进入图片引用表。保存和导入都校验 URL 的库 ID、图片归属、状态及暂存期限，同一个目标多次出现只记录一条引用。通过 `(library_id,note_id,image_id)` 与复合外键绑定库内归属，保存失败不存新正文。暂存图片引用后转为可用，活动和回收条目的引用一视同仁。写入时不访问 FastDFS。Multipart 单文件配置为 `10485760B`，请求上限 `12582912B`，超限统一映射为 `413 FILE_TOO_LARGE`。
- [ ] 实现图片清理。状态为 `TEMPORARY`、`REFERENCED`、`QUEUED`，清理选中条目后在库锁事务中重新检查引用与过期条件。候选队列按 FastDFS 标识幂等写入，保留独立于知识库的 `blob_key,attempts,next_attempt_at,leased_until`。工作者以短事务认领、事务外删除，成功后短事务清除队列和仍存在的 `QUEUED` 元数据。失败回退至有上限的重试间隔，租约过期后可接管。已排队对象拒绝新增引用，共享图片最后一次解除引用才排队。
- [ ] 重跑该测试与 `mvn -f backend/pom.xml verify`，预期正常路径和故障分支全部通过。
- [ ] 提交 `feat: add private images, references and durable cleanup`。

### Task 7: 内容回收站与子树恢复

**Files:** 新建 `M/V005__content_trash.sql`、`J/trash/ContentTrashController.java`、`J/trash/ContentTrashService.java`、`J/trash/ContentTrashRepository.java`、`T/trash/ContentTrashTest.java`。

**Interfaces:** `DELETE L/notes/{noteId}`、`DELETE L/folders/{folderId}` → `204`。`GET L/content-trash` → `Page<ContentTrashItem>`，其中 `ContentTrashItem(UUID id, String kind, UUID targetId, String name, Instant trashedAt)`，`kind` 为 `NOTE` 或 `FOLDER`。`POST L/content-trash/{batchId}/restore` → `RestoredContent(UUID id, String kind, UUID parentId, String name)`。`POST L/content-trash/{batchId}/purge {confirm:true}` → `204`。消费 `LibraryAccess.lockActive` 及 `ImageCleanupQueue.enqueueUnreferenced`。

- [ ] 写 `ContentTrashTest`。覆盖子树从浏览和搜索隐藏，恢复后图片正常，原位置已不存在时恢复根目录，同名恢复保留两项，`confirm:false` 返回 `400`。另测试先单独删除笔记、再永久删除原文件夹，旧批次仍可恢复到根目录。

```java
@Test void activeAndTrashedReferencesBothKeepImage() {
    var pair = twoNotesShareOneImage();
    var trashed = pair.trashFirst();
    pair.purgeSecond();
    assertFalse(cleanupQueued(pair.imageId()));
    trashed.restore();
    assertEquals(200, pair.downloadImage().status());
    pair.purgeFirst();
    assertTrue(cleanupQueued(pair.imageId()));
}
```

- [ ] 运行 `mvn -f backend/pom.xml -Dtest=ContentTrashTest test`，预期回收、恢复或清除端点尚未实现。
- [ ] 实现单事务的 `ContentTrashService.trashNote/trashFolder(UUID accountId, UUID libraryId, UUID itemId): void`，以 `trash_batch_id` 给原活动条目标记同一批次，跳过独立旧批次。`restore(UUID accountId, UUID libraryId, UUID batchId): RestoredContent` 只解除该批的标记，按原位置、根目录和 `（已恢复）` 后缀规则处理，后缀重复时追加递增数字。`purge(UUID accountId, UUID libraryId, UUID batchId): void` 先解除独立旧批次中指向即将删除父目录的关联，再仅清除此批内容，将其图片标识交给队列重新判重。恢复和 purge 不跨库，所有写入先锁库。用复合外键约束回收批次归属。
- [ ] 重跑该测试和 `mvn -f backend/pom.xml verify`，预期库内回收行为通过。
- [ ] 提交 `feat: add reversible content trash`。

### Task 8: 整库回收、恢复与永久删除

**Files:** 新建 `J/trash/LibraryTrashController.java`、`J/trash/LibraryTrashService.java`、`T/trash/LibraryTrashTest.java`；修改 `J/library/LibraryRepository.java`、`J/media/ImageCleanupQueue.java`。

**Interfaces:** `DELETE L` → `204`。`GET /api/library-trash` → `Page<TrashedLibrary>`，其中 `TrashedLibrary(UUID id, String name, Instant trashedAt)`。`POST /api/library-trash/{libraryId}/restore` → `LibraryView`。`POST /api/library-trash/{libraryId}/purge {confirm:true}` → `204`。消费账号行锁、`ImageCleanupQueue.enqueueLibrary` 与 Task 7 的库内批次关系。

- [ ] 写 `LibraryTrashTest`，覆盖整库回收后旧笔记和图片地址均 `404`，搜索与普通库列表隐藏它，另一个库仍可用。恢复保留独立内容回收批次，重名追加后缀。回收最后一个活动库后，库列表可为空，仍可访问知识库回收站和创建新库。

```java
@Test void permanentLibraryDeletionKeepsCleanupJobsAndOtherLibraries() {
    var state = oneTrashedLibraryAndOneActiveLibrary();
    assertEquals(204, state.purgeTrashedLibrary(true).status());
    assertEquals(404, state.restorePurgedLibrary().status());
    assertTrue(cleanupQueuedByBlobKey(state.purgedImageKey()));
    assertEquals(200, state.readOtherLibraryImage().status());
}
```

- [ ] 运行 `mvn -f backend/pom.xml -Dtest=LibraryTrashTest test`，预期整库回收或恢复接口未生效。
- [ ] 实现 `LibraryTrashService.trash(UUID accountId, UUID libraryId): void`、`restore(UUID accountId, UUID libraryId): LibraryView`、`purge(UUID accountId, UUID libraryId): void`。回收只设置库级状态，原目录、笔记、图片和批次原地保留。恢复只切回库状态并处理名称冲突。硬删除事务先按该库的图片写队列，再有序移除该库引用、条目、批次和库记录。直接永久删除活动库、访问他人条目或恢复已永久删除的库返回 `404`。内容写并发继续使用 `LibraryAccess.lockActive`。
- [ ] 重跑该测试和 `mvn -f backend/pom.xml verify`，预期整库行为通过。用实际整库删除端点再跑任务 6 的上传竞争路径，确认重新校验触发补偿。
- [ ] 提交 `feat: add recoverable nonempty libraries and library purge`。

### Task 9: Vue 会话、工作台和管理员页面

**Files:** 新建 `frontend/package.json`、`frontend/package-lock.json`、`frontend/index.html`、`frontend/tsconfig.json`、`frontend/vite.config.ts`、`F/main.ts`、`F/App.vue`、`F/router.ts`、`F/style.css`、`F/api/client.ts`、`F/api/contracts.ts`、`F/auth/session.ts`、`F/auth/AuthView.vue`、`F/workspace/WorkspaceView.vue`、`F/workspace/LibrarySwitcher.vue`、`F/workspace/FolderTree.vue`、`F/admin/AdminUsersView.vue`、`F/auth/session.test.ts`、`F/workspace/workspaceRouting.test.ts`。

**Interfaces:** `api<T>(path: string, init?: RequestInit): Promise<T>`、`class ApiError extends Error { status: number; code: string }`，JSON 路由以 `/api` 开头，`FormData` 由浏览器设置 multipart 边界。`getViewer(): Promise<Viewer | null>`、`register(username: string, password: string): Promise<Viewer>`、`login(username: string, password: string): Promise<Viewer>`、`logout(): Promise<void>`。在 `contracts.ts` 将任务 3–8 的 HTTP DTO 映射为 TypeScript，ID 为 `string`，时间为 `string`，版本为 `number`，可空字段显式 `| null`。普通用户路由 `/app`、`/kb/:libraryId`、`/kb/:libraryId/folders/:folderId`、`/library-trash`；账号页 `/login`、`/register`；管理员 `/admin/users`。任务 10 扩展工作台子路由。

- [ ] 写 `session.test.ts` 和 `workspaceRouting.test.ts`。先建立 Vue/Vite/Vitest 配置和最小可测试的模块入口。覆盖会话请求失败后回到登录页、写请求的 CSRF header 来自 token 接口、登录后刷新 token、路由角色分流及跨库切换清除旧文件夹状态。

```ts
expect(resolveHome({ role: 'ADMIN' }, [{ id: 'unused' }])).toBe('/admin/users')
expect(resolveHome({ role: 'USER' }, [{ id: 'learning' }])).toBe('/kb/learning')
expect(resolveHome({ role: 'USER' }, [])).toBe('/app')
```

- [ ] 运行 `npm --prefix frontend test -- --run`，预期会话接线或路由断言失败。
- [ ] 实现 API 客户端和角色路由。`GET` 查询携带同源会话，写请求发送当前 CSRF token。登录或注销时刷新 token 缓存；遇 `401` 清理登录状态，禁止静默重放失败的保存或上传。`resolveHome(viewer: Pick<Viewer, 'role'>, libraries: Pick<LibraryView, 'id'>[]): string` 根据角色和已有库选入口。开发服务器将 `/api` 反向代理后端。
- [ ] 实现 `AuthView` 登录和注册表单、`AdminUsersView` 分页和停用恢复。实现 `WorkspaceView` 的私有库列表、创建、命名、切换和文件夹树创建、重命名、移动。当前库和目录从路由参数取得，响应关联发出请求的库 ID，切换后忽略旧请求响应。没有活动库时显示创建入口，知识库回收站入口可用。
- [ ] 运行 `npm --prefix frontend test -- --run` 和 `npm --prefix frontend run build`，预期 Vitest 全绿、`vue-tsc` 和 Vite 正常退出。
- [ ] 提交 `feat: add Vue authentication, workspace and admin flows`。

### Task 10: 编辑预览、搜索和两级回收站界面

**Files:** 新建 `F/editor/NoteList.vue`、`F/editor/NoteEditorView.vue`、`F/editor/MarkdownPreview.vue`、`F/editor/renderMarkdown.ts`、`F/editor/NoteSearchView.vue`、`F/editor/useNoteDraft.ts`、`F/editor/markdown.test.ts`、`F/editor/noteDraft.test.ts`、`F/trash/TrashView.vue`；修改 `F/workspace/WorkspaceView.vue`、`F/router.ts`、`frontend/package.json`、`frontend/package-lock.json`。

**Interfaces:** 新路由 `/kb/:libraryId/notes/:noteId`、`/kb/:libraryId/search`、`/kb/:libraryId/content-trash`，以及填实 `/library-trash`。`renderMarkdown(markdown: string, libraryId: string): { html: string; unresolvedImageCount: number }`。`useNoteDraft(note: NoteView)` 返回 `draftTitle`、`draftMarkdown`、`dirty`、`save(send: (input: SaveNoteInput) => Promise<NoteView>): Promise<void>`，`SaveNoteInput` 为 `{version,title,markdown}`。`TrashView` 接收 `scope: 'LIBRARY' | 'CONTENT'` 和内容模式的 `libraryId`。使用任务 5–8 API。

- [ ] 写 `markdown.test.ts` 与 `noteDraft.test.ts`。覆盖同源本库图片、他库图片、相对和外链占位、禁用原始 HTML、危险链接过滤、保存成功清除脏状态、版本冲突和图片过期时保留草稿。

```ts
const preview = renderMarkdown('![本地](./images/demo.png)\n![外链](https://example.test/x.png)\n<script>bad()</script>', libraryId)
expect(preview.unresolvedImageCount).toBe(2)
expect(preview.html).not.toContain('src="https://example.test')
expect(preview.html).not.toContain('<script>')
await expect(draft.save(sendVersionConflict)).rejects.toMatchObject({ code: 'VERSION_CONFLICT' })
expect(draft.draftMarkdown.value).toBe('未保存的 Markdown')
expect(draft.dirty.value).toBe(true)
```

- [ ] 运行 `npm --prefix frontend test -- --run src/editor`，预期编辑或预览断言失败。
- [ ] 实现笔记列表、新建、移动、删除、`.md` 导入和当前库搜索，连接分页和结果路径。导入成功打开新笔记。编辑器只在用户点击「保存」时提交标题、正文和版本，图片上传成功在光标位置插入返回的应用 URL。路由离开与 `beforeunload` 在修改未保存时提示，保存失败保留光标内容和草稿。
- [ ] 实现 Markdown 预览。禁用原始 HTML，过滤危险 URL，图片只接受当前库的精确同源 API 路径。外链和本地相对路径显示占位，保持编辑器中的原语法。预览中的普通链接限制可用协议。
- [ ] 实现内容与知识库回收站的列表、恢复和永久删除确认。文件夹随其回收批次恢复，整库删除后回到下一个活动库或 `/app`。永久删除弹窗说明对象和影响，仅在用户确认后发送 `{confirm:true}`，失败保留条目并显示原因。
- [ ] 运行 `npm --prefix frontend test -- --run` 和 `npm --prefix frontend run build`，预期通过。
- [ ] 提交 `feat: add Markdown editing, scoped search and trash UI`。

### Task 11: Compose 交付、浏览器验收与备份恢复

**Files:** 新建 `compose.yaml`、`.env.example`、`backend/Dockerfile`、`frontend/Dockerfile`、`ops/nginx/default.conf.template`、`ops/nginx/tls.conf.template`、`README.md`、`docs/operations.md`、`frontend/playwright.config.ts`、`frontend/e2e/knowledgehub.spec.ts`、`frontend/e2e/persistence.spec.ts`、`frontend/e2e/fixtures/tiny.png`、`frontend/e2e/fixtures/学习笔记.md`；修改 `frontend/package.json`、`frontend/package-lock.json`、`.gitignore`。

**Interfaces:** Compose 服务为 `web`、`backend`、`db`、`tracker`、`storage`；卷为 `pg-data`、`tracker-data`、`storage-data`。对外为 `KH_WEB_BIND`（默认 `127.0.0.1`）与 `KH_WEB_PORT`（默认 `8080`）。Compose 环境文件传入数据库凭据、首次管理员密码及待选 HTTPS 证书与私钥路径。浏览器验收使用 `KH_E2E_BASE_URL` 和 `KH_E2E_ADMIN_USERNAME`、`KH_E2E_ADMIN_PASSWORD`。`npm --prefix frontend run test:e2e` 为 Playwright 入口。

- [ ] 写 `knowledgehub.spec.ts`。通过浏览器注册两个普通用户，执行多库创建与隔离、中文 Markdown 导入、补传图片、手动保存、当前库搜索、目录回收和整库恢复。管理员停用在线用户并恢复，他必须重新登录。准备通过 API 访问同一地址进行资源归属断言。写 `persistence.spec.ts`，按 `KH_PERSISTENCE_MODE=seed|verify` 创建或核对带图笔记，记录相同账号、笔记路径、正文与图片字节摘要到本地测试状态文件，供重启和恢复两阶段使用。

```ts
await expect(page.getByRole('heading', { name: '学习笔记' })).toBeVisible()
await expect(page.getByRole('img', { name: '截图' })).toBeVisible()
expect((await otherUser.request.get(privateImageUrl)).status()).toBe(404)
expect((await admin.request.get(privateImageUrl)).status()).toBe(403)
expect((await oldUser.request.get(privateImageUrl)).status()).toBe(401)
```

- [ ] 运行 `npm --prefix frontend ci`，再运行 `npm --prefix frontend exec -- playwright install chromium`。设定 `KH_E2E_BASE_URL=http://127.0.0.1:18080` 后运行 `npm --prefix frontend run test:e2e -- knowledgehub.spec.ts`，预期空环境无法访问应用。
- [ ] 实现正式 Compose。后端镜像用任务 2 的 SDK 安装脚本构建，FastDFS 使用同一基础镜像与经过验证的配置。Nginx 代理 `/api` 和提供 Vue history fallback，`client_max_body_size` 采用 `12m`。仅 `web` 配置 `ports`。数据库、Tracker、Storage 有可用性检查，后端准备就绪后才提供 Web 入口。HTTP 用作本机验证，启用 TLS 时由 Nginx 重设转发协议和来源信息，应用发送 `Secure` 会话 Cookie。PostgreSQL 和 FastDFS 卷跨容器重启保留数据。
- [ ] 写 `docs/operations.md` 中的恢复流程。备份时停止 `backend` 和 `web`，用 `pg_dump -Fc` 在容器中生成转储再复制到宿主；停止 Tracker 和 Storage 后将各数据卷只读挂载到归档容器，生成与数据库转储同一批次的归档。恢复时使用新 Compose 项目名和空数据卷，先用 `pg_restore --exit-on-error --no-owner` 导入空数据库、离线解包 FastDFS 卷，再启动应用。记录 Tracker 与 Storage 配置版本及散列。Windows 上通过文件复制传输二进制备份，避免文本重定向。
- [ ] 用本地 `.env.acceptance` 的测试凭据和 `KH_WEB_PORT=18080` 运行 `docker compose -p knowledgehub-acceptance --env-file .env.acceptance config`，确认只有 Web 发布端口，然后用相同参数运行 `up -d --build --wait`。重新跑真实 FastDFS 往返、`mvn -f backend/pom.xml verify`、`npm --prefix frontend ci`、前端单测和构建，并用上述 Compose 实例跑 `knowledgehub.spec.ts`，预期全部通过。
- [ ] 用 `persistence.spec.ts` 的 `seed` 模式写入账号、带图笔记和测试状态。执行 `docker compose -p knowledgehub-acceptance --env-file .env.acceptance restart`，待健康就绪后用 `verify` 模式校验原数据。按文档备份并恢复到另一项目 `knowledgehub-restore`、新数据卷及端口 `18081`，对新实例重跑 `verify`。预期同一账号可登录、正文和图片摘要匹配，原实例的数据卷仍存在。记录实际命令和 Playwright 结果。
- [ ] 提交 `chore: ship Docker Compose and verified recovery workflow`。

## 验收追踪

| 设计稿场景 | 计划覆盖 |
| --- | --- |
| 多用户、多库与管理员隔离 | 任务 3、4、6、8、9、11 |
| 导入、补图、手动保存、当前库搜索、并发冲突 | 任务 5、6、10、11 |
| 文件夹移动、库内回收、独立旧回收条目与图片复用 | 任务 4、7、10、11 |
| 整库回收、恢复与用户确认永久删除 | 任务 8、10、11 |
| 账号停用、恢复及旧会话失效 | 任务 3、9、11 |
| 真实 FastDFS、容器重启和跨数据卷演练 | 任务 2、11 |
