package com.hoangqwe.plugins.msal;

import android.util.Log;
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

    public String echo(String value) {
        Log.i("Echo", value);
        return value;
    }

    public void initializePcaInstance(
        String clientId,
        String domainHint,
        String tenant,
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

        SingleAccountPulicClientManager singleAccountManager = new SingleAccountPulicClientManager(this.plugin);
        singleAccountManager.initializeInstance(
            clientId,
            domainHint,
            tenant,
            authorityType,
            customAuthorityUrl,
            keyHash,
            brokerRedirectUriRegistered,
            scopes
        );

        if (singleAccountManager.isSharedDevice()) {
            this.publicClientManager = singleAccountManager;
        } else {
            MultipleAccountPulicClientManager multipleAccountManager = new MultipleAccountPulicClientManager(this.plugin);
            multipleAccountManager.initializeInstance(
                clientId,
                domainHint,
                tenant,
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
