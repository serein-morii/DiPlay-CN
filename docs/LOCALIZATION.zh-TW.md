# 繁體中文翻譯維護

[繁體中文首頁](../README.zh-TW.md) · [建置與完整檢查](BUILD.zh-TW.md#完整檢查)

## 語系與用語

主要應用程式繁體中文資源位於 `common/src/main/res/values-zh-rTW/` 及 `shared/src/main/res/values-zh-rTW/`，分散於多個 XML，不只 `strings.xml`。選用的 Home 與地圖範例資源位於 `samples/home/src/main/res/values-zh-rTW/` 及 `samples/maphost/src/main/res/values-zh-rTW/`，依系統語言顯示。主要應用程式語言代碼維持 `zh-TW`，網站使用 `zh-Hant`。語言選單維持 **繁體中文**，網站註明臺灣用語。

| English | 繁體中文 |
| --- | --- |
| Settings / Connect / Reconnect | 設定／連線／重新連線 |
| Save / Apply / Restore | 儲存／套用／還原 |
| Resolution / Frame rate | 解析度／影格率 |
| Audio / Audio stream | 音訊／音訊串流 |
| Diagnostic report | 診斷報告 |
| Head unit / Firmware | 車機／韌體 |
| Experimental | 標題後綴使用 `（實驗性）` |

保留 DiPlay、CarPlay、iPhone、Siri、Wi-Fi Direct、USB、ADB 等產品與技術名稱；不要翻譯協定識別碼、設定鍵、檔名或診斷程式用來比對的代碼。以來源語意為準，不只做簡繁字形轉換。設定會在重新連線或下次連線才生效時，譯文也必須明確說明。

## 修改資源

1. 從 `values/` 對照所有可翻譯字串，保留資源名稱。
2. 保留 `%1$s`、`%2$d` 等格式參數、索引、次數與型別，避免執行時格式錯誤；同時確認 XML 跳脫字元與換行。
3. 新增設定字串時，遵守根目錄 `AGENTS.md`，同步提供所有語系的翻譯。
4. 檢查首次設定、主畫面、所有設定分類、連線錯誤與手機備份頁。手機備份文字另位於 `common/src/main/assets/settings-backup.html`。
5. 窄視窗、大字體及高密度螢幕下確認標題、設定列與對話框沒有截斷；若修改設定版面，依 `AGENTS.md` 提供 compact/full 大字體截圖，列對齊修改另加阿拉伯文截圖。

`TraditionalChineseTranslationsTest` 檢查 common/shared 與兩個範例的完整可翻譯 string 資源鍵、重複名稱與格式參數。`SettingsTranslationsTest` 檢查各語系的 `settings_*`。`AppLocaleTest` 已涵蓋新舊 Android、臺灣／香港／澳門及 `Hant` 選擇。`AdaptiveSettingsUiTest` 也檢查繁體中文在窄視窗、完整設定頁、高密度及 1.3 倍字體下是否省略文字；Home 的返回操作測試同時驗證繁體中文編輯及應用程式清單操作。這些檢查不能取代人工校訂與實車測試。

## 文件與網站同步

繁體中文文件使用 `.zh-TW.md` 後綴，入口為 `README.zh-TW.md`。來源文件或功能變更時，同步更新中文步驟，並確認選單名稱與 `values-zh-rTW` 一致。深入英文文件尚未翻譯時，明確標示英文連結；不要將較舊版本的驗證紀錄寫成新版本保證。

網站內容來源為 `site/content.json` 的 `zh-Hant` 區塊，透過以下指令產生靜態 HTML：

```sh
python scripts/build_site.py
```

網站的安裝、隱私及說明入口指向繁體中文文件。修改 JSON 後一併提交產生的 `site/zh-Hant/index.html`；產生頁面不代表已發布網站。推送與網站部署依專案的發布流程處理。
