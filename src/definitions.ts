import type { AccountInfo } from '@azure/msal-browser';
import type { PluginListenerHandle } from '@capacitor/core';

interface AuthenticationResult {
  accessToken: string;
  account: AccountInfo;
  tenantId: string;
  idToken: string;
  scopes: string[];
  authority: string;
  expiresOn: Date | string | null;
  uniqueId?: string;
  idTokenClaims?: object;
  fromCache?: boolean;
  extExpiresOn?: Date;
  refreshOn?: Date;
  tokenType?: string;
  correlationId?: string;
  requestId?: string;
  state?: string;
  familyId?: string;
  cloudGraphHostName?: string;
  msGraphHost?: string;
  code?: string;
  fromNativeBroker?: boolean;
}

export interface BaseOptions {
  clientId: string;
  tenantId?: string;
  domainHint?: string;
  loginHint?: string;
  authorityType?: 'AAD' | 'B2C';
  authorityUrl?: string;
  knownAuthorities?: string[];
  keyHash?: string;
  brokerRedirectUriRegistered?: boolean;
  scopes?: string[];
  redirectUri?: string;
}
export interface LoginOptions extends BaseOptions {
  scopes?: string[];
}
export declare type LogoutOptions = BaseOptions;

/**
 * Describes whether the device is operating in Microsoft Entra shared-device mode.
 *
 * Shared-device mode is only supported on iOS and Android. On the web,
 * `isSharedDevice` is always `false` and `mode` is always `'personal'`.
 */
export interface DeviceInfo {
  /** `true` when the device has been configured for shared-device mode by an administrator. */
  isSharedDevice: boolean;
  /** `'shared'` when running in shared-device mode, otherwise `'personal'`. */
  mode: 'shared' | 'personal';
}

export interface MsalPluginPlugin {
  initializePcaInstance(options: BaseOptions): Promise<void>;
  login(account?: { identifier?: string }): Promise<AuthenticationResult>;
  logout(): Promise<void>;
  getAccounts(): Promise<{
    accounts: AccountInfo[];
  }>;
  /**
   * Returns the shared-device-mode state of the current device.
   *
   * Must be called after {@link MsalPluginPlugin.initializePcaInstance}.
   */
  getDeviceInfo(): Promise<DeviceInfo>;
  addListener(eventName: 'accountChanged', listenerFunc: () => void): Promise<PluginListenerHandle>;
}
