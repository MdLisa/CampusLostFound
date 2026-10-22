# 后端 B 与 A 的接入点

后端 B 的五个主要类和 `BusinessRouter` 已在 `BranchB`。当前仓库尚无 A 的公共实现，因此不能在手机上运行这 12 个接口。以下是合并前需要由 A、B 对齐的**具体 Java 接口**；HTTP 字段和业务规则仍以 `共同接口约定.md` 为准。

1. A 的 `LanServer` 在解析路径、查询参数和 JSON 请求体后，将业务路径交给 `BusinessRouter.route(method, path, query, body, memberId)`。返回 `Reply` 时直接使用 `status` 和 `json` 发送 UTF-8 JSON；返回 `null` 表示 A 自己的接口。未知业务路径由 B 返回 404。公开的 `GET /api/posts` 和 `GET /api/posts/{id}` 可传 `memberId=0`；其他路由先从 Bearer token 解析真实成员 ID，无效或缺失传 0，B 返回 401。不要读取客户端提交的 ownerId、memberId 等字段作为身份。
2. A 实现 `BusinessPort`，只使用**同一个** `AppDbHelper`。`writeTransaction` 必须在 A 的 `DB_LOCK` 内获取可写数据库、开启 SQLite 事务、运行回调、成功时 `setTransactionSuccessful()`，最后 `endTransaction()` 并释放锁。异常应原样抛出。A 的身份创建也要使用这把锁。`readableDatabase` 返回这套数据库，不创建 B 专用库。调用 `BusinessRouter` 的线程必须是 HTTP 工作线程，不能在界面主线程执行数据库操作。
3. A 建立 `members`、`posts`、`claims` 三张表，字段及约束按共同接口约定第九节。尤其需要 `claims` 的 `UNIQUE(post_id,applicant_id)`、外键开关和索引。B 的 DAO 不负责建表。
4. A 的 `ImageStore` 适配 `saveJpeg` / `deleteJpeg`。`saveJpeg` 验证 Base64、JPEG 和解码后 500 KB 上限，返回随机的安全文件名；格式错误抛 `BusinessException(400, ...)`，过大抛 `BusinessException(413, ...)`。HTTP 入口在读取请求体时限制总量 1 MB，超出返回 413。保存图片后数据库写入失败，B 会调用 `deleteJpeg` 清理。图片读取 `/images/{fileName}` 仍由 A 实现。
5. A 的 `LanApiClient` 适配 `LostFoundApi.Transport.request(method, path, body, callback)`，负责基础地址、token 请求头、后台网络和主线程回调。`LostFoundApi` 已提供前端使用的 12 个方法。A 与前端需确认回调错误如何显示及图片压缩工具入口。
6. 工程当前 `compileSdk` 与 `targetSdk` 都是 37，保持不变。A 在公共 `AndroidManifest.xml` 和主机/客户端连接流程中统一处理 SDK 37 的局域网权限、HTTP 明文连接配置及运行时授权；B 不另改这些共享配置。

联调时先验证无图发布、公开列表、详情、我的发布；再验证提交、双方申请列表；然后验证确认、拒绝、取消、站外完成、关闭；最后测带图发布。并发验证应让两个请求同时确认不同申请，最终最多一条 `ACCEPTED`；再同时提交申请与关闭，最终 `CLOSED` 帖没有 `PENDING`。这些结果依赖 A 正确实现共享锁和事务。
