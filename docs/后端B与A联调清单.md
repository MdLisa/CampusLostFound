# 后端 B 与 A 联调进度（2026-10-22）

`BranchB` 已合入 `BranchA` 的第三步提交 `106326d`。A 的建表、统一数据库锁、`AppDbHelper.read` / `withWriteTransaction`、token 身份、1 MB JSON 请求读取、`LanApiClient` 均已复用；没有另建数据库、网络客户端或锁。接口字段仍以 `共同接口约定.md` 为准。

## 已接入

- `AppDbBusinessPort` 把 B 的 DAO 读写交给 A 的 `AppDbHelper`；读取也在公共锁内完成，多步写操作走同一事务。
- `BusinessHttpAdapter` 用 A 的 `SessionStore.requireMemberFromHeader` 取得成员 ID，再调用 B 的 `BusinessRouter`。公开帖子列表与详情无需 token；申请和个人接口要求 token。
- A 的 `LanServer` 仅增加业务分发调用；`ServerManager` 启动时注入同一个 `AppDbHelper`、`SessionStore` 和业务路由。A 后续修改这两个公共类时请保留该接线，不重复读取请求体。
- `LostFoundApi` 直接包装 A 的 `LanApiClient.request`，沿用其后台线程、主线程回调、token、取消请求和错误处理。
- 主代码与设备测试源码已在 JDK 25、Android SDK 37 下编译通过。当前没有连接设备，设备测试尚未实际运行。

## 仍需 A 提供图片工具

目前 `ServerManager` 向 `AppDbBusinessPort` 传入 `null` 图片实现，所以无图的 12 个业务接口可接线运行；带图发布会明确返回 500“图片服务尚未接入”。A 完成 `ImageStore` 后，需让它实现或适配 `AppDbBusinessPort.Images` 的 `saveJpeg(base64)` / `deleteJpeg(fileName)`，并在 `ServerManager` 注入。`saveJpeg` 要验证 Base64、JPEG 与解码后 500 KB 上限，格式错误抛 `BusinessException(400, ...)`，过大抛 `BusinessException(413, ...)`；读取 `/images/{fileName}` 仍由 A 路由负责。数据库写入失败时 B 会删除本次新图片。

## 联调验证顺序

1. 两台手机先验证 A 的 ping、创建和恢复身份；SDK 37 的局域网权限须实测。
2. 用不同身份验证无图发布、公开列表与详情、我的发布；公开响应不能包含 proof/contact。
3. 验证提交申请、我提交的申请和发布者收到的申请，包括 401/403/404/409。
4. 验证确认、拒绝、取消、站外完成与关闭。两个申请同时确认，最终最多一个 `ACCEPTED`；提交与关闭并发后，`CLOSED` 帖不能有 `PENDING`。
5. A 图片工具接入后验证带图发布、跨手机读取、非法图片和超限图片；再让前端用 `LostFoundApi` 接页面。

已编译不等于真机验证。A 的第三步文档记录了其身份和数据库测试范围；上述 B 业务链路仍待设备联调。
