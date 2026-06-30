import Foundation
import Capacitor
import MSAL

@objc public class MsalPlugin: NSObject {
    private var applicationContext: MSALPublicClientApplication?
    private var clientId: String?
    private var authorityType: String?
    private var authorityUrl: URL?
    private var scopes: [String] = []
    private var domainHint: String?
    private var loginHint: String?
    private var redirectUri: String?
    private var bridgeViewController: UIViewController?

    /// Guard flag so we only register the Darwin observer once per instance.
    private var darwinObserverRegistered = false

    /// Invoked when the set of signed-in accounts changes (after an interactive login, a logout,
    /// or a cross-app account change detected via the Darwin notification).
    var onAccountChanged: (() -> Void)?

    // MARK: - Lifecycle

    deinit {
        if darwinObserverRegistered {
            // Remove every observer registered by this instance to avoid use-after-free.
            CFNotificationCenterRemoveEveryObserver(
                CFNotificationCenterGetDarwinNotifyCenter(),
                Unmanaged.passUnretained(self).toOpaque()
            )
        }
    }

    // MARK: - Public plugin entry points

    @objc public func initializePcaInstance(_ call: CAPPluginCall, bridgeViewController: UIViewController) {
        guard let _clientID = call.getString("clientId") else {
            call.reject("Invalid client ID specified.")
            return
        }

        let _tenantId = call.getString("tenantId")
        let _authorityType = call.getString("authorityType") ?? "AAD"

        if _authorityType != "AAD" && _authorityType != "B2C" {
            call.reject("authorityType must be one of 'AAD' or 'B2C'")
            return
        }

        guard let _authorityURL = URL(string: call.getString("authorityUrl") ?? "https://login.microsoftonline.com/\(_tenantId ?? "common")") else {
            call.reject("Invalid authorityUrl or tenant specified")
            return
        }

        let _scopes = call.getArray("scopes", String.self) ?? []

        self.clientId = _clientID
        self.authorityUrl = _authorityURL
        self.scopes = _scopes
        self.authorityType = _authorityType
        self.domainHint = call.getString("domainHint")
        self.loginHint = call.getString("loginHint")
        self.bridgeViewController = bridgeViewController

        do {
            let authority = self.authorityType == "AAD"
                ? try MSALAADAuthority(url: _authorityURL) : try MSALB2CAuthority(url: _authorityURL)

            let msalConfiguration = MSALPublicClientApplicationConfig(clientId: _clientID, redirectUri: nil, authority: authority)
            let knownAuthorityStrings = call.getArray("knownAuthorities", String.self) ?? []
            if knownAuthorityStrings.isEmpty {
                msalConfiguration.knownAuthorities = [authority]
            } else {
                msalConfiguration.knownAuthorities = knownAuthorityStrings.compactMap { urlString in
                    guard let url = URL(string: urlString) else { return nil }
                    return try? (_authorityType == "AAD"
                        ? MSALAADAuthority(url: url) as MSALAuthority
                        : MSALB2CAuthority(url: url) as MSALAuthority)
                }
            }
            self.applicationContext = try MSALPublicClientApplication(configuration: msalConfiguration)

            // Register the Darwin notification listener for cross-app shared-device account changes.
            registerSharedModeAccountChangedListener()

            call.resolve()
            return
        } catch {
            print(error)

            call.reject("Failed to initialize MSAL Public Client Application instance.")
            return
        }
    }

    @objc public func login(_ call: CAPPluginCall) {
        if let identifier = call.getString("identifier") {
            acquireTokenSilently(identifier: identifier, call: call)
        } else {
            acquireTokenInteractively(call: call)
        }
    }

    /// Logs out the current user.
    ///
    /// On a shared device (SDM), this performs a *global* sign-out via
    /// `MSALSignoutParameters`, which clears device-wide tokens so that all
    /// eligible apps see the account as signed out.  On a personal device it
    /// falls back to removing each account locally (original behaviour).
    @objc public func logout(_ call: CAPPluginCall) {
        guard let applicationContext = self.applicationContext else {
            call.reject("PublicClientApplication not initialized")
            return
        }

        guard let bridgeViewController = self.bridgeViewController else {
            call.reject("bridgeViewController not initialized")
            return
        }

        // Determine whether we are on a shared device, then branch accordingly.
        // Do NOT guess on failure: silently treating an error/nil as a personal device would
        // perform a local-only sign-out on a shared device, leaving device-wide tokens active
        // for other apps. Reject instead so the caller can retry.
        let deviceInfoParams = MSALParameters()
        deviceInfoParams.completionBlockQueue = DispatchQueue.main

        applicationContext.getDeviceInformation(with: deviceInfoParams) { [weak self] deviceInformation, error in
            guard let self = self else { return }

            if let error = error {
                call.reject("Failed to determine device mode for logout: \(error.localizedDescription)")
                return
            }

            guard let deviceInformation = deviceInformation else {
                call.reject("Could not determine device mode for logout")
                return
            }

            if deviceInformation.deviceMode == .shared {
                self.globalSignOut(applicationContext: applicationContext,
                                   bridgeViewController: bridgeViewController,
                                   call: call)
            } else {
                self.localSignOut(applicationContext: applicationContext, call: call)
            }
        }
    }

    /// Returns device-mode information.
    ///
    /// Resolves `{ isSharedDevice: Bool, mode: "shared" | "personal" }`.
    @objc public func getDeviceInfo(_ call: CAPPluginCall) {
        guard let applicationContext = self.applicationContext else {
            call.reject("PublicClientApplication not initialized")
            return
        }

        applicationContext.getDeviceInformation(with: nil) { deviceInformation, error in
            if let error = error {
                call.reject("Failed to get device information: \(error.localizedDescription)")
                return
            }

            guard let deviceInfo = deviceInformation else {
                call.reject("No device information returned")
                return
            }

            let isShared = deviceInfo.deviceMode == .shared
            var result = JSObject()
            result["isSharedDevice"] = isShared
            result["mode"] = isShared ? "shared" : "personal"
            call.resolve(result)
        }
    }

    func getAccounts(call: CAPPluginCall) {
        guard let applicationContext = self.applicationContext else {
            call.reject("PublicClientApplication not initialized")
            return
        }

        do {
            let accounts = try applicationContext.allAccounts()
            let accountsArray = accounts.map { accountToJSObject($0) }

            var response = JSObject()
            response["accounts"] = JSArray(accountsArray)
            call.resolve(response)
        } catch {
            call.reject("Failed to get accounts: \(error.localizedDescription)")
        }
    }

    func accountToJSObject(_ msalAccount: MSALAccount) -> JSObject {
        var account = JSObject()

        account["username"] = msalAccount.username
        account["environment"] = msalAccount.environment
        account["idTokenClaims"] = dictionaryToJSObject(msalAccount.accountClaims ?? [:])
        account["identifier"] = msalAccount.identifier
        account["homeAccountId"] = msalAccount.homeAccountId?.identifier
        account["tenantId"] = msalAccount.homeAccountId?.tenantId
        account["isSSOAccount"] = msalAccount.isSSOAccount
        return account
    }

    // MARK: - Darwin notification (Shared Device Mode cross-app account changes)

    /// Registers a Darwin notification observer for `SHARED_MODE_CURRENT_ACCOUNT_CHANGED`.
    ///
    /// The C-level callback cannot capture `self` via a closure, so we pass a raw unretained
    /// pointer to `self` as the `observer` argument and recover it inside the callback using
    /// `Unmanaged<MsalPlugin>.fromOpaque(_:).takeUnretainedValue()`.  The observer is
    /// deregistered in `deinit` via `CFNotificationCenterRemoveEveryObserver` to prevent
    /// use-after-free.
    ///
    /// - Note: **App Store caveat** — Apple may reject an app whose *only* background mode is
    ///   listening for Darwin notifications.  The consuming app must already declare a legitimate
    ///   UIBackgroundModes entry (e.g. `remote-notification`, `fetch`, `processing`).  This is an
    ///   app-level requirement; the plugin itself does not add background modes.
    private func registerSharedModeAccountChangedListener() {
        guard !darwinObserverRegistered else { return }

        let center = CFNotificationCenterGetDarwinNotifyCenter()
        let notificationName = "SHARED_MODE_CURRENT_ACCOUNT_CHANGED" as CFString
        let selfPtr = Unmanaged.passUnretained(self).toOpaque()

        let callback: CFNotificationCallback = { _, observer, _, _, _ in
            guard let observer = observer else { return }
            let plugin = Unmanaged<MsalPlugin>.fromOpaque(observer).takeUnretainedValue()
            // Fire on the main queue so downstream UI work is safe.
            DispatchQueue.main.async {
                plugin.onAccountChanged?()
            }
        }

        CFNotificationCenterAddObserver(
            center,
            selfPtr,
            callback,
            notificationName,
            nil,
            .deliverImmediately
        )

        darwinObserverRegistered = true
    }

    // MARK: - Private helpers

    /// Performs a device-global sign-out via `MSALSignoutParameters`.
    ///
    /// Uses `getCurrentAccount` first so we sign out the correct account even if
    /// multiple accounts exist in the cache.  Falls back to `allAccounts().first`
    /// for cases where the SDM account state is unavailable.
    private func globalSignOut(
        applicationContext: MSALPublicClientApplication,
        bridgeViewController: UIViewController,
        call: CAPPluginCall
    ) {
        let msalParams = MSALParameters()
        msalParams.completionBlockQueue = DispatchQueue.main

        applicationContext.getCurrentAccount(with: msalParams) { [weak self] currentAccount, _, error in
            guard let self = self else { return }

            // Resolve the account to sign out from.
            let accountToSignOut: MSALAccount?
            if let current = currentAccount {
                accountToSignOut = current
            } else {
                accountToSignOut = try? applicationContext.allAccounts().first
            }

            guard let account = accountToSignOut else {
                // No account signed in — nothing to do.
                self.onAccountChanged?()
                call.resolve()
                return
            }

            let webviewParameters = MSALWebviewParameters(authPresentationViewController: bridgeViewController)
            let signoutParameters = MSALSignoutParameters(webviewParameters: webviewParameters)
            // Setting signoutFromBrowser clears the Safari session as well.
            // The SSO plug-in only clears app-level state, not the browser session.
            signoutParameters.signoutFromBrowser = true

            applicationContext.signout(with: account, signoutParameters: signoutParameters) { [weak self] success, error in
                guard let self = self else { return }
                if let error = error {
                    call.reject("Global sign-out failed: \(error.localizedDescription)")
                    return
                }
                self.onAccountChanged?()
                call.resolve()
            }
        }
    }

    /// Performs a local (per-app) sign-out by removing every cached account.
    /// Used on personal (non-shared) devices.
    private func localSignOut(applicationContext: MSALPublicClientApplication, call: CAPPluginCall) {
        do {
            let accounts = try applicationContext.allAccounts()
            for account in accounts {
                try applicationContext.remove(account)
            }
            self.onAccountChanged?()
            call.resolve()
        } catch {
            call.reject("Failed to logout: \(error.localizedDescription)")
        }
    }

    @objc private func acquireTokenSilently(identifier: String, call: CAPPluginCall) {
        guard let applicationContext = self.applicationContext else {
            call.reject("PublicClientApplication not initialized")
            return
        }

        if let account = try? applicationContext.account(forUsername: identifier) {
            acquireTokenSilentlyWithAccount(account: account, call: call)
        } else if let account = try? applicationContext.account(forIdentifier: identifier) {
            acquireTokenSilentlyWithAccount(account: account, call: call)
        } else {
            acquireTokenInteractively(call: call)
        }
    }

    @objc private func acquireTokenSilentlyWithAccount(account: MSALAccount, call: CAPPluginCall) {
        guard let applicationContext = self.applicationContext else {
            call.reject("PublicClientApplication not initialized")
            return
        }

        let parameters = MSALSilentTokenParameters(scopes: self.scopes, account: account)
        applicationContext.acquireTokenSilent(with: parameters) { (result, error) in
            if error != nil {
                self.acquireTokenInteractively(call: call)
                return
            }
            guard let result = result else {
                call.reject("Failed to acquire token silently")
                return
            }
            self.handleAuthenticationResult(result: result, call: call)
        }

    }

    @objc private func acquireTokenInteractively(call: CAPPluginCall) {
        guard let bridgeViewController = self.bridgeViewController else {
            call.reject("bridgeViewController not initialized")
            return
        }

        guard let applicationContext = self.applicationContext else {
            call.reject("PublicClientApplication not initialized")
            return
        }

        let webviewParameters = MSALWebviewParameters(authPresentationViewController: bridgeViewController)
        let parameters = MSALInteractiveTokenParameters(scopes: self.scopes, webviewParameters: webviewParameters)

        parameters.promptType = .selectAccount
        parameters.domainHint = self.domainHint
        parameters.loginHint = self.loginHint

        applicationContext.acquireToken(with: parameters) { (result, error) in
            if error != nil {
                call.reject("Failed to acquire token interactively")
                return
            }
            guard let result = result else {
                call.reject("Failed to acquire token interactively")
                return
            }
            self.onAccountChanged?()
            self.handleAuthenticationResult(result: result, call: call)
        }
    }

    @objc private func handleAuthenticationResult(result: MSALResult, call: CAPPluginCall) {
        var ret = JSObject()
        var account = JSObject()

        account["username"] = result.account.username
        account["environment"] = result.account.environment
        account["idTokenClaims"] = dictionaryToJSObject(result.account.accountClaims ?? [:])
        account["identifier"] = result.account.identifier
        account["idToken"] = result.idToken
        account["homeAccountId"] = result.account.homeAccountId?.identifier
        account["tenantId"] = result.account.homeAccountId?.tenantId
        account["isSSOAccount"] = result.account.isSSOAccount

        ret["accessToken"] = result.accessToken
        ret["authorizationHeader"] = result.authorizationHeader
        ret["authenticationScheme"] = result.authenticationScheme
        ret["expiresOn"] = result.expiresOn?.description
        ret["scopes"] = result.scopes
        ret["idToken"] = result.idToken
        ret["authority"] = result.authority.description
        ret["uniqueId"] = result.tenantProfile.identifier
        ret["tenantId"] = result.account.homeAccountId?.tenantId

        ret["account"] = account

        call.resolve(ret)
    }

    func dictionaryToJSObject(_ dictionary: [String: Any]) -> JSObject {
        var jsObject = JSObject()
        for (key, value) in dictionary {
            jsObject[key] = value as? JSValue
        }
        return jsObject
    }
}
