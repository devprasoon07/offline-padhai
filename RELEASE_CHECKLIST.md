# Offline PadhAI — Release Checklist (GitHub Releases)

## Ek baar karna hai (setup)

### 1. Release keystore banao
```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair `
  -alias offline-padhai `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -keystore C:\Users\devpr\offline-padhai.keystore
```
- Passwords yaad rakho (password manager me daalo)
- **Ye file ka backup lo** — kho gayi to purane users update nahi kar payenge!

### 2. keystore.properties banao
Project root me `keystore.properties` file banao (ye git me nahi jayegi):
```
storeFile=C:\\Users\\devpr\\offline-padhai.keystore
storePassword=TUMHARA_PASSWORD
keyAlias=offline-padhai
keyPassword=TUMHARA_PASSWORD
```

## Har release pe karna hai

### 3. Version bump karo
`app/build.gradle.kts` me:
- `versionCode` +1 karo (1, 2, 3...)
- `versionName` saaf rakho: `"1.0"`, `"1.1"` (commit hash hata do)

### 4. Release APK banao
Android Studio: **Build → Generate Signed Bundle/APK → APK → release**
Ya terminal:
```powershell
.\gradlew assembleRelease
```
APK milega: `app\build\outputs\apk\release\app-release.apk`

### 5. GitHub pe Release banao
1. github.com/devprasoon07/offline-padhai → **Releases → Draft a new release**
2. Tag: `v1.0` (versionName se match karo)
3. Title: `Offline PadhAI v1.0`
4. APK attach karo (`app-release.apk` ko rename karke `offline-padhai-v1.0.apk`)
5. Release notes likho (neeche template)
6. **Publish release**

### Release notes template
```markdown
## Offline PadhAI v1.0 🎉

100% offline AI tutor — no internet, no data collection.

### Features
- 📸 Photo → crop → OCR → step-by-step explanation
- ⌨️ Direct typed questions
- 📝 Quiz mode with scores
- 🌍 12 answer languages
- 📚 History, bookmarks, share

### Install
1. `offline-padhai-v1.0.apk` download karo
2. "Install from unknown sources" allow karo
3. **Note:** Pehli baar app kholne pe AI model (~1.5GB) download hoga — WiFi pe karna!

### Requirements
- Android 8.0+ (API 26)
- ~2GB free space (model ke liye)
```

## F-Droid (baad me)
- ML Kit proprietary hai — F-Droid ke liye Tesseract pe switch karna padega
- Pehle GitHub Releases pe traction lao, phir F-Droid
