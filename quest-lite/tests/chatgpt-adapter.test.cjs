const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const code = fs.readFileSync(require('node:path').join(__dirname, '../app/src/main/assets/chatgpt-adapter.js'), 'utf8');
function page(origin = 'https://chatgpt.com') {
  const state = {now: 0, draft: '', busy: false, image: false, progress: false, disabled: false, sent: 0, changes: 0};
  const send = {get disabled() {return state.disabled;}, getAttribute() {return null;}, click() {state.sent++;}};
  const form = {querySelectorAll(selector) {return selector.includes('input') ? [input] : [];}, querySelector(selector) {
    if (selector.includes('img')) return state.image ? {} : null;
    if (selector.includes('progressbar')) return state.progress ? {} : null;
    if (selector.includes('send-button')) return send;
    return null;
  }};
  const field = {tagName: 'DIV', get innerText() {return state.draft;}, set textContent(value) {state.draft = value;},
    getClientRects() {return [{}];}, focus() {}, dispatchEvent() {}, closest() {return form;}};
  const input = {accept: 'image/*', dispatchEvent() {state.changes++;}};
  const context = {window: {}, location: {origin}, Date: {now: () => state.now},
    document: {querySelector(selector) {return selector === '#prompt-textarea' ? field : state.busy ? {} : null;},
      querySelectorAll(selector) {return selector === 'button, a' ? (state.login ? [{innerText:'Log in', getClientRects(){return [{}];}, closest(){return null;}}] : []) : selector.includes('input[type') ? [input] : [field];}},
    File: class {}, DataTransfer: class {constructor() {this.items = {add() {}}; this.files = []; }},
    Event: class {}, InputEvent: class {}, atob: x => Buffer.from(x, 'base64').toString('binary')};
  vm.runInNewContext(code, context);
  return {adapter: context.window.metamanning, state, context};
}
test('adapter never installs on a foreign or authentication origin', () => {
  assert.equal(page('https://auth.openai.com').adapter, undefined);
  assert.equal(page('https://chatgpt.com.evil.example').adapter, undefined);
});
test('dictation appends to a draft without submitting', () => {
  const {adapter, state} = page(); state.draft = 'Existing';
  assert.equal(adapter.dictate('words'), 'ok'); assert.equal(state.draft, 'Existing words'); assert.equal(state.sent, 0);
});
test('sampled live does not replace a user draft, attachment, or active response', () => {
  const {adapter, state} = page(); state.draft = 'My draft';
  assert.equal(adapter.attach('eA==', true), 'draft-present');
  state.draft = ''; state.image = true; assert.equal(adapter.attach('eA==', true), 'draft-present');
  state.image = false; state.busy = true; assert.equal(adapter.attach('eA==', true), 'busy');
  assert.equal(state.changes, 0);
});
test('live waits for attachment evidence, no progress, and an enabled Send button', () => {
  const {adapter, state} = page(); assert.equal(adapter.attach('eA==', true), 'staged');
  state.now = 4000; assert.equal(adapter.sendPending(), 'waiting'); assert.equal(state.draft, '');
  state.image = true; state.progress = true; assert.equal(adapter.sendPending(), 'waiting');
  state.progress = false; assert.equal(adapter.sendPending(), 'waiting');
  state.disabled = true; assert.equal(adapter.sendPending(), 'waiting'); assert.equal(state.sent, 0);
  state.disabled = false; assert.equal(adapter.sendPending(), 'sent'); assert.equal(state.sent, 1);
  assert.equal(adapter.sendPending(), 'none');
});
test('stopping sharing cancels pending sending without deleting a user attachment', () => {
  const {adapter, state} = page(); adapter.attach('eA==', true); adapter.cancel();
  state.now = 5000; state.image = true; assert.equal(adapter.sendPending(), 'none'); assert.equal(state.sent, 0);
});
test('upload timeout fails closed', () => {
  const {adapter, state} = page(); adapter.attach('eA==', true); state.now = 46000;
  assert.equal(adapter.sendPending(), 'upload-timeout'); assert.equal(state.sent, 0);
});
test('new user text entered during upload is preserved and never auto-sent', () => {
  const {adapter, state} = page(); adapter.attach('eA==', true);
  state.now = 4000; state.image = true; state.draft = 'User started typing';
  assert.equal(adapter.sendPending(), 'draft-present'); assert.equal(state.draft, 'User started typing'); assert.equal(state.sent, 0);
});
test('manual snapshot only attaches, never submits', () => {
  const {adapter, state} = page(); assert.equal(adapter.attach('eA==', false), 'attached');
  state.now = 4000; state.image = true; assert.equal(adapter.sendPending(), 'none'); assert.equal(state.sent, 0);
});
test('editing the generated prompt before Send stops automatic submission', () => {
  const {adapter, state} = page(); adapter.attach('eA==', true);
  state.now = 4000; state.image = true; assert.equal(adapter.sendPending(), 'waiting');
  state.draft = 'User replaced the generated prompt';
  assert.equal(adapter.sendPending(), 'draft-present'); assert.equal(state.sent, 0);
});
test('a conversation URL change prevents submission of an already staged frame', () => {
  const {adapter, state, context} = page();
  context.location.href = 'https://chatgpt.com/c/one'; adapter.attach('eA==', true);
  state.now = 4000; state.image = true; context.location.href = 'https://chatgpt.com/c/two';
  assert.equal(adapter.sendPending(), 'conversation-changed'); assert.equal(state.sent, 0);
});
test('visible account sign-in controls block file changes outside the composer form', () => {
  const {adapter, state} = page(); state.login = true;
  assert.equal(adapter.capabilities().uploadsRequireLogin, true);
  assert.equal(adapter.attach('eA==', false), 'authentication-required');
  assert.equal(state.changes, 0);
});
test('custom live questions survive staging and are the only prompt auto-submitted', () => {
  const {adapter, state} = page(); adapter.attach('eA==', true, 'Read the label in front of me.');
  state.now = 4000; state.image = true; assert.equal(adapter.sendPending(), 'waiting');
  assert.equal(state.draft, 'Read the label in front of me.');
  assert.equal(adapter.sendPending(), 'sent');
});
test('tool drafts preserve existing text and never auto-submit', () => {
  const {adapter,state}=page();state.draft='Keep my draft';
  assert.equal(adapter.draft('Browser excerpt'), 'draft-present');assert.equal(state.draft,'Keep my draft');
  state.draft='';assert.equal(adapter.draft('Browser excerpt'),'ok');assert.equal(state.draft,'Browser excerpt');assert.equal(state.sent,0);
});
test('widget extraction uses only HTML in the latest assistant reply', () => {
  const {adapter,context}=page();const original=context.document.querySelectorAll;
  context.TextEncoder=TextEncoder;
  let replies=[{querySelectorAll(){return [{className:'language-html',textContent:'<h1>Widget</h1>'}];}}];
  context.document.querySelectorAll=selector=>selector.includes('data-message-author-role')?replies:original(selector);
  assert.equal(adapter.latestHtml(),'<h1>Widget</h1>');
  replies.push({querySelectorAll(){return [];},innerText:'Just an explanation.'});
  assert.equal(adapter.latestHtml(),'no-widget-html');
  replies=[{querySelectorAll(){return [];},innerText:'```html\n<section>Latest</section>\n```'}];
  assert.equal(adapter.latestHtml(),'<section>Latest</section>\n');
});
test('oversized UTF-8 widget code is rejected instead of silently truncated', () => {
  const {adapter,context}=page();context.TextEncoder=TextEncoder;
  context.document.querySelectorAll=()=>[{querySelectorAll(){return [{className:'language-html',textContent:'<div>'+ '🧩'.repeat(40000)+'</div>'}];}}];
  assert.equal(adapter.latestHtml(),'widget-too-large');
});
