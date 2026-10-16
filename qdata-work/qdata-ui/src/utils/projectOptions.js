import bus from './bus.js';

export const PROJECT_OPTIONS_CHANGED = 'projectOptionsChanged';

// Keep the API response/rejection contract; only business success invalidates the list.
export function notifyProjectOptionsChanged(response) {
  if (response?.code === 200) bus.emit(PROJECT_OPTIONS_CHANGED);
  return response;
}
