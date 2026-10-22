# 校园寻物站 CampusLostFound

本项目使用 Android Studio、Java 和 XML 开发。三位成员共用一个安卓工程，手机主机和客户端的代码均放在 `app` 模块内。

本文件说明目录用途、代码放置位置和命名规范。功能分工、开发顺序和接口字段以 `docs` 中的开发文档为准。

## 项目目录

```text
CampusLostFound/
├── docs/                         # 开发计划和共同接口约定
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/example/campuslostfound/
│   │   │   │   ├── MainActivity.java
│   │   │   │   ├── ui/           # 界面与列表适配器
│   │   │   │   ├── server/       # 手机主机与 HTTP 请求处理
│   │   │   │   ├── data/         # SQLite 数据库与数据访问
│   │   │   │   ├── api/          # 客户端网络请求与业务调用
│   │   │   │   └── common/       # 公共常量和复用工具
│   │   │   ├── res/              # XML 布局、图片、文字与主题
│   │   │   └── AndroidManifest.xml
│   │   ├── test/                # 本地单元测试
│   │   └── androidTest/         # 设备或模拟器上的测试
│   └── build.gradle.kts         # app 模块的构建配置和依赖
├── gradle/                      # 依赖版本与 Gradle Wrapper
├── build.gradle.kts             # 项目构建配置
├── settings.gradle.kts          # 模块与仓库配置
├── gradle.properties            # 项目构建参数
├── gradlew                      # Gradle 启动脚本
├── gradlew.bat                  # Windows Gradle 启动脚本
├── .gitignore                   # Git 忽略规则
└── README.md                    # 本目录说明
```

其中 `ui`、`server`、`data`、`api` 和 `common` 是规划中的 Java 包，按开发需要逐步创建，不要求开工前一次建齐。现有 `MainActivity.java` 保留在根包，新增页面放入 `ui`。

## 开发文档

将三份文档放入根目录的 `docs` 文件夹，文件名统一为：

- `后端A开发计划.md`：手机主机、连接、数据基础和图片工具。
- `后端B开发计划.md`：发布、查询、认领和状态处理。
- `共同接口约定.md`：双方共用的请求、返回、数据结构和业务规则。

同一份文档只在这里维护，不在各人的代码包里另存副本。接口变化时同步更新 `共同接口约定.md`。前端计划确定后也放入 `docs`。

`docs/.gitkeep` 仅用于让空文件夹能够随 Git 提交，放入文档后可删除。

## Java 代码归属

| 目录 | 放什么 | 主要维护者 |
| --- | --- | --- |
| `ui` | Activity、页面逻辑、Adapter；需要 Fragment 时也放这里 | 前端人员；A 可先编写主机和临时调试页面 |
| `server` | `LanServer`、`ServerManager`、`SessionStore`、`ImageStore`，以及业务请求 Handler | A 维护基础服务，B 维护 `PostHandler`、`ClaimHandler` |
| `data` | `AppDbHelper`、`PostDao`、`ClaimDao` 和数据库相关数据对象 | A 维护建表和公共数据库工具，B 维护帖子及申请的数据访问 |
| `api` | `LanApiClient`、`LostFoundApi`，供界面调用主机接口 | A 维护通用网络请求，B 维护业务调用方法 |
| `common` | 分类与状态常量、统一 JSON 返回、公共数据库锁等复用工具 | A 统一维护，B 使用前对齐约定 |

这里的目录职责与开发计划配合使用，不额外增加模块。类名可按实际开发调整，调整后同步告知相关成员。

界面通过 `api` 请求主机；主机请求处理代码通过 `data` 读写数据库。客户端页面不直接读写一套独立的业务数据库。

公共类只维护一份，不各自创建第二套数据库、网络工具或状态常量。修改共同使用的类、`AndroidManifest.xml` 或构建配置前，先与相关成员确认。

## 资源与命名

- Java 包名用小写，例如 `server`、`data`；每个类通常单独一个文件。
- Java 类名用大驼峰，例如 `PostDao.java`；方法和变量用小驼峰，例如 `getMyPosts`；常量用大写加下划线，例如 `STATUS_OPEN`。
- 布局放入 `res/layout`，文件名用小写加下划线，例如 `activity_host.xml`、`item_post.xml`。
- 图标和界面图片放入 `res/drawable`；文字放入 `res/values/strings.xml`；颜色和主题放入对应的 `values` 资源文件。
- 不把主机运行时生成的数据库、用户照片、token、手机地址配置提交进仓库。这些由 App 在手机上保存。
- `local.properties`、`.gradle`、各模块的 `build` 等本机配置和生成文件按 `.gitignore` 忽略，不手动加入 Git。
- `gradle` 目录、构建配置、`gradlew` 和 `gradlew.bat` 需要提交，供成员使用同一套项目配置。
