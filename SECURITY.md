# CN APK signing limitation / CN 安装包签名限制

The CN Android release signing keystore and its password are committed in this public repository to keep existing `com.shihab.diplay.cn` installations upgradeable. **This key is not secret.** Anyone can use it to sign an APK that Android considers an update to the CN app. Android's matching-signature check therefore does not authenticate the publisher of a CN APK.

当前 CN 包的 Android 发布签名库和密码已公开。这样做保留了 `com.shihab.diplay.cn` 用户的覆盖升级能力，但**签名不能证明 APK 来自本项目**：第三方也能签出被 Android 视为同一应用更新的安装包。

- Download only from the release pages linked in this repository. Do not install APKs forwarded in chats or hosted by unknown mirrors. A checksum from the same untrusted source does not establish publisher identity.
- 只使用本仓库列出的 GitHub/Gitee 发布页；不要安装聊天转发或未知镜像提供的 APK。同一不可信来源附带的校验和不能证明发布者身份。
- The two optional acceleration channels in the app use third-party mirrors for release metadata, APKs and checksums. They are convenience choices, not trusted publishers; prefer direct GitHub or Gitee when possible.
- 应用内两个可选加速通道会通过第三方镜像获取版本信息、APK 和校验和。它们仅是便利选项，不是可信发布方；条件允许时优先使用 GitHub 直连或 Gitee。
- The app remains on its current package name and key for update compatibility. Deleting the exposed file from a later commit would not revoke copies already made. A secure future signing identity requires a separately protected key and a migration plan, normally a new package name and a fresh install; it cannot be presented as an in-place update of the existing CN package.
- 目前为兼容旧用户，暂不更换包名及签名。今后即使从 Git 删除旧密钥，也无法撤销已被复制的密钥。真正更换安全签名需要受保护的新私钥和迁移方案，通常意味着新包名及重新安装，不能伪装成原包的覆盖升级。

For a security report, open a private GitHub security advisory for this repository rather than posting an exploit or private user data in a public issue.

## Accessory identity and source packaging

The release APK also contains the experimental accessory certificate and matching key described in `docs/THIRD_PARTY_NOTICES.md`. Anyone with the APK can extract them. Local compilation, Git history removal and obfuscation do not make a bundled shared key confidential or revoke previous copies. The public Git tree excludes these accessory-identity files; source builds do not import them automatically, and release packaging selects them explicitly. The Android signing keystore is separate from this accessory identity and is not bundled inside the APK, but it **is present in this public Git repository** as described above.

Review diagnostic reports before posting. Never include credentials or pairing records in public issues.
