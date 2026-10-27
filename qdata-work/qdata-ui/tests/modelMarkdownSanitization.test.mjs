import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { JSDOM } from 'jsdom';
import { computed, reactive, ref } from 'vue';
import MarkdownIt from 'markdown-it';
import hljs from 'highlight.js';
import { renderContent, getFileFormat } from '../src/utils/app/chat/chat.js';

// 不启用脚本执行、资源加载或任何后端连接，仅检查离线 DOM。
const { window } = new JSDOM('', { url: 'https://qdata.test/chat/session' });
globalThis.window = window;
const { sanitizeModelHtml } = await import('../src/utils/app/chat/sanitizeModelHtml.js');
const source = readFileSync(new URL('../src/components/MarkdownView/index.vue', import.meta.url), 'utf8');
const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .+;$/gm, '');

function component(content) {
  const props = reactive({ content });
  const mounted = [];
  const copied = [];
  const state = vm.runInNewContext(`${script}\n;({ deepThinking, renderedMarkdown, contentRef });`, {
    defineProps: () => props,
    getCurrentInstance: () => ({ proxy: { $modal: { msgSuccess() {} } } }),
    useRouter: () => ({}),
    useClipboard: () => ({ copy: (value) => copied.push(value) }),
    ref, computed, MarkdownIt, hljs, renderContent, getFileFormat, sanitizeModelHtml,
    onMounted: (callback) => mounted.push(callback),
    defineExpose() {},
    t: (key) => key,
    word: '', excel: '', ppt: '', pdf: '', tet: '',
  });
  const root = window.document.createElement('div');
  state.contentRef.value = root;
  for (const callback of mounted) callback();
  return {
    props, state, copied,
    render() {
      root.innerHTML = state.deepThinking.value + state.renderedMarkdown.value;
      return root;
    },
  };
}

function assertSafe(root) {
  assert.equal(root.querySelector('script, style, link, iframe, object, embed, svg, math, video, audio, source, base'), null);
  for (const element of root.querySelectorAll('*')) {
    for (const attribute of element.attributes) {
      assert.doesNotMatch(attribute.name, /^on|^(style|srcset|ping|background|srcdoc)$/i);
      if (attribute.name === 'href') assert.doesNotMatch(attribute.value, /^\s*javascript:/i);
      if (attribute.name === 'src') {
        const url = new URL(attribute.value);
        assert.equal(url.origin, window.location.origin);
        assert.match(url.protocol, /^https?:$/);
      }
    }
  }
}

const attacks = [
  ['事件属性', '<img src=x onerror=alert(1)><div onclick="alert(2)">内容</div>'],
  ['混合大小写事件', '<img src="/local.png" oNlOaD="alert(1)" ONERROR="alert(2)">'],
  ['危险链接协议', '<a href="javascript:alert(1)">链接</a><a href="jav&#x61;script:alert(2)">实体</a><a href="java&#9;script:alert(3)">制表符</a>'],
  ['外联 HTML 图片', '<img src="https://attacker.invalid/pixel"><img src="//attacker.invalid/pixel">'],
  ['外联 Markdown 图片', '![跟踪](https://attacker.invalid/pixel)'],
  ['反斜线与实体 URL', '<img src="&#47;&#47;attacker.invalid/a"><img src="\\\\attacker.invalid/a"><img src="https://qdata.test@attacker.invalid/a">'],
  ['其他来源与协议', '<img src="http://qdata.test/a"><img src="https://qdata.test:444/a"><img src="data:image/svg+xml,<svg onload=alert(1)></svg>"><img src="blob:https://qdata.test/id">'],
  ['CSS 与响应式资源', '<style>@import "https://attacker.invalid/a";</style><div style="background:url(https://attacker.invalid/a)">内容</div><img src="/ok.png" srcset="https://attacker.invalid/a 2x"><table background="https://attacker.invalid/a"><tr><td>内容</td></tr></table>'],
  ['嵌入资源', '<iframe src="https://attacker.invalid/a"></iframe><object data="https://attacker.invalid/a"></object><video poster="https://attacker.invalid/a"><source src="https://attacker.invalid/a"></video><link rel="stylesheet" href="https://attacker.invalid/a"><base href="https://attacker.invalid/">'],
  ['SVG/MathML 与畸形 HTML', '<svg><image href="https://attacker.invalid/a" onload="alert(1)"></image></svg><math><mtext><img src=x onerror=alert(1)></mtext></math>'],
];

for (const [name, payload] of attacks) {
  test(`正文和思考区拦截：${name}`, () => {
    const view = component(`<think>${payload}</think>\n\n${payload}`);
    const root = view.render();
    assertSafe(root);
    assert.equal(root.querySelector('[href*="script:"]'), null);
    assert.equal(root.querySelector('[src*="attacker.invalid"]'), null);
  });
}

test('标准 Markdown 的标题、表格、列表、引用、强调、链接与内部图片保留', () => {
  const view = component('# 标题\n\n| 列 | 值 |\n| --- | --- |\n| 正常 | 42 |\n\n- 列表\n\n> 引用\n\n**重点** [帮助](/help) [普通链接](https://example.invalid/help)\n\n![内部图](/profile/a.png)\n\n![相对图](images/b.png)\n\n<img src="https://qdata.test/c.png" alt="同源图">');
  const root = view.render();
  assertSafe(root);
  assert.equal(root.querySelector('h1').textContent, '标题');
  assert.equal(root.querySelector('tbody td').textContent, '正常');
  assert.equal(root.querySelector('li').textContent, '列表');
  assert.match(root.querySelector('blockquote').textContent, /引用/);
  assert.equal(root.querySelector('strong').textContent, '重点');
  assert.equal(root.querySelector('a').getAttribute('href'), '/help');
  assert.equal(root.querySelectorAll('a').length, 2);
  assert.deepEqual([...root.querySelectorAll('img')].map((img) => img.src), [
    'https://qdata.test/profile/a.png', 'https://qdata.test/chat/images/b.png', 'https://qdata.test/c.png',
  ]);
});

test('代码块中的引号和 HTML 保持原文，不能注入元素，复制结果一致', () => {
  const code = `const value = "'"><img src=https://attacker.invalid/pixel onerror=alert(1)>";\n`;
  const view = component(`\`\`\`javascript\n${code}\`\`\``);
  const root = view.render();
  assertSafe(root);
  assert.equal(root.querySelector('img'), null);
  assert.equal(root.querySelector('code.hljs').textContent, code);
  const button = root.querySelector('#copy');
  assert.equal(button.dataset.copy, code);
  button.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  assert.deepEqual(view.copied, [code]);
});

test('未知语言代码块保留转义文本', () => {
  const root = component('```unknown-language\n<img src=x onerror=alert(1)>\n```').render();
  assertSafe(root);
  assert.equal(root.querySelector('img'), null);
  assert.match(root.querySelector('code').textContent, /<img src=x onerror=alert\(1\)>/);
});

test('流式未闭合思考区及后续内容更新每次重新消毒', () => {
  const view = component('<think><img src="https://attacker.invalid/a" onerror="alert(1)">思考');
  assertSafe(view.render());
  view.props.content += '</think>\n\n# 回答\n<img src="/local.png" onerror="alert(2)">';
  const root = view.render();
  assertSafe(root);
  assert.equal(root.querySelector('h1').textContent, '回答');
  assert.equal(root.querySelector('img[src]').src, 'https://qdata.test/local.png');
});

test('空响应与重复消毒保持稳定', () => {
  assert.equal(component('').render().textContent, '');
  const clean = sanitizeModelHtml('<p>安全<img src="/ok.png" onerror="alert(1)"></p>');
  assert.equal(sanitizeModelHtml(clean), clean);
});
