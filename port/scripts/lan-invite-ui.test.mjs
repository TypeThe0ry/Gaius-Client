import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
import test from 'node:test';

const template = await readFile(new URL('../web/launcher/index.template.html', import.meta.url), 'utf8');
const source = template.slice(template.indexOf('window.__gaiusOpenToLan ='),
  template.indexOf('const LEGACY_OFFLINE_GAIUS_UUID'));

function fixture(writeText) {
  let focused;
  const nodes = [];
  const make = tag => {
    const node = {tag, style: {}, children: [], listeners: {}, isConnected: true,
      setAttribute(name, value) { this[name] = value; },
      append(...children) { this.children.push(...children); },
      appendChild(child) { this.children.push(child); },
      addEventListener(type, fn) { this.listeners[type] = fn; },
      focus() { focused = this; }, select() { this.selected = true; },
      remove() { this.isConnected = false; }};
    nodes.push(node);
    return node;
  };
  const previous = make('canvas');
  const events = [];
  const document = {body: make('body'), activeElement: previous, createElement: make,
    getElementById(id) { return nodes.find(node => node.id === id && node.isConnected); }};
  const window = {__gaiusMinecraftEvents: events,
    __gaiusLanInviteProvider: async () => ({url: 'https://host.invalid/?server=client-abc.gaius-local'}),
    prompt() { assert.fail('LAN invite must not pause the page with a native dialog'); }};
  vm.runInNewContext(source, {window, document, navigator: {clipboard: {writeText}}, Promise});
  return {window, document, events, nodes, previous, focused: () => focused};
}

test('pending clipboard permission does not delay invite publication or completion', async () => {
  const f = fixture(() => {
    assert.equal(f.events[0].event, 'lan:opened');
    assert.ok(f.document.getElementById('gaius-lan-invite'));
    return new Promise(() => {});
  });
  const result = await f.window.__gaiusOpenToLan({brokerSessionId: 'abc'});
  assert.equal(result.ok, true);
  const input = f.nodes.find(node => node.tag === 'input');
  assert.equal(input.value, result.url);
  assert.equal(input.readOnly, true);
  assert.equal(input.selected, true);
  assert.equal(f.focused(), input);
  const panel = f.document.getElementById('gaius-lan-invite');
  let prevented = false;
  let stopped = false;
  panel.listeners.keydown({key: 'Escape', preventDefault() { prevented = true; },
    stopPropagation() { stopped = true; }});
  assert.equal(prevented && stopped, true);
  assert.equal(panel.isConnected, false);
  assert.equal(f.focused(), f.previous);
});

test('clipboard rejection keeps a selectable link, and copy and close buttons work', async () => {
  let attempts = 0;
  const f = fixture(() => { attempts++; return Promise.reject(new Error('permission denied')); });
  await f.window.__gaiusOpenToLan({});
  await Promise.resolve();
  const status = f.nodes.find(node => node.role === 'status');
  assert.match(status.textContent, /keyboard/);
  f.nodes.find(node => node.textContent === 'Copy link').listeners.click();
  await Promise.resolve();
  assert.equal(attempts, 2);
  const first = f.document.getElementById('gaius-lan-invite');
  await f.window.__gaiusOpenToLan({});
  assert.equal(first.isConnected, false);
  f.nodes.filter(node => node.textContent === 'Close').at(-1).listeners.click();
  assert.equal(f.document.getElementById('gaius-lan-invite'), undefined);
});

test('the in-game Open to LAN screen gets the invite without a page panel or alert', async () => {
  const f = fixture(() => Promise.resolve());
  f.window.alert = () => assert.fail('the in-game screen reports failures itself');
  const result = await f.window.__gaiusOpenToLan({brokerSessionId: 'abc', inGame: true});
  assert.equal(result.ok, true);
  assert.equal(f.events[0].event, 'lan:opened');
  assert.equal(f.document.getElementById('gaius-lan-invite'), undefined);
  delete f.window.__gaiusLanInviteProvider;
  const unavailable = await f.window.__gaiusOpenToLan({inGame: true});
  assert.equal(unavailable.ok, false);
  assert.match(unavailable.message, /relay-backed LAN broker/);
});
