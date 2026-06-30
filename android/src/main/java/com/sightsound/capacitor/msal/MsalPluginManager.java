package com.sightsound.capacitor.msal;

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
        if (this.publicClientManager != null) {
            return;
        }

        SingleAccountPublicClientManager singleAccountManager = new SingleAccountPublicClientManager(this.plugin);
        singleAccountManager.initializeInstance(
            clientId,
            domainHint,
            loginHint,
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
            MultipleAccountPublicClientManager multipleAccountManager = new MultipleAccountPublicClientManager(this.plugin);
            multipleAccountManager.initializeInstance(
                clientId,
                domainHint,
                loginHint,
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

    public void login(String identifier, PluginCall call) throws MsalException, InterruptedException {
        this.publicClientManager.login(identifier, call);
    }

    public void getAccounts(PluginCall call) {
        this.publicClientManager.getAccounts(call);
    }

    public void logout(PluginCall call) {
        this.publicClientManager.logout(call);
    }
}
