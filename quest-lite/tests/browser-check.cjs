const {chromium} = require('playwright');
const fs = require('node:fs');
const assert = require('node:assert/strict');
(async () => {
  const browser = await chromium.launch({executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox']});
  try {
    const page = await browser.newPage();
    await page.route('https://chatgpt.com/**', route => route.fulfill({contentType: 'text/html', body: `
      <form onsubmit="event.preventDefault()"><div id="prompt-textarea" contenteditable="true"></div>
      <input type="file" accept="image/*"><button data-testid="send-button" type="button">Send</button></form>
      <script>window.sends=0;document.querySelector('button').onclick=()=>window.sends++;</script>`}));
    await page.goto('https://chatgpt.com/');
    await page.addScriptTag({content: fs.readFileSync('quest-lite/app/src/main/assets/chatgpt-adapter.js', 'utf8')});
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
    console.log('Chromium: dictation, native File/DataTransfer, draft protection, attachment gating and single submission passed.');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
