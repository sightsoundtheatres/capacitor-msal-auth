# Onboarding `@sightsoundtheatres/capacitor-msal-auth` into an app

This guide is for **consuming apps** that want to add Microsoft (MSAL) authentication via
this plugin. It is the source of truth for the setup flow; the bundled Claude Code skill
(`.claude/skills/onboard-capacitor-msal-auth`) automates these same steps.

Work top to bottom. Steps marked **(iOS)** / **(Android)** / **(Web)** only apply to those
platforms.

## Values you'll need

Collect these before starting — most steps just plug them in:

| Value | Where it comes from |
| ----- | ------------------- |
| `clientId` | Azure app registration → Overview → Application (client) ID |
| `tenantId` | Azure tenant ID, or `common` / `organizations` / `consumers` (defaults to `common`) |
| iOS bundle identifier | Xcode → target → General → Bundle Identifier (e.g. `com.example.app`) |
| Android package name | `android/app/src/main/AndroidManifest.xml` `package` attribute |
| Android key hash | Generated in the Azure portal's Android platform config (Signature step) |
| `scopes` | The API scopes your app requests (e.g. `User.Read`) |

## 1. Install from GitHub Packages

This package is published to **GitHub Packages**, which requires auth to install even though
the repo is public.

Create an `.npmrc` next to the app's `package.json`:

```
@sightsoundtheatres:registry=https://npm.pkg.github.com
//npm.pkg.github.com/:_authToken=${GITHUB_TOKEN}
```

`GITHUB_TOKEN` must be a [personal access token](https://github.com/settings/tokens) with the
`read:packages` scope. Export it in your shell (or inline the token value).

Then:

```sh
npm i @sightsoundtheatres/capacitor-msal-auth
npx cap sync
```

`npx cap sync` wires the Swift package into iOS (SPM) and the Gradle module into Android — no
manual Xcode/SPM dependency setup needed.

## 2. Register the app in Azure / Entra

1. Create an app registration:
   https://learn.microsoft.com/en-us/entra/identity-platform/scenario-spa-app-registration
2. In the registration → **Authentication** → **Add platform**:
   - Add **iOS/macOS**, supplying the iOS **bundle identifier**.
   - Add **Android**, supplying the Android **package name**. In the **Signature** section,
     generate a **key hash** for your signing key — save it; you need it for Android config
     and at runtime (`keyHash`).

## 3. (Android) Native wiring

**a.** In `AndroidManifest.xml`, inside `<application>`, add the redirect activity. Replace
`<package name>` and `<key hash>` (the hash is prefixed with a slash):

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
              android:path="/<key hash, with prepending slash>" />
    </intent-filter>
</activity>
```

**b.** Add the Microsoft Maven feed to `android/build.gradle`:

```gradle
allprojects {
    repositories {
        maven {
            url 'https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed/maven/v1'
        }
    }
}
```

**c.** Register the plugin in `MainActivity.java`:

```java
import com.getcapacitor.BridgeActivity;
import android.os.Bundle;
import com.sightsound.capacitor.msal.MsalPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MsalPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
```

## 4. (iOS) Native wiring

**a.** Add a keychain group `com.microsoft.adalcache` under the target's
**Signing & Capabilities**.

**b.** Add URL schemes to `Info.plist`:

```xml
<key>CFBundleURLTypes</key>
<array>
    <dict>
        <key>CFBundleURLSchemes</key>
        <array>
            <string>msauth.$(PRODUCT_BUNDLE_IDENTIFIER)</string>
        </array>
    </dict>
</array>
<key>LSApplicationQueriesSchemes</key>
<array>
    <string>msauthv2</string>
    <string>msauthv3</string>
</array>
```

**c.** Add `import MSAL` to the top of `AppDelegate` so the library links.

**d.** If `AppDelegate` already implements the `open url` handler, add the MSAL response check:

```swift
func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
    if MSALPublicClientApplication.handleMSALResponse(
        url, sourceApplication: options[UIApplication.OpenURLOptionsKey.sourceApplication] as? String
    ) == true {
        return true
    }
    return ApplicationDelegateProxy.shared.application(app, open: url, options: options)
}
```

## 5. Initialize and use in app code

```typescript
import { MsalPlugin } from '@sightsoundtheatres/capacitor-msal-auth';

await MsalPlugin.initializePcaInstance({
    clientId: '<client id>',
    tenantId: '<tenant id, defaults to common>',
    scopes: ['<scopes>'],
    keyHash: '<Android only: the key hash from step 2>',
});

// Interactive login
const result = await MsalPlugin.login();
const accessToken = result.accessToken;

// Silent login for an existing account
const { accounts } = await MsalPlugin.getAccounts();
if (accounts.length) {
    await MsalPlugin.login({ identifier: accounts[0].username });
}

await MsalPlugin.logout();
```

See the README API section for the full options and result types.

## 6. (Optional) Shared device mode

[Shared device mode](https://learn.microsoft.com/en-us/entra/identity-platform/shared-device-mode)
(SDM) lets an admin enroll an **iOS or Android** device for frontline workers: sign-in and sign-out
become **device-wide** across all MSAL apps. The plugin auto-detects SDM at
`initializePcaInstance` time — you don't toggle it in code — but the device and Azure registration
must be set up for it. **The web platform does not support SDM.**

**a. Azure registration must use a broker-compatible redirect URI.**

- **Android:** add a redirect URI of the form `msauth://<package name>/<url-encoded key hash>`
  (this is the same redirect the plugin already builds from `keyHash`), and pass
  `brokerRedirectUriRegistered: true` to `initializePcaInstance`.
- **iOS:** add a redirect URI of the form `msauth.<bundle id>://auth` and keep the
  `com.microsoft.adalcache` keychain group from step 4a.

**b. The device needs the Microsoft broker + admin enrollment.**

- Install **Microsoft Authenticator** (Android) or the **Microsoft Enterprise SSO plug-in** /
  Authenticator (iOS) on the device.
- A **Cloud Device Administrator** must enroll the device into shared mode in Microsoft Entra.
  SDM is never detected on a device that hasn't been enrolled by an admin — `getDeviceInfo()` will
  report `mode: 'personal'` until then.

**c. (iOS only) background account-change notifications.** The plugin listens for cross-app
sign-out via a Darwin notification. To receive it while backgrounded, the app must already declare a
legitimate `UIBackgroundModes` entry in `Info.plist`. Do **not** add a background mode solely for
this — Apple may reject the app.

**d. App code.** Branch your UX on the device mode and react to user switches:

```typescript
const { isSharedDevice, mode } = await MsalPlugin.getDeviceInfo();

await MsalPlugin.addListener('accountChanged', async () => {
  // On a shared device the signed-in worker may have changed (or signed out).
  // Clear any cached user data, then re-read the current account.
  const { accounts } = await MsalPlugin.getAccounts();
});
```

On a shared device, `logout()` signs the user out of the whole device, not just your app.

## 7. Verify

- **Web:** `npm run build` (or your app's build) and exercise login in a browser.
- **iOS:** run on a simulator/device; confirm the Microsoft sign-in sheet appears and returns a token.
- **Android:** run on an emulator/device; confirm the redirect activity catches the response (no
  "app not found" / browser-stuck behavior — usually a wrong `host`/`path` in the manifest).

## Troubleshooting

- **Android redirect loops / browser doesn't return:** the `android:host` (package name) or
  `android:path` (key hash, leading slash) in the manifest doesn't match the Azure registration.
- **iOS sign-in sheet never appears:** missing keychain group `com.microsoft.adalcache` or the
  `CFBundleURLSchemes` entry doesn't match `msauth.<bundle id>`.
- **`401`/scope errors at runtime:** `scopes` or `clientId` mismatch with the Azure registration.
