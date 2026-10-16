import test from 'node:test';
import assert from 'node:assert/strict';
import { createI18n } from 'vue-i18n';
import zh from '../src/locales/zh-CN/att/index.js';
import en from '../src/locales/en-US/att/index.js';
import ja from '../src/locales/ja-JP/att/index.js';

// Use real locale resources and interpolation. No UI transport or business data access.
for (const [locale, messages] of Object.entries({ zh, en, ja })) {
  const { t } = createI18n({ legacy: false, locale, messages: { [locale]: { att: messages } } }).global;
  for (const state of ['enable', 'disable']) {
    test(`${locale} 来源系统${state}确认与成功提示完整`, () => {
      const status = t(`att.sourceSystem.form.${state}`);
      const name = 'bin68_r114_source';
      const confirmation = t('att.sourceSystem.message.confirmStatus', { status, name });
      const success = t('att.sourceSystem.message.statusSuccess', { status });
      assert.ok(confirmation.includes(name));
      assert.ok(confirmation.includes(status));
      assert.doesNotMatch(confirmation, /[<>]|\{(?:status|name)\}/);
      assert.ok(success.startsWith(status));
      assert.doesNotMatch(success, /["「」<>]|\{status\}/);
    });
  }
}
