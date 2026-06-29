---
name: onboard-capacitor-msal-auth
description: Install and configure the @sightsoundtheatres/capacitor-msal-auth plugin (Microsoft MSAL login) in a Capacitor app. Use when a user wants to add Microsoft/Azure AD/Entra authentication, "set up MSAL", or onboard this plugin into an iOS/Android/web Capacitor project.
---

# Onboard capacitor-msal-auth into a Capacitor app

Drive the full setup of `@sightsoundtheatres/capacitor-msal-auth` in the **current app**.
Do the work; only stop to ask the user for values that cannot be discovered from the repo or
the Azure portal.

## 0. Detect platforms first

**Before gathering anything or editing anything, determine which native platforms the app has.**
Check for the `ios/` and `android/` directories at the project root (an `ios/App` or
`android/app` subdirectory confirms it). The result gates the rest of this skill:

- If `android/` is absent: skip **all** Android steps and **do not** ask the user for the Android
  package name or key hash.
- If `ios/` is absent: skip **all** iOS steps and **do not** ask the user for the iOS bundle
  identifier.
- A web-only Capacitor app may have neither — in that case do steps 1, 2 (web/SPA only), and 5
  only.

State which platforms you detected before proceeding, so the user can correct you if a platform
exists but isn't synced yet (`npx cap add ios` / `npx cap add android`).

## 1. Gather required values

Ask the user (or detect) before editing — but **only ask for values for platforms that exist**
per step 0. Don't invent these:

Always needed:
- `clientId` — Azure app registration → Application (client) ID
- `tenant` — Azure tenant ID, or `common` (default if unsure)
- `scopes` — API scopes the app requests (e.g. `User.Read`)

Only if `ios/` exists:
- iOS **bundle identifier** — detect from the Xcode project / `capacitor.config`

Only if `android/` exists:
- Android **package name** — detect from `android/app/src/main/AndroidManifest.xml`
- Android **key hash** — generated in the Azure portal's Android platform config; ask the user

## 2. Install from GitHub Packages

The package lives on GitHub Packages and needs auth even though the repo is public.

1. Create/append an `.npmrc` next to the app's `package.json`:
   ```
   @sightsoundtheatres:registry=https://npm.pkg.github.com
   //npm.pkg.github.com/:_authToken=${GITHUB_TOKEN}
   ```
   Tell the user `GITHUB_TOKEN` must be a PAT with `read:packages` scope, exported in their shell.
2. Run `npm i @sightsoundtheatres/capacitor-msal-auth` then `npx cap sync`.
   `cap sync` wires iOS (SPM) and Android (Gradle) automatically — no manual dependency setup.

## 3. Azure / Entra registration (user action)

Confirm the user has an app registration. They only need to add the platforms the app actually
has (per step 0): the **iOS** platform (bundle id) if `ios/` exists, the **Android** platform
(package name + generated **key hash**) if `android/` exists. Don't tell them to register a
platform they aren't building. If the registration isn't ready, point them to
https://learn.microsoft.com/en-us/entra/identity-platform/scenario-spa-app-registration and
wait for the values. You cannot do this step for them.

## 4. (Android) — only if `android/` exists

- In `AndroidManifest.xml`, inside `<application>`, add the redirect activity. Substitute the
  real package name into `android:host` and the key hash (with a leading slash) into `android:path`:
  ```xml
  <activity
      android:name="com.microsoft.identity.client.BrowserTabActivity"
      android:exported="true">
      <intent-filter>
          <action android:name="android.intent.action.VIEW" />
          <category android:name="android.intent.category.DEFAULT" />
          <category android:name="android.intent.category.BROWSABLE" />
          <data android:scheme="msauth"
                android:host="<package name>"
                android:path="/<key hash>" />
      </intent-filter>
  </activity>
  ```
- Add the Microsoft Maven feed to `android/build.gradle` under `allprojects { repositories { ... } }`:
  ```gradle
  maven { url 'https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed/maven/v1' }
  ```
- In `MainActivity.java`, import `com.sightsound.capacitor.msal.MsalPlugin` and call
  `registerPlugin(MsalPlugin.class);` inside `onCreate` **before** `super.onCreate(...)`.

## 5. (iOS) — only if `ios/` exists

- Add keychain group `com.microsoft.adalcache` under Signing & Capabilities (tell the user to add
  it in Xcode if you can't edit the entitlements directly).
- Add to `Info.plist`:
  ```xml
  <key>CFBundleURLTypes</key>
  <array>
    <dict>
      <key>CFBundleURLSchemes</key>
      <array><string>msauth.$(PRODUCT_BUNDLE_IDENTIFIER)</string></array>
    </dict>
  </array>
  <key>LSApplicationQueriesSchemes</key>
  <array><string>msauthv2</string><string>msauthv3</string></array>
  ```
- Add `import MSAL` to the top of `AppDelegate`.
- If `AppDelegate` implements `application(_:open:options:)`, add an
  `MSALPublicClientApplication.handleMSALResponse(...)` check at the top that returns `true` on match.

## 6. App code

Add an initialization + login flow using the gathered values:

```typescript
import { MsalPlugin } from '@sightsoundtheatres/capacitor-msal-auth';

await MsalPlugin.initializePcaInstance({
  clientId: '<client id>',
  tenant: '<tenant>',
  scopes: ['<scopes>'],
  keyHash: '<Android only>',
});
const result = await MsalPlugin.login();
```

Omit `keyHash` if the app has no Android platform.

Methods available: `initializePcaInstance`, `login(account?)`, `logout`, `getAccounts`, and an
`accountChanged` listener.

## 7. Verify and report

Only verify the platforms that exist:

- Web: build the app and exercise login.
- iOS/Android: tell the user to run on a device/simulator and confirm the sign-in sheet appears
  and returns a token. You generally can't complete an interactive OAuth flow headlessly — be
  explicit that native verification needs a human.

Common failures: Android redirect loop = wrong `host`/`path` vs. registration; iOS sheet missing
= missing keychain group or wrong `CFBundleURLSchemes`; runtime 401 = `clientId`/`scopes` mismatch.

For the full step-by-step with rationale, see `ONBOARDING.md` in the plugin package.
