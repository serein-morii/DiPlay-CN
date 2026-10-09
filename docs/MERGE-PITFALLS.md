# CN 同步官方版本的踩坑记录

从官方 tag（v0.2.x）全量合并到 CN 仓库时反复遇到的坑。流程本身见各次同步提交；本文只记「不踩一次就不知道」的点。最近一次验证：v0.2.15 → 0.2.15-cn.1（2026-10-08）。

## 合并方向与 --ours/--theirs 是反的

我们的流程是 `git checkout -b sync-X v0.2.X && git merge main`：**HEAD 是官方，CN 在 theirs**。要保留 CN 侧必须 `git checkout --theirs`（strings、README 等），和解直觉相反。用脚本按内容取 `>>>>>>> main` 一侧最稳。

## 官方收编了我们自己的 PR 时，冲突是「旧版 vs 演进版」

官方 0.2.15 合并了 CN 的 #438/#439/#440/#441。此时同一功能两边都有：官方侧是 PR 评审时定稿的版本，CN 侧是发版后继续演进的版本。判定原则：**保 CN 演进版**（ADB 优先的小屏自动、菜单打开期间不重连、1% 网格滑条与 cn.3 迁移等），除非官方版本明显更严格（如 BydBootStartRepair 的白名单严格解析、BydClusterMapPause 的取消暂停不残留——这两处取了官方侧）。

## 冲突块之外会留下「同名双份」成员

git 只保证冲突块内二选一；两边的 companion 常量（`CLUSTER_SMALL_WINDOW_ON/AUTO`）、局部 `val smallWindow`、整个函数（`repairBootStart` 出现两次）可能在块外被双双保留，报 `Conflicting declarations / Conflicting overloads`。**推 CI 前先本地 `./gradlew :mobile:compileDebugKotlin`（约 15 秒）能一次抓全**，别浪费 20 分钟 CI。

## 上游新功能的「半截」会漏进来

上游 #413 自带更新器：UI 在冲突块里（被 CN 四通道更新器替换掉了），但状态变量（`updateStage`/`updateRelease`…）和 `import ...UpdateRelease` 在块外自动合并进来了 → `Unresolved reference 'UpdateStage'`。删状态变量时连 import 一起删；其 manifest 权限（REQUEST_INSTALL_PACKAGES）与 provider（`.update-apks`）保留共存无冲突。

## 注入 strings 前先查 CN 专属资源文件

补官方新增键时，先确认 CN 是否已在别处定义同名键：`connect_when_iphone_bluetooth_connects` 早就在 **cn_connection_preview.xml**（#439 的 CN 实现用了和官方相同的键名），直接注入 strings.xml 会 `Duplicate resources`。同理官方 `update_*` 八个键不需要——更新器是 CN 自己的。

## 每个新增键必须 ×7 locale 同步

values / zh-rCN / zh-rTW / es / ru / ar / uk 全部要加（zh-TW 手写，繁体用词按台湾习惯：設定精靈、連線、藍牙）。文案里的撇号必须转义成 `\'`（aapt），引号用「」/“”都可以。写完用 ElementTree 逐个文件 parse 验证。

## 几个固定哨兵（合并后必查）

- `REQUEST_INSTALL_PACKAGES`（common manifest；历史上在 0.2.11 rebase 丢过一次，导致所有通道更新都不弹安装器）
- FileProvider authorities：CN 的 `${applicationId}.update`（update_paths.xml）必须在
- `signing/diplay-cn.jks`、applicationId `com.shihab.diplay.cn`、versionName/tag 一致（release.yml 强制）
- `app_name` 在 values(EN) 里本来就是 `DiPlay`——别误报，CN 桌面标签在 activity-alias 上

## 版本与杂项

- versionName `<官方>-cn.1`、versionCode +1；0.2.15 起官方 minSdk 降到 25（Android 7.1 支持），CN 跟随。
- `site/*.html` 冲突别手解：取 CN 的 `scripts/build_site.py`，改 `VERSION`，`python3 scripts/build_site.py` 重新生成全部页面。
- 文档：README ×2 的「基于官方 vX」引用、INSTALL 的复现版本号、CN_OPTIMIZATIONS.md 顶部插条目、GITHUB_RELEASE.md 保留 `{{VERSION}}` 占位符。
