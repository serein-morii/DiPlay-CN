# DiPlay CN

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md)

為相容的比亞迪 Android 車機提供有線與無線 CarPlay，採用熟悉的 DiAuto 介面。此 CN 社群版本的套件名稱為 `com.shihab.diplay.cn`，可與官方 DiPlay 並存。

> 專案以比亞迪汽車為主要支援對象。其他品牌可能可以執行，但不在一般支援範圍內；特定韌體的個別整合不代表整個品牌受到支援。

[下載 CN 版本](https://github.com/serein-morii/DiPlay-CN/releases/latest) · [Gitee 國內下載](https://gitee.com/oneeyear/DiPlay-CN/releases/latest) · [回報 CN 問題](https://github.com/serein-morii/DiPlay-CN/issues/new)

![DiPlay 主畫面](site/assets/home.png)

## 開始使用

請將 DiPlay 安裝在**車機上，而非 iPhone**。車機需要允許安裝 APK，且 Android 版本須為 7.1 以上（API 25+）；Android 7.1–8.1 尚待實車驗證。使用時無須越獄、轉接盒、Mac、帳號或遠端認證伺服器。

有線與無線 CarPlay 的核心功能不需要 ADB。部分儀表板、車輛資料、方向盤或停車影片功能需要相容韌體及已授權的網路 ADB，請依各設定的說明操作。

1. 停車後安裝 APK，開啟 DiPlay 並授予所需權限。
2. 無線模式可選車載熱點、Wi-Fi Direct 或現有 Wi-Fi／同一區域網路；有線模式請使用支援資料傳輸的 USB 連接埠與傳輸線。
3. 在 iPhone 上接受 CarPlay 提示。連線前先關閉其他手機投影應用程式。

完整步驟請見[安裝與連線指南](docs/INSTALL.zh-TW.md)。

## 切換為繁體中文

開啟 **設定 → 語言 → 應用程式語言**，選擇 **繁體中文**，再按 **套用**。也可選擇 **跟隨系統**。

Android 13 以上會與 Android 的個別應用程式語言設定同步；Android 7.1–12 由 DiPlay 儲存語言選擇。繁體中文使用臺灣用語；香港、澳門及明確指定 `Hant` 的語言選擇共用這份翻譯，並未提供獨立的地區版本。DiPlay 的語言選擇控制其自身介面，CarPlay 內的應用程式語言則由 iPhone 及各應用程式管理。

選用的 **DiPlay Home** 啟動器與 **DiPlay map host** 範例也提供繁體中文，依各自的 Android 系統語言顯示，不共用主要 DiPlay 應用程式的語言偏好。第三方小工具與應用程式名稱由提供者決定。

## 主要功能與 0.2.20 更新

- 有線 USB 與無線 CarPlay，使用本機認證。
- 相容韌體上的比亞迪抬頭顯示器（HUD）與儀表板導航提示。
- 解析度、影格率、圖示與文字大小調整；依設定說明，在重新連線後套用。
- 0.2.20 修正音訊時脈偏快或偏慢的車機上音樂每隔一分鐘左右停頓的問題，車機送到聽筒的通話改用揚聲器播放，有線 CarPlay 也能在 USB 音訊驅動程式佔用 iPhone 的車機上，以及點「儲存並重新連線」後順利啟動。
- 「自動開啟熱點」支援更多車機，儀表板上的 CarPlay 地圖支援更多比亞迪韌體（包括豹 5 Ultra）；診斷報告會記錄最近一次當機，以及收到的方向盤與媒體按鍵。
- 可在 **設定 → 關於 → 在手機上備份或還原** 透過 QR 碼備份設定，更新時可選其他安裝程式。
- 本機診斷報告匯出，只有在你選擇分享後才會傳送。
- 實驗性選項預設關閉，請停車後依說明測試。

[英文版本說明](docs/RELEASE-NOTES-0.2.20.md)與[驗證紀錄](docs/VALIDATION.md)記錄完整變更、已知限制及尚待實車測試的項目。自動化測試通過不代表所有車型、iOS、Siri 或通話情境都已驗證。

## 繁體中文文件

| 文件 | 內容 |
| --- | --- |
| [安裝與連線](docs/INSTALL.zh-TW.md) | 首次使用、權限、USB、熱點及 Wi-Fi 模式 |
| [設定與疑難排解](docs/SETTINGS.zh-TW.md) | 設定分類、重新連線、畫面與聲音、診斷回報 |
| [隱私權與診斷](docs/PRIVACY.zh-TW.md) | 資料儲存、權限、報告分享及更新檢查 |
| [從原始碼建置](docs/BUILD.zh-TW.md) | Windows／Android Studio、建置環境、APK 路徑及認證素材 |
| [翻譯維護](docs/LOCALIZATION.zh-TW.md) | 臺灣用語、資源檢查與文件同步方式 |
| [本次繁體中文驗證紀錄](docs/VALIDATION.zh-TW.md) | 測試、建置結果及完整 CI 的未解失敗 |

進階車型整合可參考[比亞迪導航](docs/BYD_NAVIGATION.md)、[Wi-Fi Direct 舊版限制](docs/ANDROID9_WIFI_DIRECT.md)及[現有 Wi-Fi 指南](docs/EXISTING_WIFI.md)；這些深入文件目前為英文。

## 相容性與公開預覽版限制

這是**未經 Apple 認證的公開預覽版**。公開 APK 包含從公開 Carlinkit 韌體取得的實驗性配件身分，並非為 DiPlay 新核發的 MFi 身分；內含私密金鑰可被擷取。未來 iOS 是否接受、各車機的可靠性及該身分是否適合公開散布，仍有未解問題。

Wi-Fi Direct 在 Android 7.1–9 使用依賴韌體的舊版路徑，無法驗證實際協商頻率；Android 10 以上可驗證。車機支援連上 5 GHz Wi-Fi，不代表能建立 5 GHz Wi-Fi Direct 群組。遇到卡頓可先使用車載熱點、30 fps、預設圖示大小及較低解析度，保留 HEVC 關閉。

更新時使用相同套件與相符簽章才能保留設定及配對資料；請勿只憑 APK 檔名判斷更新相容性。安裝限制取決於車機韌體。

## 回報問題

重現問題後，開啟 **設定 → 診斷 → 儲存診斷報告**，記錄故障時間並檢查 TXT 內容，再附到[相關 CN 問題](https://github.com/serein-morii/DiPlay-CN/issues)或[新增問題](https://github.com/serein-morii/DiPlay-CN/issues/new)。請提供車型／車機、Android／DiLink／完整韌體版本、iPhone／iOS、連線方式、相關設定與重現步驟。不要公開熱點密碼或認證檔案。詳見[診斷回報步驟](docs/SETTINGS.zh-TW.md#匯出診斷報告與回報問題)。

## 原始碼與授權

專案基於 [xcertplay](https://github.com/shilapi/xcertplay)（GPL-3.0）。主畫面、設定介面及網站改編自 [DiAuto](https://github.com/shihabal3amri/DiAuto)（AGPL-3.0）；相關授權位於 `docs/licenses`。散布修改版本時請保留[第三方聲明與授權](docs/THIRD_PARTY_NOTICES.md)。CarPlay 與其圖示屬於 Apple Inc.；專案不代表 Apple 或比亞迪的認可。

一般原始碼／CI 建置預設不包含執行時認證素材；獨立連線測試需要外部素材。Git 與公開原始碼壓縮檔排除認證私密金鑰、Android 簽章金鑰及本機設定；將認證素材打包進 APK 後，接收者仍可擷取。詳見[建置指南](docs/BUILD.zh-TW.md)。
