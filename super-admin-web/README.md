# 1803 CONSULT — Super Admin Web Portal & Payment Notification Backend

This project is pre-wired to the live **1803Receipts** Firebase backend:

- **Project ID:** `gen-lang-client-0777428447`
- **Firestore Database ID:** `ai-studio-android-1803rece-28abc08c-3d25-4b25-a94f-ec5c1f083b93`
- **Authorized Super Admin:** Verified Google account `info.1803consult@gmail.com`
- **Official Payment Account:** `1803 CONSULT` • `3001823009` • `KUDA`

## 1. Deploy Web Portal & Email Notification Cloud Function
From an exported project folder with Firebase CLI (`firebase login`):

```bash
# Set a Gmail App Password for info.1803consult@gmail.com so receipt uploads email automatically
firebase functions:secrets:set GMAIL_APP_PASSWORD --project gen-lang-client-0777428447

# Install function dependencies & deploy Hosting + Cloud Functions + Firestore Rules
npm --prefix functions install
firebase deploy --only hosting,functions,firestore --project gen-lang-client-0777428447
```

## 2. Connect the Web Portal
1. In Firebase Console (`gen-lang-client-0777428447`), add a **Web App** to obtain your Web **API Key** and **App ID**, enable **Google** in Authentication, and add `gen-lang-client-0777428447.web.app` under **Authorized domains**.
2. Open `https://gen-lang-client-0777428447.web.app` (or the in-app **1803 Super Admin** console), sign in as `info.1803consult@gmail.com`, inspect uploaded subscriber KUDA transfer receipts, and click **Verified** to activate any business's subscription immediately.
