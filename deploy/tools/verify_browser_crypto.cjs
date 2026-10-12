const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');

const frontend = path.resolve(process.argv[2]);
const env = process.env;
const publicKey = (env.VITE_APP_RSA_PUBLIC_KEY || '').replace(/\\n/g, '\n');
const privateKey = (env.VITE_APP_RSA_PRIVATE_KEY || '').replace(/\\n/g, '\n');
const derivedPublicKey = crypto.createPublicKey(crypto.createPrivateKey(privateKey))
  .export({ type: 'spki', format: 'pem' });
if (derivedPublicKey.trim() !== publicKey.trim()) {
  throw new Error('The browser rememberMe RSA public and private keys do not match');
}
global.window = global;
const JSEncrypt = require(path.join(frontend, 'node_modules/jsencrypt'));
const source = fs.readFileSync(path.join(frontend, 'src/utils/jsencrypt.js'), 'utf8');
const implementation = source.replace(/import JSEncrypt from '[^']+'/g, '')
  .replaceAll('import.meta.env', 'env').replaceAll('export function', 'function');
const functions = vm.runInNewContext(implementation + '\n({encrypt, decrypt})', { JSEncrypt, env });
for (const password of ['rememberMe-deployment-check', '中文密码检查', 'symbols-@#$%-123']) {
  if (functions.decrypt(functions.encrypt(password)) !== password) {
    throw new Error('The source browser rememberMe RSA round trip failed');
  }
}
console.log('Source browser rememberMe RSA pair and round trips: PASS');
