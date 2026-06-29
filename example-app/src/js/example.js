import { MsalPlugin } from '@sightsoundtheatres/capacitor-msal-auth';

const log = (label, data) => {
  const out = document.getElementById('output');
  out.textContent = `${label}: ${JSON.stringify(data, null, 2)}`;
  console.log(label, data);
};

// Fired on all platforms after an interactive login or a logout.
MsalPlugin.addListener('accountChanged', () => {
  log('accountChanged', 'accounts changed');
});

window.testInitialize = async () => {
  await MsalPlugin.initializePcaInstance({
    clientId: '<your-client-id>',
    authorityUrl: 'https://login.microsoftonline.com/<your-tenant-id>/',
    scopes: ['User.Read'],
    keyHash: '<your-android-key-hash>',
  });
  log('initialize', 'done');
};

window.testLogin = async () => {
  const result = await MsalPlugin.login();
  log('login', result);
};

window.testGetAccounts = async () => {
  const accounts = await MsalPlugin.getAccounts();
  log('getAccounts', accounts);
};

window.testLogout = async () => {
  await MsalPlugin.logout();
  log('logout', 'done');
};
