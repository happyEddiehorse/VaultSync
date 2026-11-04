# VaultSync

[English](README.md) | 简体中文

VaultSync 是一个 Android 应用，用 Git 把一个或多个 [Obsidian](https://obsidian.md) 库与远端仓库（GitHub 或其它 Git 服务）**手动**同步。
它和桌面端的 [obsidian-git](https://github.com/Vinzent03/obsidian-git) 插件配合使用：同一个仓库、同样的忽略规则，两边互不干扰。
它是专门给 Obsidian 用的 Git 同步工具，不是通用的 Git 客户端。

## 功能

- 一键同步：提交、拉取合并、推送。
- 克隆已有仓库；把已有的库初始化成新仓库；直接接管已经带 `.git` 的库。
- 在应用里按块解决合并冲突，也可以手动编辑；不会静默覆盖任何内容。
- 支持 HTTPS（令牌）和 SSH（在应用里生成或导入密钥），可设代理。
- 多个库，各自独立同步。
- 同步被打断后，能修复留下的锁文件、未完成的合并等。
- 加密备份与恢复应用设置、令牌和密钥。
- 8 种界面语言。

只在你点同步时才同步，没有定时同步，也没有后台自动同步。

## 系统要求

- Android 10（API 29）及以上。
- 库放在共享存储里（例如 `Documents/`）；Android 11 及以上需要授予"所有文件访问权限"。

## 构建

需要 JDK 21 和 Android SDK。

```bash
./gradlew :gitcore:test        # 同步引擎的单元测试（纯 JVM）
./gradlew :app:assembleDebug   # debug 包在 app/build/outputs/apk/debug/
```

`gitcore` 模块放 Git 与同步逻辑，不依赖 Android；`app` 放 Compose 界面和平台相关代码。
JGit 锁定在 5.13.x：更新的版本用到了 Android 10 没有的 JDK API。

## 许可证

VaultSync 是自由软件，使用 [GNU 通用公共许可证 第 3 版](LICENSE)（GPL-3.0）。

"VaultSync" 这个名称和应用图标不在 GPL 授权范围内（GPL-3.0 第 7 条 (e)）。
如果你发布修改版，请换一个名称、图标和应用 ID，以免与本应用混淆（第 7 条 (c)）。

VaultSync 与 Obsidian 官方无关。
