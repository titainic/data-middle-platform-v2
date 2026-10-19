import { joinApiPath } from './apiUrl.js';

/** Resolve local avatar paths identically after upload and when loading user info. */
export function resolveAvatarUrl(rawAvatar, baseApi = '') {
  if (!rawAvatar) return '';
  if (/^https?:\/\//i.test(rawAvatar)) return rawAvatar;
  return joinApiPath(baseApi, rawAvatar);
}
