package com.sightsound.capacitor.msal;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import com.microsoft.identity.client.exception.MsalException;
import java.io.IOException;
import java.util.List;
import org.json.JSONException;

public class MsalPluginManager {

    private IPublicClientManager publicClientManager;
    private List<String> scopes;
    private final MsalPlugin plugin;

    public MsalPluginManager(MsalPlugin plugin) {
        this.plugin = plugin;
    }

    public void initializePcaInstance(
        String clientId,
        String tenantId,
        AuthorityType authorityType,
        String customAuthorityUrl,
        String keyHash,
        Boolean brokerRedirectUriRegistered,
        List<String> scopes
    ) throws MsalException, InterruptedException, IOException, JSONException {
        // Return the instance if it is already initialized
        if (this.publicClientManager != null) {
            return;
        }

        SingleAccountPublicClientManager singleAccountManager = new SingleAccountPublicClientManager(this.plugin);
        singleAccountManager.initializeInstance(
            clientId,
            tenantId,
            authorityType,
            customAuthorityUrl,
            keyHash,
            brokerRedirectUriRegistered,
            scopes
        );

        if (singleAccountManager.isSharedDevice()) {
            this.publicClientManager = singleAccountManager;
        } else {
            // The single-account manager was only needed to detect shared-device mode. It already
            // registered a broadcast receiver and an account-change callback during init, so it must
            // be cleaned up before it is discarded — otherwise its receiver leaks for the app's lifetime.
            singleAccountManager.cleanup();

            MultipleAccountPublicClientManager multipleAccountManager = new MultipleAccountPublicClientManager(this.plugin);
            multipleAccountManager.initializeInstance(
                clientId,
                tenantId,
                authorityType,
                customAuthorityUrl,
                keyHash,
                brokerRedirectUriRegistered,
                scopes
            );
            this.publicClientManager = multipleAccountManager;
        }
    }

    public void login(String identifier, String loginHint, String domainHint, PluginCall call) throws MsalException, InterruptedException {
        this.publicClientManager.login(identifier, loginHint, domainHint, call);
    }

    public void getAccounts(PluginCall call) {
        this.publicClientManager.getAccounts(call);
    }

    public void logout(PluginCall call) {
        this.publicClientManager.logout(call);
    }

    public void getDeviceInfo(PluginCall call) {
        if (this.publicClientManager == null) {
            call.reject("PCA instance not initialized. Call initializePcaInstance first.");
            return;
        }
        boolean shared = this.publicClientManager.isSharedDevice();
        JSObject result = new JSObject();
        result.put("isSharedDevice", shared);
        result.put("mode", shared ? "shared" : "personal");
        call.resolve(result);
    }

    public void cleanup() {
        if (this.publicClientManager != null) {
            this.publicClientManager.cleanup();
        }
    }
}
