# 繁體中文支援驗證紀錄

[繁體中文首頁](../README.zh-TW.md) · [完整建置指令](BUILD.zh-TW.md#完整檢查)

日期：2026-10-12。分支：`codex/traditional-chinese-support`。驗證環境：Windows、專案 Gradle wrapper 9.5.0、JDK 25 工具鏈及 Android SDK 37.0。

## 本次變更的檢查

| 檢查 | 結果 |
| --- | --- |
| common 的 997 個字串、shared 的 2 個字串 | 繁體中文資源鍵與格式參數相符，沒有重複鍵 |
| Home 的 21 個字串、map host 的 14 個字串 | 英文與繁體中文資源鍵及格式參數相符 |
| 語言、翻譯及設定畫面相關測試 | 24 項通過；包含新舊 Android 的語言選擇，以及窄／完整視窗、高密度、1.3 倍字體的文字省略檢查 |
| Home 返回操作測試 | 6 項通過，包含繁體中文、編輯模式與應用程式清單操作 |
| mobile／home／maphost lintDebug | 三個模組通過 |
| mobile／home／maphost assembleDebug | 三個 debug APK 建置通過；一般原始碼建置不含配件認證素材 |
| 靜態網站產生 | 8 個語系頁面產生成功，繁體中文入口連到中文文件 |
| 文件連結與標題錨點 | 繁體中文文件內的本機目標與錨點可以解析 |
| 公開原始碼與差異檢查 | `scripts/check_public_tree.py` 及 `git diff --check` 通過 |

## 完整 CI 已通過

首次完整測試在 Windows 環境出現 10 項失敗。以三組測試建立 9 秒的重現後，確認原因是主機命令列與檔案系統語意不等同 Android，已修正測試執行器及相容層：

| 測試類別 | 初次失敗數 | 根因與修正 |
| --- | --- | --- |
| `HotspotJoinNormalizedTransactionTest` | 3 | Windows 的 `File.renameTo` 無法取代既有日誌；測試只替換 AtomicFile 的 rename 步驟，保留 Android 的替換行為及真實日誌驗證 |
| `UsbPermissionSetupTest` | 3 | Windows 改寫 `sh -c` 腳本的雙引號；改由標準輸入傳送原始腳本，並自動尋找標準 Git for Windows shell |
| `UpdateInstallCompatibilityTest` | 4 | FileProvider 的 Android 路徑分隔語意與 Windows 主機不同；使用專案既有的 `FileProviderPathTestShadow` |

新增回歸檢查涵蓋每個交易階段的日誌替換及重新讀取、shell 引號與字面特殊字元，以及拒絕 FileProvider 根目錄外但具有相同名稱前綴的目錄。原有同步失敗、交易回復、完整設定內容與公開 APK 校驗測試繼續執行，正式版程式及檔案存取範圍沒有變更。

已重新執行 `AGENTS.md` 的全部九個任務，結果為 `BUILD SUCCESSFUL`（3 分 31 秒）：

| 模組 | 單元測試數 | 失敗／錯誤／略過 |
| --- | --- | --- |
| shared | 1,367 | 0／0／0 |
| common | 1,170 | 0／0／0 |
| home | 6 | 0／0／0 |
| 合計 | 2,543 | 0／0／0 |

mobile、home、maphost 的 `lintDebug` 與 `assembleDebug` 也全部通過。此為本機完整 CI 指令的結果，不代表已取得 GitHub Actions 的遠端檢查結果。

本機結果摘要位於 `.gradle/test-portability-summary.json`，完整測試報告位於各模組的 `build/reports/tests/testDebugUnitTest/`。首次失敗紀錄與 common 報告副本分別保留於 `.gradle/traditional-chinese-validation.log` 與 `.gradle/traditional-chinese-full-tests/common/`。這些是忽略的本機產物，不提交到 Git。

## Android 手機實機檢查

同日透過已授權的 USB ADB 在 V2130 手機上安裝 `mobile-debug.apk`（套件 `com.shihab.diplay.hudtest`，版本 `0.2.18-hud-test`），安裝結果為 `Success`。

手機使用 Android 14（API 34）、1080 × 2400 螢幕、440 dpi，系統語言為 `zh-Hant-TW`。本次維持手機原本的螢幕、字體及系統語言設定。

- 設定概覽、語言頁及進階設定可正常開啟，介面使用繁體中文。
- 語言選單顯示「繁體中文」，套用後設定列顯示「應用程式語言 · 繁體中文」。
- 強制停止並重新啟動後，Android LocaleManager 回報 `[zh-TW]`，語言選擇保留。
- 開啟音訊焦點後，新增「暫時失去音訊焦點時將媒體靜音」及完整說明可見；已檢視實機截圖，這兩個新增字串沒有截斷。
- 檢查後將音訊焦點恢復為原本的關閉狀態，保留繁體中文選擇，並將手機停在設定概覽。

實機截圖：[繁體中文語言頁](screenshots/zh-TW/android14-language.png)、[進階音訊焦點](screenshots/zh-TW/android14-audio-focus.png)。

此原始碼 debug APK 沒有配件認證素材，因此會顯示「無法載入 CarPlay 認證」；這是預期限制，不影響上述介面檢查。尚未進行實車或 iPhone／CarPlay 連線驗證，也尚未發布網站。手機實測與自動化文字省略檢查不能保證每種字型與車機都沒有顯示問題。
