const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const code = fs.readFileSync('app/src/main/assets/download.js', 'utf8');

function setup(blob) {
  const messages = [];
  let listener;
  const bridge = { postMessage: data => messages.push(JSON.parse(data)) };
  const context = {
    window: { GifNative: bridge }, GifNative: bridge,
    document: { addEventListener: (_, callback) => { listener = callback; } },
    fetch: async () => ({ blob: async () => blob }), Uint8Array,
    btoa: text => Buffer.from(text, 'binary').toString('base64')
  };
  vm.runInNewContext(code, context);
  return { api: context.window.GifDownloads, bridge, messages, click: e => listener(e) };
}
const tick = () => new Promise(resolve => setImmediate(resolve));

test('saves a multi-chunk binary GIF exactly and waits for acknowledgements', async () => {
  const bytes = Buffer.alloc(130000);
  for (let i = 0; i < bytes.length; i++) bytes[i] = i % 256;
  const s = setup(new Blob([bytes], { type: 'image/gif' }));
  await s.api.offer('blob:test', 'ocean.gif');
  assert.equal(s.messages[0].type, 'offer');
  assert.equal(s.messages[0].size, bytes.length);
  s.api.start(); await tick();
  assert.equal(s.messages.filter(m => m.type === 'chunk').length, 1);
  for (let i = 0; i < 3; i++) { s.bridge.onmessage({ data: 'next' }); await tick(); }
  const chunks = s.messages.filter(m => m.type === 'chunk');
  assert.ok(chunks.every(m => m.data.length <= 65536));
  assert.deepEqual(Buffer.concat(chunks.map(m => Buffer.from(m.data, 'base64'))), bytes);
  assert.equal(s.messages.at(-1).type, 'end');
  s.bridge.onmessage({ data: 'saved' });
  await s.api.offer('blob:second', 'second.gif');
  assert.equal(s.messages.at(-1).type, 'offer');
});

test('cancel frees the pending download and allows retry', async () => {
  const s = setup(new Blob(['GIF89a'], { type: 'image/gif' }));
  await s.api.offer('blob:first', 'first.gif');
  await s.api.offer('blob:ignored', 'ignored.gif');
  assert.equal(s.messages.length, 1);
  s.api.cancel();
  s.api.start(); await tick();
  assert.equal(s.messages.length, 1);
  await s.api.offer('blob:retry', 'retry.gif');
  assert.equal(s.messages.at(-1).name, 'retry.gif');
});

test('rejects non-GIF blobs', async () => {
  const s = setup(new Blob(['bad'], { type: 'text/html' }));
  await s.api.offer('blob:bad', 'bad.gif');
  assert.equal(s.messages[0].type, 'error');
});

test('cancel while an asynchronous chunk is read never sends stale data', async () => {
  let release;
  const blob = { type: 'image/gif', size: 6,
    slice: () => ({ arrayBuffer: () => new Promise(resolve => { release = resolve; }) }) };
  const s = setup(blob);
  await s.api.offer('blob:first', 'first.gif');
  s.api.start();
  s.api.cancel();
  release(new Uint8Array([71, 73, 70, 56, 57, 97]).buffer);
  await tick();
  assert.equal(s.messages.filter(m => m.type === 'chunk').length, 0);
});

test('intercepts the site programmatic download anchor', async () => {
  const s = setup(new Blob(['GIF89a'], { type: 'image/gif' }));
  let prevented = false;
  s.click({ target: { closest: () => ({ href: 'blob:site', download: '해양대.gif', hasAttribute: () => true }) },
    preventDefault: () => { prevented = true; }, stopImmediatePropagation() {} });
  await tick();
  assert.ok(prevented);
  assert.equal(s.messages[0].name, '해양대.gif');
});
