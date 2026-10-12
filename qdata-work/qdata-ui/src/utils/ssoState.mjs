const PENDING_KEY = 'qdata:sso:pending';
const MAX_AGE_MS = 10 * 60 * 1000;

function internalPath(path) {
  return typeof path === 'string' && path.startsWith('/') && !path.startsWith('//')
    && !path.startsWith('/\\') && !path.includes('\0');
}

function removeLegacyCodeCache(storage) {
  const oldRepeatCache = storage.getItem('sessionObj');
  if (typeof oldRepeatCache === 'string' && oldRepeatCache.includes('/oauth2/codeLogin?code=')) {
    storage.removeItem('sessionObj');
  }
}

export function beginSsoLogin(storage, returnPath, cryptoProvider = globalThis.crypto, now = Date.now()) {
  if (!internalPath(returnPath)) throw new Error('Invalid SSO return path');
  removeLegacyCodeCache(storage);
  const bytes = new Uint8Array(32);
  cryptoProvider.getRandomValues(bytes);
  const state = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('');
  storage.setItem(PENDING_KEY, JSON.stringify({ state, returnPath, createdAt: now }));
  return state;
}

export function buildSsoAuthorizeUrl(serverUrl, clientId, appUrl, state) {
  const params = new URLSearchParams({
    response_type: 'code',
    client_id: clientId,
    redirect_uri: `${appUrl.replace(/\/+$/, '')}/sso`,
    state
  });
  return `${serverUrl.replace(/\/+$/, '')}/oauth2/authorize?${params}`;
}

export function acceptSsoCallback(storage, query, authenticated, now = Date.now()) {
  removeLegacyCodeCache(storage);
  const pending = storage.getItem(PENDING_KEY);
  storage.removeItem(PENDING_KEY);
  if (authenticated || !pending || typeof query?.state !== 'string') return null;
  let saved;
  try {
    saved = JSON.parse(pending);
  } catch {
    return null;
  }
  if (saved.state !== query.state || !internalPath(saved.returnPath)
    || !Number.isFinite(saved.createdAt) || saved.createdAt > now
    || now - saved.createdAt > MAX_AGE_MS
    || query.error || typeof query.code !== 'string' || !query.code) return null;
  return { code: query.code, returnPath: saved.returnPath };
}
