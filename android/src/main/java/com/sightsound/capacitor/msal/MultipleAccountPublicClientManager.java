package com.sightsound.capacitor.msal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import com.microsoft.identity.client.IMultipleAccountPublicClientApplication;
import com.microsoft.identity.client.IPublicClientApplication;
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

public class MultipleAccountPublicClientManager implements IPublicClientManager {

    private IMultipleAccountPublicClientApplication instance;
    private final Context context;
    private final AppCompatActivity activity;
    private List<String> scopes;

    private MsalPlugin plugin;
    private BroadcastReceiver mAccountChangedReceiver;

    public MultipleAccountPublicClientManager(MsalPlugin plugin) {
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
        configFile.put("account_mode", "MULTIPLE");
        configFile.put("authorities", new JSONArray().put(authorityConfig));

        File config = writeJSONObjectConfig(configFile);
        this.instance = PublicClientApplication.createMultipleAccountPublicClientApplication(this.context, config);
        this.scopes = scopes;
        this.registerAccountChangeBroadcastReceiver();

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
            this.instance.getAccounts(
                new IPublicClientApplication.LoadAccountsCallback() {
                    @Override
                    public void onTaskCompleted(List<IAccount> result) {
                        JSArray accountsArray = new JSArray();
                        for (IAccount account : result) {
                            accountsArray.put(getJSObjectAccount(account));
                        }

                        JSObject response = new JSObject();
                        response.put("accounts", accountsArray);

                        call.resolve(response);
                    }

                    @Override
                    public void onError(MsalException exception) {
                        Logger.error("Error occurred during getAccounts", exception);
                        call.reject("Error occurred during getAccounts");
                    }
                }
            );
        } catch (Exception e) {
            Logger.error("Error occurred during getAccounts", e);
            call.reject("Error occurred during getAccounts");
        }
    }

    @Override
    public void logout(PluginCall call) {
        this.instance.getAccounts(
            new IPublicClientApplication.LoadAccountsCallback() {
                @Override
                public void onTaskCompleted(List<IAccount> result) {
                    for (IAccount account : result) {
                        try {
                            instance.removeAccount(account);
                        } catch (MsalException | InterruptedException e) {
                            call.reject("Error when logging out");
                        }
                    }
                    call.resolve(null);
                }

                @Override
                public void onError(MsalException exception) {
                    Logger.error("Error occurred during logOut", exception);
                    call.reject("Error occurred during logOut");
                }
            }
        );
    }

    @Override
    public boolean isSharedDevice() {
        return false;
    }

    @Override
    public void cleanup() {
        if (mAccountChangedReceiver != null) {
            try {
                this.context.unregisterReceiver(mAccountChangedReceiver);
            } catch (IllegalArgumentException e) {
                // Receiver was already unregistered — ignore.
            }
            mAccountChangedReceiver = null;
        }
    }

    private File writeJSONObjectConfig(JSONObject data) throws IOException {
        File config = new File(this.context.getFilesDir() + "auth_multiple_config.json");

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
        AcquireTokenSilentParameters.Builder builder = new AcquireTokenSilentParameters.Builder()
            .withScopes(this.scopes)
            .fromAuthority(this.instance.getConfiguration().getDefaultAuthority().getAuthorityURL().toString());

        builder = builder.forAccount(this.instance.getAccount(identifier));

        AcquireTokenSilentParameters parameters = builder.build();
        IAuthenticationResult silentAuthResult = this.instance.acquireTokenSilent(parameters);

        callback.tokenReceived(silentAuthResult);
    }

    private void acquireTokenInteractively(final TokenResultCallback callback) {
        AcquireTokenParameters.Builder params = new AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(this.activity)
            .withScopes(scopes)
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

    private void registerAccountChangeBroadcastReceiver() {
        if (mAccountChangedReceiver != null) {
            // Guard against double-registration
            return;
        }

        mAccountChangedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                plugin.notifyAccountChangedListener();
                Logger.info(MsalPlugin.TAG, "Received broadcast");
            }
        };

        // Only the protected system action is used here (personal/multiple-account mode),
        // which is exempt from the API 33+ exported-flag requirement, so the two-argument
        // overload is safe. The receiver is stored so it can be unregistered in cleanup().
        this.context.registerReceiver(mAccountChangedReceiver, new IntentFilter("android.accounts.LOGIN_ACCOUNTS_CHANGED"));
    }
}
