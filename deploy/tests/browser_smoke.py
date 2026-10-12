#!/usr/bin/env python3
"""Browser smoke test for a fresh isolated installation and its test Redis."""
from pathlib import Path
import argparse
import base64
import json
import os
import re
import subprocess
import time
import urllib.request


class Browser:
    def __init__(self, endpoint):
        self.endpoint = endpoint.rstrip('/')
        self.session = None
        result = self.call('POST', '/session', {'capabilities': {'alwaysMatch': {'browserName': 'chrome', 'goog:loggingPrefs': {'browser': 'ALL'}, 'goog:chromeOptions': {'args': ['--headless=new', '--no-sandbox', '--disable-dev-shm-usage', '--window-size=1440,960']}}}})
        self.session = result['sessionId']

    def call(self, method, path, payload=None):
        data = None if payload is None else json.dumps(payload).encode()
        request = urllib.request.Request(self.endpoint + path, data=data, method=method, headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)['value']

    def command(self, method, path, payload=None):
        return self.call(method, '/session/' + self.session + path, payload)

    def script(self, script, *args):
        return self.command('POST', '/execute/sync', {'script': script, 'args': list(args)})

    def element(self, selector):
        result = self.command('POST', '/element', {'using': 'css selector', 'value': selector})
        return result['element-6066-11e4-a52e-4f735466cecf']

    def input(self, identifier, value):
        self.command('POST', '/element/' + identifier + '/clear', {})
        self.command('POST', '/element/' + identifier + '/value', {'text': value})

    def click_text(self, selector, text):
        element = self.script('''return [...document.querySelectorAll(arguments[0])]
            .find(x => x.innerText.trim() === arguments[1] && x.getClientRects().length > 0);''', selector, text)
        if not element:
            raise RuntimeError('Missing visible control: ' + text)
        self.command('POST', '/element/' + element['element-6066-11e4-a52e-4f735466cecf'] + '/click', {})

    def screenshot(self, path):
        path.write_bytes(base64.b64decode(self.command('GET', '/screenshot')))

    def close(self):
        if self.session:
            self.command('DELETE', '')


def wait_for(check, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = check()
        if result:
            return result
        time.sleep(0.4)
    raise RuntimeError('Browser condition timed out')


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True)
    parser.add_argument('--webdriver-url', default='http://127.0.0.1:18444')
    parser.add_argument('--admin-password-file', type=Path, required=True)
    parser.add_argument('--runtime-dir', type=Path, required=True)
    parser.add_argument('--redis-container', required=True)
    parser.add_argument('--output', type=Path, required=True)
    arguments = parser.parse_args()
    if arguments.admin_password_file.stat().st_mode & 0o077:
        parser.error('Administrator password file must be private')
    if not re.fullmatch(r'[a-zA-Z0-9_.-]+', arguments.redis_container):
        parser.error('Invalid test Redis container name')
    password = arguments.admin_password_file.read_text().strip()
    redis_config = (arguments.runtime_dir / 'redis.conf').read_text()
    redis_password = next(line.split(None, 1)[1].strip('"') for line in redis_config.splitlines() if line.startswith('requirepass '))
    arguments.output.mkdir(parents=True, exist_ok=True)
    browser = Browser(arguments.webdriver_url)
    results = []
    try:
        script = """(() => {
            window.__qdataTestRequests = [];
            const original = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(...args) {
                this.addEventListener('load', () => {
                    let code;
                    try { code = JSON.parse(this.responseText).code; } catch (_) {}
                    window.__qdataTestRequests.push({path: new URL(this.responseURL).pathname, status: this.status, code});
                    if (this.responseURL.includes('/captchaImage') && this.status === 200) {
                        try { window.__qdataTestCaptcha = JSON.parse(this.responseText); } catch (_) {}
                    }
                });
                return original.apply(this, args);
            };
        })();"""
        browser.command('POST', '/goog/cdp/execute', {'cmd': 'Page.addScriptToEvaluateOnNewDocument', 'params': {'source': script}})
        browser.command('POST', '/url', {'url': arguments.url.rstrip('/') + '/login'})
        captcha = wait_for(lambda: browser.script('return window.__qdataTestCaptcha || null;'))
        assert captcha.get('code') == 200
        inputs = browser.script('return [...document.querySelectorAll("form input")].map(x=>({type:x.type,placeholder:x.placeholder}));')
        (arguments.output / 'login-input-shape.json').write_text(json.dumps(inputs, ensure_ascii=False, indent=2))
        browser.input(browser.element('form input[type="text"]'), 'admin')
        browser.input(browser.element('form input[type="password"]'), password)
        if captcha.get('captchaEnabled'):
            identifier = captcha['uuid']
            assert re.fullmatch(r'[a-fA-F0-9-]{20,64}', identifier)
            command = ['docker', 'exec', '-i', arguments.redis_container, 'sh', '-c', 'read -r REDISCLI_AUTH; export REDISCLI_AUTH; exec redis-cli --raw GET "$1"', 'sh', 'captcha_codes:' + identifier]
            answer = subprocess.check_output(command, input=(redis_password + '\n').encode()).decode().strip()
            assert answer and answer != '(nil)'
            try:
                answer = str(json.loads(answer))
            except json.JSONDecodeError:
                pass
            element_ids = browser.command('POST', '/elements', {'using': 'css selector', 'value': 'form input[type="text"]'})
            assert len(element_ids) >= 2
            browser.input(element_ids[-1]['element-6066-11e4-a52e-4f735466cecf'], answer)
        browser.command('POST', '/element/' + browser.element('form .el-button--primary') + '/click', {})
        wait_for(lambda: '/login' not in browser.command('GET', '/url'))
        wait_for(lambda: browser.script('return document.body.innerText.includes("数据建模");'))
        browser.screenshot(arguments.output / 'dashboard.png')
        results.append({'page': 'login/dashboard', 'status': 'PASS'})
        pages = [('/dm/model/warehouse', 'warehouse', '选择模型开始编辑'),
                 ('/dm/model/architecture', 'architecture', '五层 Hive 数仓'),
                 ('/da/asset', 'assets', '资产类型')]
        for path, name, marker in pages:
            browser.command('POST', '/url', {'url': arguments.url.rstrip('/') + path})
            wait_for(lambda: browser.script('return document.readyState === "complete";'))
            time.sleep(2)
            current = browser.command('GET', '/url')
            text = browser.script('return document.body.innerText;')
            assert '/login' not in current and marker in text and '404' not in text
            if name == 'warehouse':
                browser.click_text('.el-tabs__item', '库级空间')
                wait_for(lambda: browser.script('return document.body.innerText.includes("项目与分层数据库");'))
                browser.click_text('.el-tabs__item', '模型与加工')
                wait_for(lambda: browser.script('return document.body.innerText.includes("选择模型开始编辑");'))
                content = browser.script('return document.body.innerText;')
                assert all(layer in content for layer in ['ODS', 'DWD', 'DWS', 'DWT', 'ADS'])
                browser.screenshot(arguments.output / 'five-layer-models.png')
                browser.click_text('.el-tabs__item', '持续批次')
                time.sleep(1)
                browser.screenshot(arguments.output / 'warehouse-batches.png')
                browser.click_text('.el-tabs__item', '库级空间')
                browser.click_text('button', '刷新')
            elif name == 'architecture':
                browser.click_text('.el-radio-button', '数据架构')
                browser.click_text('.el-radio-button', '2D 平面')
                browser.click_text('button', '适应画布')
            elif name == 'assets':
                browser.click_text('button', '查询')
                time.sleep(1)
                browser.click_text('button', '重置')
            time.sleep(1)
            failures = browser.script('return (window.__qdataTestRequests || []).filter(x => x.status >= 400 || (x.code && x.code !== 200));')
            assert not failures, 'Page API failures: ' + json.dumps(failures)
            browser.screenshot(arguments.output / (name + '.png'))
            (arguments.output / (name + '.txt')).write_text(text)
            results.append({'page': path, 'status': 'PASS', 'text_length': len(text), 'controls_clicked': True})
        (arguments.output / 'browser-result.json').write_text(json.dumps({'results': results, 'scope': 'fresh-instance', 'captcha_enabled': captcha.get('captchaEnabled')}, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps({'browser_checks': len(results), 'status': 'PASS'}))
    except Exception:
        browser.screenshot(arguments.output / 'failed-page.png')
        (arguments.output / 'failed-page.txt').write_text(browser.script('return document.body.innerText;'))
        failures = browser.script('return (window.__qdataTestRequests || []).filter(x => x.status >= 400 || (x.code && x.code !== 200));')
        (arguments.output / 'failed-api-status.json').write_text(json.dumps(failures, ensure_ascii=False, indent=2))
        raise
    finally:
        browser.close()


if __name__ == '__main__':
    main()
