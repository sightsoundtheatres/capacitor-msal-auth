package com.sightsound.capacitor.msal;

import com.getcapacitor.PluginCall;
import com.microsoft.identity.client.exception.MsalException;
import java.io.IOException;
import java.util.List;
import org.json.JSONException;

public interface IPublicClientManager {
    void initializeInstance(
        String clientId,
        String domainHint,
        String loginHint,
        String tenantId,
        AuthorityType authorityType,
        String customAuthorityUrl,
        String keyHash,
        Boolean brokerRedirectUriRegistered,
        List<String> scopes
    ) throws MsalException, InterruptedException, IOException, JSONException;

    void login(String identifier, PluginCall call) throws MsalException, InterruptedException;

    void getAccounts(PluginCall call);

    void logout(PluginCall call);
}
