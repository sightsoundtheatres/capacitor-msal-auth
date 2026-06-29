package com.sightsound.capacitor.msal;

import com.microsoft.identity.client.IAuthenticationResult;

public interface TokenResultCallback {
    void tokenReceived(IAuthenticationResult tokenResult);
}
