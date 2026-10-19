import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { ref, reactive } from 'vue';
import { createPinia, setActivePinia, defineStore } from 'pinia';
import { resolveAvatarUrl } from '../src/utils/avatar.mjs';

const path = '/profile/avatar/2026/10/19/new.png';
const bases = ['', '/', '/admin', '/admin/', 'https://x/y', 'https://x/y/'];
const expected = ['/profile', '/profile', '/admin/profile', '/admin/profile',
  'https://x/y/profile', 'https://x/y/profile'];

for (const [index, base] of bases.entries()) {
  test(`avatar URL handles base ${JSON.stringify(base)}`, () => {
    for (const raw of [path, path.slice(1), '/' + path]) {
      assert.equal(resolveAvatarUrl(raw, base), expected[index] + '/avatar/2026/10/19/new.png');
    }
    const url = new URL(resolveAvatarUrl(path, base), 'https://qdata.test');
    assert.equal(url.host, base.startsWith('http') ? 'x' : 'qdata.test');
  });
}

test('remote avatar URLs are preserved and absent avatars use the existing default', () => {
  assert.equal(resolveAvatarUrl('https://cdn.test/a.png', '/admin'), 'https://cdn.test/a.png');
  assert.equal(resolveAvatarUrl('http://cdn.test/a.png', '/'), 'http://cdn.test/a.png');
  for (const raw of ['', null, undefined]) assert.equal(resolveAvatarUrl(raw, '/admin'), '');
});

// Execute the actual component script with Vue reactivity and its actual Pinia store.
// Only API, cropper, i18n and modal collaborators are fixtures; no browser/95 service is used.
function fixture(base, failure = false) {
  setActivePinia(createPinia());
  const storeSource = readFileSync(new URL('../src/store/system/user.js', import.meta.url), 'utf8')
    .replace(/^import .*;?$/gm, '')
    .replaceAll('import.meta.env.VITE_APP_BASE_API', 'baseApi')
    .replace('export default useUserStore;', 'useUserStore;');
  const useUserStore = vm.runInNewContext(storeSource, {
    defineStore, baseApi: base, resolveAvatarUrl, defAva: 'default.png', getToken: () => '',
    getInfo: async () => ({ user: { avatar: path, userId: '42' }, roles: ['fixture'], permissions: [] }),
  });
  const store = useUserStore();
  store.avatar = 'old.png';
  const messages = [];
  let submitted;
  const uploadAvatar = async data => {
    submitted = data;
    if (failure) throw new Error('fixture server rejected upload');
    return { imgUrl: path };
  };
  const source = readFileSync(new URL('../src/views/sys/system/user/profile/userAvatar.vue', import.meta.url), 'utf8')
    .match(/<script setup>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*;?$/gm, '')
    .replaceAll('import.meta.env.VITE_APP_BASE_API', 'baseApi');
  const component = vm.runInNewContext(source + '\n({ options, open, visible, editCropper, modalOpened, closeDialog, uploadImg, beforeUpload });', {
    ref, reactive, baseApi: base, resolveAvatarUrl, uploadAvatar, useUserStore: () => store,
    useDefaultLang: () => ({ td: key => key }),
    getCurrentInstance: () => ({ proxy: {
      $refs: { cropper: { getCropBlob: callback => callback(new Blob(['fixture'], { type: 'image/png' })) } },
      $modal: { msgSuccess: message => messages.push(['success', message]),
        msgError: message => messages.push(['error', message]) },
    } }),
    FormData, Blob,
  });
  return { component, store, messages, submitted: () => submitted };
}

for (const base of ['/', '/admin', 'https://x/y']) {
  test(`successful upload and refreshed getInfo share the same avatar for ${base}`, async () => {
    const { component, store, messages, submitted } = fixture(base);
    component.editCropper(); component.modalOpened();
    assert.equal(component.open.value, true);
    assert.equal(component.visible.value, true);
    component.uploadImg();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(component.options.img, resolveAvatarUrl(path, base));
    assert.equal(store.avatar, component.options.img);
    assert.equal(submitted().get('avatarfile').type, 'image/png');
    assert.equal(component.open.value, false);
    assert.equal(component.visible.value, false);
    assert.equal(messages[0][0], 'success');
    const uploaded = store.avatar;
    await store.getInfo();
    assert.equal(store.avatar, uploaded);
  });
}

test('closing cropper releases visibility and restores saved avatar before reopening', () => {
  const { component } = fixture('/');
  component.editCropper(); component.modalOpened();
  component.options.img = 'data:image/png;base64,unsaved';
  component.open.value = false; // el-dialog v-model update occurs before @close
  component.closeDialog();
  assert.equal(component.visible.value, false);
  assert.equal(component.options.img, 'old.png');
  component.editCropper(); component.modalOpened();
  assert.equal(component.open.value, true);
  assert.equal(component.visible.value, true);
});

test('rejected upload displays feedback, preserves saved avatar and allows retry', async () => {
  const { component, store, messages } = fixture('/', true);
  component.editCropper(); component.modalOpened();
  component.uploadImg();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(store.avatar, 'old.png');
  assert.equal(component.open.value, true);
  assert.equal(component.visible.value, true);
  assert.deepEqual(messages, [['error', 'common.upload.uploadFailedAdmin']]);
});

test('non-image selection shows an explicit error', () => {
  const { component, messages } = fixture('/');
  component.beforeUpload({ type: 'text/plain' });
  assert.deepEqual(messages, [['error', 'sys.system.userAvatar.fileFormatError']]);
});
