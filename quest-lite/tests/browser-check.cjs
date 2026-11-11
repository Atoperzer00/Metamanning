const {chromium} = require('playwright');
const fs = require('node:fs');
const assert = require('node:assert/strict');
(async () => {
  const browser = await chromium.launch({executablePath: process.env.CHROMIUM_PATH || (fs.existsSync('/usr/bin/chromium') ? '/usr/bin/chromium' : undefined), headless: true, args: ['--no-sandbox']});
  try {
    const page = await browser.newPage();
    await page.route('https://chatgpt.com/**', route => route.fulfill({contentType: 'text/html', body: `
      <form onsubmit="event.preventDefault()"><div id="prompt-textarea" contenteditable="true"></div>
      <input type="file" accept="image/*"><button data-testid="send-button" type="button">Send</button></form>
      <script>window.sends=0;document.querySelector('button').onclick=()=>window.sends++;</script>`}));
    await page.goto('https://chatgpt.com/');
    await page.addScriptTag({content: fs.readFileSync(require('node:path').join(__dirname, '../app/src/main/assets/chatgpt-adapter.js'), 'utf8')});
    assert.equal(await page.evaluate(() => window.metamanning.dictate('Hello from speech')), 'ok');
    assert.equal(await page.locator('#prompt-textarea').innerText(), 'Hello from speech');
    assert.equal(await page.evaluate(() => window.metamanning.attach('eA==', true)), 'draft-present');
    assert.equal(await page.evaluate(() => window.metamanning.attach('eA==', false)), 'attached');
    assert.equal(await page.locator('input').evaluate(input => input.files[0].type), 'image/jpeg');
    assert.equal(await page.evaluate(() => window.sends), 0);
    await page.locator('#prompt-textarea').fill('');
    assert.equal(await page.evaluate(() => window.metamanning.attach('eA==', true)), 'staged');
    await page.evaluate(() => { const image = document.createElement('img'); document.querySelector('form').append(image); });
    await page.waitForTimeout(3100);
    assert.equal(await page.evaluate(() => window.metamanning.sendPending()), 'waiting');
    assert.equal(await page.evaluate(() => window.metamanning.sendPending()), 'sent');
    assert.equal(await page.evaluate(() => window.sends), 1);
    const adapterCode = fs.readFileSync(require('node:path').join(__dirname, '../app/src/main/assets/chatgpt-adapter.js'), 'utf8');
    // Re-injection disposes old capture listeners; only one briefing is prepared.
    await page.addScriptTag({content: adapterCode});
    await page.evaluate(() => window.luma.configureRole('You are Luma, your meta assistant.', true));
    await page.locator('#prompt-textarea').fill('Arrange my workspace');
    const filesBefore = await page.locator('input').evaluate(input => input.files.length);
    await page.locator('button').click();
    assert.equal(await page.evaluate(() => window.sends), 1);
    const briefing = await page.locator('#prompt-textarea').innerText();
    assert.match(briefing, /You are Luma/); assert.match(briefing, /Arrange my workspace$/);
    assert.equal(await page.locator('input').evaluate(input => input.files.length), filesBefore);
    await page.locator('button').click();
    assert.equal(await page.evaluate(() => window.sends), 2);
    await page.evaluate(value => {const message=document.createElement('div');message.dataset.messageAuthorRole='user';message.innerText=value;document.body.append(message);}, briefing);
    await page.locator('#prompt-textarea').fill('Follow up');
    await page.locator('button').click();
    assert.equal(await page.evaluate(() => window.sends), 3);
    assert.equal(await page.locator('#prompt-textarea').innerText(), 'Follow up');
    await page.evaluate(() => window.luma.configureRole('You are Luma. New preferences.',true));
    assert.equal(await page.locator('#prompt-textarea').evaluate(field => !field.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true}))),true);
    assert.equal(await page.locator('#prompt-textarea').evaluate(field => field.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true}))),true);
    console.log('Chromium: dictation, native File/DataTransfer, draft protection, attachment gating and single submission passed.');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
