import type { AccountInfo, AuthenticationResult, IPublicClientApplication } from '@azure/msal-browser';
import { PublicClientApplication } from '@azure/msal-browser';
import { WebPlugin } from '@capacitor/core';

import type { BaseOptions, DeviceInfo, MsalPluginPlugin } from './definitions';

let instance: IPublicClientApplication | undefined;
export class MsalPluginWeb extends WebPlugin implements MsalPluginPlugin {
  private baseConfig: BaseOptions | undefined;

  public async initializePcaInstance(options: BaseOptions): Promise<void> {
    if (instance) return;

    instance = new PublicClientApplication({
      auth: {
        clientId: options.clientId,
        authority: options.authorityUrl || `https://login.microsoftonline.com/${options.tenantId || 'common'}/`,
        ...(options.knownAuthorities ? { knownAuthorities: options.knownAuthorities } : {}),
        redirectUri: options.redirectUri || window.location.origin, // Points to window.location.origin. You must register this URI on Microsoft Entra admin center/App Registration.
      },
    });

    this.baseConfig = options;

    await instance.initialize();
  }

  public async login(accountData?: { identifier?: string }): Promise<AuthenticationResult> {
    if (!instance) {
      throw new Error('PublicClientApplication not initialized');
    }

    if (!this.baseConfig) {
      throw new Error('BaseOptions not initialized');
    }

    try {
      if (accountData?.identifier) {
        const identifier = accountData.identifier;
        const account =
          instance.getAccount({ homeAccountId: identifier }) ||
          instance.getAccount({ localAccountId: identifier }) ||
          instance.getAccount({ username: identifier });

        if (account) {
          return await this.acquireTokenSilently(account).catch(async () => {
            return await this.acquireTokenInteractively();
          });
        }
      }

      return await this.acquireTokenInteractively();
    } catch (error) {
      console.error('Error logging in', error);
      throw error;
    }
  }

  public async logout(): Promise<void> {
    if (!instance) {
      throw new Error('PublicClientApplication not initialized');
    }

    await instance.logoutPopup();
    this.notifyListeners('accountChanged', {});
  }

  public async getAccounts(): Promise<{
    accounts: AccountInfo[];
  }> {
    if (!instance) {
      throw new Error('PublicClientApplication not initialized');
    }

    return { accounts: instance.getAllAccounts() };
  }

  // Shared-device mode is not supported on the web. Always report a personal device.
  public async getDeviceInfo(): Promise<DeviceInfo> {
    return { isSharedDevice: false, mode: 'personal' };
  }

  private async acquireTokenInteractively(): Promise<AuthenticationResult> {
    if (!instance) {
      throw new Error('PublicClientApplication not initialized');
    }

    if (!this.baseConfig) {
      throw new Error('BaseOptions not initialized');
    }

    const extraQueryParameters: Record<string, string> = {
      ...(this.baseConfig?.domainHint ? { domain_hint: this.baseConfig.domainHint } : {}),
      ...(this.baseConfig?.loginHint ? { login_hint: this.baseConfig.loginHint } : {}),
    };
    const result = await instance.loginPopup({
      scopes: this.baseConfig?.scopes ?? [],
      ...(Object.keys(extraQueryParameters).length ? { extraQueryParameters } : {}),
      prompt: 'select_account',
    });
    this.notifyListeners('accountChanged', {});
    return result;
  }

  private async acquireTokenSilently(account: AccountInfo): Promise<AuthenticationResult> {
    if (!instance) {
      throw new Error('PublicClientApplication not initialized');
    }

    if (!this.baseConfig) {
      throw new Error('BaseOptions not initialized');
    }

    const extraQueryParameters: Record<string, string> = {
      ...(this.baseConfig?.domainHint ? { domain_hint: this.baseConfig.domainHint } : {}),
      ...(this.baseConfig?.loginHint ? { login_hint: this.baseConfig.loginHint } : {}),
    };
    return await instance.acquireTokenSilent({
      scopes: this.baseConfig?.scopes ?? [],
      ...(Object.keys(extraQueryParameters).length ? { extraQueryParameters } : {}),
      account,
    });
  }
}
