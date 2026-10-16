import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRenderer, getCurrentInstance, onBeforeUnmount, onMounted } from 'vue';
import { parse } from '@vue/compiler-sfc';
import bus from '../src/utils/bus.js';

// 执行首页真实监听器及生命周期代码，使用 Vue 挂载/卸载和项目真实 event bus。
// 图表、初始化请求和宿主渲染为离线替身；EventTarget 保留浏览器的监听去重语义。
const page = parse(readFileSync(new URL('../src/views/sys/index.vue', import.meta.url), 'utf8')).descriptor;
const script = page.scriptSetup.content;
assert.ok(script.includes('function chartIntancesResize()'));
const lifecycle = script.slice(script.indexOf('function chartIntancesResize()'));
const renderer = createRenderer({
  createComment: () => ({}), createText: () => ({}), createElement: () => ({}),
  insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {},
  parentNode: () => null, nextSibling: () => null,
});

class OfflineWindow extends EventTarget {
  registrations = [];
  removals = [];
  addEventListener(...args) { this.registrations.push(args); super.addEventListener(...args); }
  removeEventListener(...args) { this.removals.push(args); super.removeEventListener(...args); }
}

function mountHome(t, window = new OfflineWindow()) {
  const errors = [], registrations = [], removals = [];
  const counts = { overview: 0, carousel: 0, resize: 0 };
  const app = renderer.createApp({
    setup() {
      new Function('window', 'chartIntances', 'console', 'onBeforeUnmount', 'onMounted',
        'getCurrentInstance', 'refreshOverview', 'getxljtcont', lifecycle)(
        window, [{ resize: () => counts.resize++ }], { log() {} }, onBeforeUnmount, onMounted,
        getCurrentInstance, () => counts.overview++, () => counts.carousel++,
      );
      return () => null;
    },
  });
  app.config.globalProperties.$bus = {
    on(event, handler) { registrations.push([event, handler]); bus.on(event, handler); },
    off(event, handler) { removals.push([event, handler]); bus.off(event, handler); },
  };
  app.config.errorHandler = error => errors.push(error);
  app.mount({});
  let unmounted = false;
  const unmount = () => { if (!unmounted) { app.unmount(); unmounted = true; } };
  t.after(() => {
    unmount();
    // 失败时也只移除本实例的监听，不清空其他监听。
    for (const [event, handler] of registrations) bus.off(event, handler);
  });
  return { window, errors, counts, registrations, removals, unmount };
}

test('挂载与卸载使用同一事件和具名处理器，卸载无 ReferenceError', t => {
  const h = mountHome(t);
  assert.equal(h.registrations.length, 1);
  const [event, handler] = h.registrations[0];
  assert.equal(event, 'getsidebarStatus');
  assert.equal(typeof handler, 'function');
  assert.equal(handler.name, 'handleSidebarStatus');
  assert.deepEqual(h.counts, { overview: 1, carousel: 1, resize: 0 });
  h.unmount();
  assert.deepEqual(h.errors, []);
  assert.equal(h.removals.length, 1);
  assert.equal(h.removals[0][0], event);
  assert.equal(h.removals[0][1], handler);
  assert.equal(bus.events[event].includes(handler), false);
  assert.equal(h.window.removals[0][0], 'resize');
  assert.equal(h.window.removals[0][1], h.window.registrations[0][1]);
});

test('侧栏事件保留 resize 注册语义，多次触发不重复执行图表 resize', t => {
  const h = mountHome(t);
  bus.emit('getsidebarStatus', true);
  bus.emit('getsidebarStatus', false);
  assert.equal(h.window.registrations.length, 3);
  assert.ok(h.window.registrations.every(([event, handler]) =>
    event === 'resize' && handler === h.window.registrations[0][1]));
  assert.equal(h.counts.resize, 0);
  h.window.dispatchEvent(new Event('resize'));
  assert.equal(h.counts.resize, 1);
  h.unmount();
  bus.emit('getsidebarStatus', true);
  h.window.dispatchEvent(new Event('resize'));
  assert.equal(h.window.registrations.length, 3);
  assert.equal(h.counts.resize, 1);
  assert.deepEqual(h.errors, []);
});

test('卸载保留同事件的其他订阅者和无关事件', t => {
  let sidebarCalls = 0, otherCalls = 0;
  const otherSidebar = () => sidebarCalls++;
  const otherEvent = () => otherCalls++;
  bus.on('getsidebarStatus', otherSidebar);
  bus.on('home-test-other', otherEvent);
  t.after(() => { bus.off('getsidebarStatus', otherSidebar); bus.off('home-test-other', otherEvent); });
  const h = mountHome(t);
  h.unmount();
  bus.emit('getsidebarStatus', false);
  bus.emit('home-test-other');
  assert.equal(sidebarCalls, 1);
  assert.equal(otherCalls, 1);
  assert.ok(bus.events.getsidebarStatus.includes(otherSidebar));
  assert.ok(bus.events['home-test-other'].includes(otherEvent));
  assert.deepEqual(h.errors, []);
});

test('两个首页实例互不解绑，卸载一个后另一个仍响应', t => {
  const window = new OfflineWindow();
  const first = mountHome(t, window), second = mountHome(t, window);
  assert.notEqual(first.registrations[0][1], second.registrations[0][1]);
  first.unmount();
  bus.emit('getsidebarStatus', false);
  window.dispatchEvent(new Event('resize'));
  assert.equal(first.counts.resize, 0);
  assert.equal(second.counts.resize, 1);
  assert.equal(first.removals[0][1], first.registrations[0][1]);
  second.unmount();
  window.dispatchEvent(new Event('resize'));
  assert.equal(second.counts.resize, 1);
  assert.deepEqual([...first.errors, ...second.errors], []);
});

test('反复刷新对应的 20 次挂载/卸载后不残留本页订阅', t => {
  const window = new OfflineWindow();
  const baseline = [...(bus.events.getsidebarStatus || [])];
  for (let cycle = 0; cycle < 20; cycle++) {
    const h = mountHome(t, window);
    assert.equal(bus.events.getsidebarStatus.length, baseline.length + 1);
    bus.emit('getsidebarStatus', cycle % 2 === 0);
    window.dispatchEvent(new Event('resize'));
    assert.equal(h.counts.resize, 1);
    h.unmount();
    assert.deepEqual(h.errors, []);
    assert.deepEqual([...bus.events.getsidebarStatus], baseline);
    const registeredBefore = window.registrations.length;
    bus.emit('getsidebarStatus', false);
    window.dispatchEvent(new Event('resize'));
    assert.equal(window.registrations.length, registeredBefore);
    assert.equal(h.counts.resize, 1);
  }
});
