# 后端 B 与 A 的接入点

## BranchB 整合记录（2026-10-26）

`BranchB` 已整合 A 的第五步图片实现 `8ea082e` 与第六步异常恢复改动 `c888393`。B 的十二个业务路由、DAO、Handler 和 `LostFoundApi` 继续使用 A 的唯一数据库、公共事务锁、身份校验、HTTP 服务、`ImageStore` 与 `LanApiClient`。单张图片随发布请求保存，帖子只保存文件名，公开读取使用 `/images/{fileName}`；没有新增数据库表或接口。

本轮补充 B 的设备回归用例：标题搜索把 `%` 当普通字符、关闭帖从公开列表隐藏但仍能在本人列表和详情查询；拒绝、取消、站外完成的权限及最终状态；图片已保存但帖子事务失败时调用清理且没有留下帖子。A 的 `BackendRecoveryInstrumentedTest` 另覆盖真实 HTTP、持久化数据库及 JPEG 读取。

合并后 `assembleDebug`、`lintDebug`、`compileDebugAndroidTestJavaWithJavac` 和本地单元测试已通过。用户从 Android Studio 手动启动 Pixel 9（Android 16 / API 36）后，`connectedDebugAndroidTest` 实际执行 17 项、失败 0 项：其中 `BusinessFlowTest` 7 项、`BackendRecoveryInstrumentedTest` 4 项、`LanServerInstrumentedTest` 5 项和模板测试 1 项。报告见 `app/build/reports/androidTests/connected/debug/index.html`。跨手机网络、Android 37 局域网权限及完整前端仍需按下文和第五、六步说明验收。

## A 提供的公共接入约定

第四步已将 moren/BranchB 合入 BranchA 并提交为 4dd350d；第五步图文功能已提交为 8ea082e，用户反馈测试通过。第六步补强生命周期和失败提示，并补充回归测试源码；设备异常恢复验收待用户完成。HTTP 路径和字段不变。

1. LanServer 将 B 的十二个业务接口分发给 BusinessRouter；解析查询参数及有界 JSON 请求体，通过 SessionStore 解析 Bearer 身份。公开列表和详情不强制登录，私有接口不信任请求体中的操作者 ID。未知接口仍返回 404。
2. DatabaseBusinessPort 复用唯一 AppDbHelper。所有只读查询、查询中的权限检查使用 BusinessPort.read，在 DatabaseLock.DB_LOCK 内完成。readableDatabase 只保留兼容入口，生产适配器要求已持有公共锁；不要取得数据库后带到锁外查询。
3. writeTransaction 直接复用 AppDbHelper.withWriteTransaction。通过内部异常桥接保留 B 的受检异常，业务失败既回滚数据库，也正确返回原有 400、403、404、409 等状态。身份创建与业务写入共用同一把锁。没有更改数据库版本或重建旧表。
4. 第五步已接入 ImageStore。DatabaseBusinessPort.saveJpeg 验证并保存单张 JPEG，deleteJpeg 用于写库失败清理；GET /images/{fileName} 返回已发布图片的 JPEG 字节。客户端通过 common/ImageCompressor 压缩，使用 LanApiClient.loadImage 读取。具体规则及待验收项目见 docs/第五步测试与交接说明.md。
5. LanApiTransport 实现 LostFoundApi.Transport，复用 LanApiClient 的地址、token、网络线程、超时和主线程回调。创建方式：new LostFoundApi(new LanApiTransport(client))。成功回调为 HTTP 200 和完整响应包装；HTTP 错误保留状态码和失败包装，error 为 null；本地或网络错误为状态 0、response=null、error 非空。页面结束时关闭其 LanApiClient。
6. ConnectActivity 在创建或恢复身份后显示“进入业务联调”。BusinessDebugActivity 提供 B 的十二个方法和单张图片的临时表单，通过网络调用主机，不读取客户端本地业务数据库。每次进入会再次验证身份。返回连接页后需重新检测，正常覆盖安装保留此前身份。
7. compileSdk、targetSdk 和协议版本保持原值。现有主机／客户端的权限、HTTP 和后台停止规则继续复用。

8. 主机临时页面在所有实例间共用一个串行启停队列；正式主机页也必须保证旧页面的停止先于新页面的启动。ServerManager.start/stop 在后台执行，isRunning 是不等待启停锁的状态快照。前端仍遵守主机离开前台即停止，不增加后台保活。
9. 连接和业务页面离开前台时关闭本页 LanApiClient，再次进入创建新实例。close/cancel 只停止向旧页面回调，不代表已发出的写操作回滚。网络失败或退出时未取得写入结果，应先查我的发布、我的申请或收到的申请，不能自动重发 POST。相册返回仍重新验证身份。
10. 401 表示身份失效，返回连接页重新进入；断网、超时等本地错误保留现有身份。更换基础地址或 ping 检出的 hostId 不同会清除原身份；重新填写同名昵称也不会恢复旧用户。

详细业务验收见 [第四步联调说明](第四步联调说明.md)，异常恢复、交付范围和前端接入注意事项见 [第六步测试与交接说明](第六步测试与交接说明.md)。本轮只构建、静态检查和编译测试源码，不运行模拟器、设备测试或自动发送业务请求。第六步验收完成后由用户提交并推送 BranchA。
