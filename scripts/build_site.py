#!/usr/bin/env python3
"""Generate the static GitHub Pages editions, one per language in content.json; no runtime dependencies.

DiPlay CN edition: every download, link and note points at the CN repository and its
releases. content.json keeps the upstream texts; the CN-specific blocks below are
injected per language with an English fallback.
"""
from pathlib import Path
import json
from html import escape as e
ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / 'site'
data = json.loads((SITE / 'content.json').read_text())
BASE = 'https://serein-morii.github.io/DiPlay-CN/'
REPO = 'https://github.com/serein-morii/DiPlay-CN'
GITEE = 'https://gitee.com/oneeyear/DiPlay-CN'
UPSTREAM = 'https://github.com/shihabal3amri/DiPlay'
VERSION = '0.2.16-cn.2'
RELEASE = REPO + '/releases/latest'
DOWNLOAD = RELEASE
OPTIMIZATIONS = REPO + '/blob/main/docs/CN_OPTIMIZATIONS.md'

# CN blocks per language (missing locales fall back to English).
CN = {
    'en': {
        'testScope': 'Field-tested on a 2025 BYD Han DM-i with DiLink 5.0. Other models and head-unit systems are not guaranteed; test on your own car or adapt the source.',
        'gitee': 'Gitee (China)',
        'cnTitle': 'Added in DiPlay CN',
        'cnFeatures': [
            'Custom dashboard turn card: 1% placement sliders, size and opacity, day/night glass, trip info strip; kept across drops, cleared at the destination.',
            'Small-window navi with Off/On/Auto: Auto follows the wheel Small/Full screen navi mode over ADB, Usage Access as fallback.',
            'In-app updates with four channels: Gitee (default), GitHub and two GitHub mirrors.',
            'Optional delayed pause of the car Bluetooth during CarPlay; boot auto-start ADB repair; open DiPlay when the selected iPhone reconnects.',
            'Simplified Chinese fallback on unsupported head-unit languages; stable signing key so updates overlay-install and keep settings.',
        ],
        'signing': 'DiPlay CN APKs are signed with one stable key since 0.2.10-cn.4: install over the previous CN build to keep settings. About → Check for updates downloads and installs the next CN build in place (first use asks once for the install-unknown-apps permission).',
        'basedOn': 'Based on official DiPlay v0.2.15 · independent community build',
    },
    'zh-Hans': {
        'testScope': '实车验证车辆为 2025 款比亚迪汉 DM-i，车机 DiLink 5.0。其他车型、其他车机系统不保证所有功能可用；请自行测试，或拉取源代码按本车修改。',
        'gitee': 'Gitee 国内下载',
        'cnTitle': 'DiPlay CN 增强',
        'cnFeatures': [
            '自定义仪表转向卡：1% 步进位置滑条、大小与透明度、昼夜玻璃、行程信息条；断线不清卡、到达终点自动清卡。',
            '小屏导航 关／开／自动：自动模式优先用 ADB 读方向盘「小屏/全屏导航」，读不到再用使用情况访问。',
            '应用内更新四通道：Gitee（默认）／GitHub／两个加速镜像。',
            '可选：CarPlay 期间延时暂停车机蓝牙、开机自启 ADB 修复、已选 iPhone 蓝牙重连自动连接。',
            '车机语言不支持时默认简体中文；固定签名密钥，覆盖升级保留设置。',
        ],
        'signing': 'DiPlay CN 自 0.2.10-cn.4 起使用同一把固定签名：直接覆盖安装上一版 CN 即可保留设置。关于 → 检查更新 可直接下载安装下一版（首次使用会请求一次「允许安装未知应用」）。',
        'basedOn': '基于官方 DiPlay v0.2.15 · 独立社区构建',
    },
    'zh-Hant': {
        'testScope': '實車驗證車輛為 2025 款比亞迪漢 DM-i，車機 DiLink 5.0。其他車型、其他車機系統不保證所有功能可用；請自行測試，或拉取原始碼依本車修改。',
        'gitee': 'Gitee 國內下載',
        'cnTitle': 'DiPlay CN 增強',
        'cnFeatures': [
            '自訂儀表轉向卡：1% 步進位置滑條、大小與透明度、晝夜玻璃、行程資訊條；斷線不清卡、到達終點自動清卡。',
            '小螢幕導航 關／開／自動：自動模式優先用 ADB 讀方向盤「小螢幕/全螢幕導航」，讀不到再用使用情況存取。',
            '應用程式內更新四通道：Gitee（預設）／GitHub／兩個加速鏡像。',
            '可選：CarPlay 期間延時暫停車機藍牙、開機自啟 ADB 修復、已選 iPhone 藍牙重連自動連線。',
            '車機語言不支援時預設簡體中文；固定簽章金鑰，覆蓋升級保留設定。',
        ],
        'signing': 'DiPlay CN 自 0.2.10-cn.4 起使用同一把固定簽章：直接覆蓋安裝上一版 CN 即可保留設定。關於 → 檢查更新 可直接下載安裝下一版（首次使用會請求一次「允許安裝未知應用程式」）。',
        'basedOn': '基於官方 DiPlay v0.2.15 · 獨立社群構建',
    },
}
for lang, d in data.items():
    folder = SITE if lang == 'en' else SITE / lang
    folder.mkdir(exist_ok=True)
    prefix = './' if lang == 'en' else '../'
    url = BASE + ('' if lang == 'en' else lang + '/')
    cn = CN.get(lang, CN['en'])
    nav = ''.join(f'<a href="{prefix}{"" if code == "en" else code + "/"}" lang="{code}" hreflang="{code}" dir="auto"'+(' aria-current="page"' if code == lang else '')+f'>{e(v["name"])}</a>' for code,v in data.items())
    alternates = ''.join(f'<link rel="alternate" hreflang="{code}" href="{BASE}{"" if code == "en" else code + "/"}">' for code in data)
    pics = ''.join(f'<figure><a href="{prefix}assets/{pic}.png"><img src="{prefix}assets/{pic}.png" width="1920" height="1080" loading="lazy" alt="{e(cap)}"></a><figcaption>{e(cap)}</figcaption></figure>' for pic,cap in zip(['home','settings'],d['captions']))
    (folder/'index.html').write_text(f'''<!doctype html>
<html lang="{lang}" dir="{d['dir']}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>DiPlay CN · {e(d['download'])}</title><meta name="description" content="{e(d['intro'])}"><meta name="theme-color" content="#0c121c">
<link rel="icon" href="{prefix}assets/icon.png"><link rel="stylesheet" href="{prefix}assets/site.css"><link rel="canonical" href="{url}">{alternates}
<meta property="og:title" content="DiPlay CN — CarPlay for compatible Android head units"><meta property="og:description" content="{e(d['promise'])}"><meta property="og:image" content="{BASE}assets/home.png"><meta property="og:url" content="{url}"><meta property="og:type" content="website">
</head><body><main>
<header><a class="brand" href="{prefix}"><img src="{prefix}assets/icon.png" width="56" height="56" alt=""><span><strong>DiPlay CN</strong><small>{e(cn['basedOn'])}</small></span></a><nav class="languages" aria-label="Language">{nav}</nav></header>
<section class="hero"><span class="badge">{e(d['badge'])} · <bdi>{VERSION}</bdi></span><h1>{e(d['title']).replace(chr(10),'<br>')}</h1><p class="intro">{e(d['intro'])}</p><div class="actions"><a class="button" href="{DOWNLOAD}">{e(d['download'])} <span aria-hidden="true">↓</span></a><a class="button secondary" href="{GITEE}/releases/latest">{e(cn['gitee'])} <span aria-hidden="true">↓</span></a><a class="button secondary" href="#install">{e(d['install'])}</a></div><p class="promise">{e(d['promise'])}</p><p class="note">{e(d['requires'])}</p><p class="note support-scope"><strong>{e(cn['testScope'])}</strong></p></section>
<section class="gallery"><h2>{e(d['gallery'])}</h2><div class="screens">{pics}</div></section>
<div class="grid"><section class="card" id="install"><span class="eyebrow">01</span><h2>{e(d['setup'])}</h2><ol>{''.join('<li>'+e(x)+'</li>' for x in d['steps'])}</ol><p class="note">{e(d['bssid'])}</p><a href="{REPO}/blob/main/docs/INSTALL.md">{e(d['adb'])} ↗</a></section>
<section class="card"><span class="eyebrow">02</span><h2>{e(d['whats'])}</h2><ul>{''.join('<li>'+e(x)+'</li>' for x in d['features'])}</ul><h3>{e(cn['cnTitle'])}</h3><ul>{''.join('<li>'+e(x)+'</li>' for x in cn['cnFeatures'])}</ul><a href="{RELEASE}">{e(d['notes'])} ↗</a> · <a href="{OPTIMIZATIONS}">CN changelog ↗</a><h3>{e(d['compat'])}</h3><p>{e(d['compatText'])}</p></section></div>
<section class="card updates"><div><h2>{e(d['follow'])}</h2><p>{e(d['followText'])}</p></div><a class="button secondary" href="https://t.me/byd_localized">{e(d['telegram'])} ↗</a></section>
<section class="signing"><h2>{e(d['update'])}</h2><p>{e(d['updateText'])}</p><p>{e(cn['signing'])}</p></section>
<section class="card"><h2>{e(d['diagnosticsTitle'])}</h2><p>{e(d['diagnosticsText'])}</p><a href="{REPO}/issues">{e(d['feedback'])} ↗</a> · <a href="{REPO}/issues/new">{e(d['newIssue'])} ↗</a></section>
<footer><nav><a href="{REPO}/blob/main/docs/PRIVACY.md">{e(d["privacy"])}</a><a href="{REPO}">{e(d['source'])}</a><a href="{RELEASE}">{e(d['notes'])}</a><a href="{GITEE}">Gitee</a><a href="{UPSTREAM}">DiPlay (upstream)</a></nav><p>{e(d['footer'])}</p></footer>
</main></body></html>''')
print('Generated', len(data), 'language pages')
