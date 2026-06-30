package com.sightsound.capacitor.msal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.PluginCall;
import com.microsoft.identity.client.AcquireTokenParameters;
import com.microsoft.identity.client.AcquireTokenSilentParameters;
import com.microsoft.identity.client.AuthenticationCallback;
import com.microsoft.identity.client.IAccount;
import com.microsoft.identity.client.IAuthenticationResult;
import com.microsoft.identity.client.ISingleAccountPublicClientApplication;
import com.microsoft.identity.client.Prompt;
import com.microsoft.identity.client.PublicClientApplication;
import com.microsoft.identity.client.exception.MsalException;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class SingleAccountPublicClientManager implements IPublicClientManager {

    private ISingleAccountPublicClientApplication instance;
    private final Context context;
    private final MsalPlugin plugin;
    private final AppCompatActivity activity;
    private List<String> scopes;
    private BroadcastReceiver mAccountChangedReceiver;

    public SingleAccountPublicClientManager(MsalPlugin plugin) {
        this.activity = plugin.getActivity();
        this.context = this.activity.getApplicationContext();
        this.plugin = plugin;
    }

    @Override
    public void initializeInstance(
        String clientId,
        String domainHint,
        String loginHint,
        String tenantId,
        AuthorityType authorityType,
        String customAuthorityUrl,
        String keyHash,
        Boolean brokerRedirectUriRegistered,
        List<String> scopes
    ) throws MsalException, InterruptedException, IOException, JSONException {
        // Return the instance if it is already initialized
        if (this.instance != null) {
            return;
        }

        tenantId = tenantId != null ? tenantId : "common";
        String authorityUrl = customAuthorityUrl != null ? customAuthorityUrl : "https://login.microsoftonline.com/" + tenantId;
        String urlEncodedKeyHash = URLEncoder.encode(keyHash, "UTF-8");
        String redirectUri = "msauth://" + this.context.getPackageName() + "/" + urlEncodedKeyHash;

        JSONObject configFile = new JSONObject();
        JSONObject authorityConfig = new JSONObject();

        switch (authorityType) {
            case AAD:
                authorityConfig.put("type", AuthorityType.AAD.name());
                authorityConfig.put("authority_url", authorityUrl);
                authorityConfig.put("audience", new JSONObject().put("type", "AzureADMultipleOrgs").put("tenant_id", tenantId));
                configFile.put("broker_redirect_uri_registered", brokerRedirectUriRegistered);
                break;
            case B2C:
                authorityConfig.put("type", AuthorityType.B2C.name());
                authorityConfig.put("authority_url", authorityUrl);
                authorityConfig.put("default", "true");
                break;
        }

        configFile.put("client_id", clientId);
        configFile.put("domain_hint", domainHint);
        configFile.put("login_hint", loginHint);
        configFile.put("authorization_user_agent", "DEFAULT");
        configFile.put("redirect_uri", redirectUri);
        configFile.put("account_mode", "SINGLE");
        configFile.put("shared_device_mode_supported", true);
        configFile.put("authorities", new JSONArray().put(authorityConfig));

        File config = writeJSONObjectConfig(configFile);
        this.instance = PublicClientApplication.createSingleAccountPublicClientApplication(this.context, config);
        this.scopes = scopes;

        this.registerAccountChangeBroadcastReceiver();
        this.registerCurrentAccountCallback();

        if (!config.delete()) {
            Logger.warn("Warning! Unable to delete config file.");
        }

        Logger.debug("Pca instance is initialized");
    }

    @Override
    public void login(String identifier, PluginCall call) throws MsalException, InterruptedException {
        acquireToken(identifier, (result) -> {
            try {
                JSObject accountInfo = new JSObject();

                accountInfo.put("accessToken", result.getAccessToken());
                accountInfo.put("authorizationHeader", result.getAuthorizationHeader());
                accountInfo.put("authenticationScheme", result.getAuthenticationScheme());
                accountInfo.put("tenantId", result.getTenantId());
                accountInfo.put("expiresOn", result.getExpiresOn().toString());
                accountInfo.put("scopes", new JSONArray(Arrays.asList(result.getScope())));

                IAccount resultAccount = result.getAccount();

                JSObject account = getJSObjectAccount(resultAccount);
                accountInfo.put("account", account);
                accountInfo.put("idToken", resultAccount.getIdToken());
                accountInfo.put("authority", resultAccount.getAuthority());
                Object oid = resultAccount.getClaims() != null ? resultAccount.getClaims().get("oid") : null;
                if (oid instanceof String) accountInfo.put("uniqueId", (String) oid);

                call.resolve(accountInfo);
            } catch (Exception e) {
                Logger.error("Error occurred during login", e);
                call.reject("Error occurred during login");
            }
        });
    }

    @Override
    public void getAccounts(PluginCall call) {
        try {
            IAccount currentAccount = this.instance.getCurrentAccount().getCurrentAccount();
            JSArray accountsArray = new JSArray();
            accountsArray.put(this.getJSObjectAccount(currentAccount));

            JSObject response = new JSObject();
            response.put("accounts", accountsArray);

            call.resolve(response);
        } catch (Exception e) {
            Logger.error("Error occurred during getAccounts", e);
            call.reject("Error occurred during getAccounts");
        }
    }

    @Override
    public void logout(PluginCall call) {
        try {
            if (this.instance.getCurrentAccount() == null) {
                call.reject("Nothing to sign out from");
            } else {
                this.instance.signOut(
                    new ISingleAccountPublicClientApplication.SignOutCallback() {
                        @Override
                        public void onSignOut() {
                            call.resolve();
                        }

                        @Override
                        public void onError(@NonNull MsalException exception) {
                            Logger.error("Error occurred during logout", exception);
                            call.reject("Error occurred during logout");
                        }
                    }
                );
            }
        } catch (Exception e) {
            Logger.error("Error occurred during logout", e);
            call.reject("Error occurred during logout");
        }
    }

    @Override
    public boolean isSharedDevice() {
        return instance != null && instance.isSharedDevice();
    }

    @Override
    public void cleanup() {
        if (mAccountChangedReceiver != null) {
            try {
                this.context.unregisterReceiver(mAccountChangedReceiver);
            } catch (IllegalArgumentException e) {
                Logger.warn("BroadcastReceiver was already unregistered or never registered: " + e.getMessage());
            }
            mAccountChangedReceiver = null;
        }
    }

    private File writeJSONObjectConfig(JSONObject data) throws IOException {
        File config = new File(this.context.getFilesDir() + "auth_single_config.json");

        try (FileWriter writer = new FileWriter(config, false)) {
            writer.write(data.toString());
            writer.flush();
        }

        return config;
    }

    private void acquireToken(String identifier, final TokenResultCallback callback) throws MsalException, InterruptedException {
        if (identifier != null) {
            try {
                acquireTokenSilently(identifier, callback);
            } catch (MsalException | InterruptedException e) {
                acquireTokenInteractively(callback);
            }
        } else {
            acquireTokenInteractively(callback);
        }
    }

    private void acquireTokenSilently(String identifier, final TokenResultCallback callback) throws MsalException, InterruptedException {
        IAccount currentAccount = this.instance.getCurrentAccount().getCurrentAccount();

        if (currentAccount != null) {
            AcquireTokenSilentParameters.Builder builder = new AcquireTokenSilentParameters.Builder()
                .withScopes(this.scopes)
                .fromAuthority(this.instance.getConfiguration().getDefaultAuthority().getAuthorityURL().toString())
                .forAccount(this.instance.getCurrentAccount().getCurrentAccount());

            AcquireTokenSilentParameters parameters = builder.build();
            IAuthenticationResult silentAuthResult = this.instance.acquireTokenSilent(parameters);

            callback.tokenReceived(silentAuthResult);
        } else {
            throw new InterruptedException("No account found");
        }
    }

    private void acquireTokenInteractively(final TokenResultCallback callback) {
        AcquireTokenParameters.Builder params = new AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(this.activity)
            .withScopes(this.scopes)
            .withPrompt(Prompt.SELECT_ACCOUNT)
            .withCallback(
                new AuthenticationCallback() {
                    @Override
                    public void onCancel() {
                        Logger.info("Login cancelled");
                        callback.tokenReceived(null);
                    }

                    public void onSuccess(IAuthenticationResult authenticationResult) {
                        Logger.info(authenticationResult.getAccessToken());
                        callback.tokenReceived(authenticationResult);
                    }

                    @Override
                    public void onError(MsalException ex) {
                        Logger.error("Unable to acquire token interactively", ex);
                        callback.tokenReceived(null);
                    }
                }
            );

        this.instance.acquireToken(params.build());
    }

    private JSObject getJSObjectAccount(IAccount account) {
        JSObject result = new JSObject();

        result.put("authority", account.getAuthority());
        result.put("homeAccountId", account.getId());
        result.put("identifier", account.getId());
        result.put("idTokenClaims", new JSONObject(account.getClaims()));
        result.put("tenantId", account.getTenantId());
        result.put("username", account.getUsername());
        result.put("idToken", account.getIdToken());

        return result;
    }

    private static final String MSAL_ACCOUNT_CHANGED_ACTION =
        "com.microsoft.identity.client.sharedmode.CURRENT_ACCOUNT_CHANGED";

    private void registerAccountChangeBroadcastReceiver() {
        if (mAccountChangedReceiver != null) {
            // Guard against double-registration
            return;
        }

        mAccountChangedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                plugin.notifyAccountChangedListener();
                Logger.info(MsalPlugin.TAG, "Received account-change broadcast: " + intent.getAction());
            }
        };

        // Register for both the MSAL broker account-changed action and the system
        // LOGIN_ACCOUNTS_CHANGED action so cross-app sign-out and broker sign-out are
        // both caught. The broker action is broadcast by the Microsoft Authenticator
        // app (a separate UID), so the receiver must be EXPORTED for the OS to deliver
        // it — RECEIVER_NOT_EXPORTED would silently drop the cross-app broadcast and
        // defeat shared-device account-change notifications. minSdk is 33, so the flag
        // is mandatory.
        IntentFilter filter = new IntentFilter();
        filter.addAction(MSAL_ACCOUNT_CHANGED_ACTION);
        filter.addAction("android.accounts.LOGIN_ACCOUNTS_CHANGED");

        this.context.registerReceiver(mAccountChangedReceiver, filter, Context.RECEIVER_EXPORTED);
    }

    private void registerCurrentAccountCallback() {
        this.instance.getCurrentAccountAsync(
            new ISingleAccountPublicClientApplication.CurrentAccountCallback() {
                @Override
                public void onAccountLoaded(@androidx.annotation.Nullable IAccount activeAccount) {
                    // Initial load — nothing to notify yet
                }

                @Override
                public void onAccountChanged(
                    @androidx.annotation.Nullable IAccount priorAccount,
                    @androidx.annotation.Nullable IAccount currentAccount
                ) {
                    plugin.notifyAccountChangedListener();
                    Logger.info(MsalPlugin.TAG, "MSAL account changed via CurrentAccountCallback");
                }

                @Override
                public void onError(@NonNull MsalException exception) {
                    Logger.error(MsalPlugin.TAG, "Error in getCurrentAccountAsync", exception);
                }
            }
        );
    }
}
