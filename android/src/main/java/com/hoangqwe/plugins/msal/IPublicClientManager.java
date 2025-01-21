package com.hoangqwe.plugins.msal;

import com.getcapacitor.PluginCall;
import com.microsoft.identity.client.exception.MsalException;

import org.json.JSONException;

import java.io.IOException;
import java.util.List;

public interface IPublicClientManager {
    void initializeInstance(
            String clientId,
            String domainHint,
            String tenant,
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
