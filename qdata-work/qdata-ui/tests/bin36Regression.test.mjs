import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const api = readFileSync(new URL('../src/views/ds/api/edit/index.vue', import.meta.url), 'utf8');
const nextStep = api.slice(api.indexOf('const nextStepLoading = ref(false);'), api.indexOf('\nasync function ', api.indexOf('const nextStepLoading = ref(false);')));

function stepHandler(valid, request) {
  let pending;
  const active = { value: 0 };
  const form = { value: { projectId: '9007199254740993', name: 'test', apiVersion: 'v1', apiUrl: '/test' } };
  const proxy = { $refs: { base: { validateFormBase(name, callback) { if (valid) pending = callback(form.value); } } } };
  const source = nextStep.slice(0, nextStep.indexOf('\n}', nextStep.indexOf('function handleNextStep()')) + 2);
  const handler = new Function('ref', 'proxy', 'activeReult', 'form1', 'form2', 'repeatFlag', 'td',
    `${source}; return handleNextStep;`)(value => ({ value }), proxy, active, form, { value: {} }, request, value => value);
  return { handler, active, done: () => pending };
}

test('an invalid API form makes no permission or uniqueness request', async () => {
  let calls = 0;
  const step = stepHandler(false, () => { calls++; });
  step.handler(); await step.done();
  assert.equal(calls, 0);
  assert.equal(step.active.value, 0);
});

test('API uniqueness failure is handled, stays at step one, and can be retried', async () => {
  let calls = 0;
  const step = stepHandler(true, async () => { calls++; throw new Error('permission denied'); });
  step.handler(); await step.done();
  step.handler(); await step.done();
  assert.equal(calls, 2);
  assert.equal(step.active.value, 0);
});

test('a valid API form sends one request and keeps Long project IDs intact', async () => {
  let complete;
  let calls = 0;
  const step = stepHandler(true, params => {
    calls++; assert.equal(params.projectId, '9007199254740993');
    return new Promise(resolve => { complete = resolve; });
  });
  step.handler(); step.handler();
  assert.equal(calls, 1);
  complete({ code: 200 }); await step.done();
  assert.equal(step.active.value, 1);
});

test('an empty AI conversation initializes without a default scope or model', async () => {
  const ai = readFileSync(new URL('../src/views/ai/chat/index/index.vue', import.meta.url), 'utf8');
  const mounted = [...ai.matchAll(/onMounted\(async \(\) => \{([\s\S]*?)\n\}\);/g)].at(-1)?.[1];
  assert.ok(mounted);
  let loaded = false;
  const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
  await new AsyncFunction('route', 'activeConversationId', 'activeMessageListLoading', 'getConversation', 'getMessageList', mounted)(
    { query: {} }, { value: null }, { value: false }, async () => {}, async () => { loaded = true; });
  assert.equal(loaded, true);
});

test('date formatting preserves explicit UTC and offset timestamps', () => {
  const utility = readFileSync(new URL('../src/utils/anivia.js', import.meta.url), 'utf8');
  const source = utility.slice(utility.indexOf('export function parseTime('), utility.indexOf('// form reset'))
    .replace('export function', 'function');
  const parseTime = new Function(`${source}; return parseTime;`)();
  const utc = '2026-10-15T10:08:18.000Z';
  const shanghai = '2026-10-15T18:08:18.000+08:00';
  assert.equal(parseTime(utc), parseTime(shanghai));
  assert.equal(parseTime(Date.parse(utc)), parseTime(utc));
  assert.equal(parseTime('2026-10-15 18:08:18'), '2026-10-15 18:08:18');
});
