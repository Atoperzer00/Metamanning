// Readiness probe against the public ChatGPT UI. It never submits a message or image.
const {chromium} = require('playwright');
const fs = require('node:fs');
const assert = require('node:assert/strict');
(async () => {
 const browser = await chromium.launch({executablePath:process.env.CHROMIUM_PATH || (fs.existsSync('/usr/bin/chromium') ? '/usr/bin/chromium' : undefined),headless:true,args:['--no-sandbox'],proxy:process.env.HTTPS_PROXY?{server:process.env.HTTPS_PROXY}:undefined});
 try {
  const page = await browser.newPage();
  const response = await page.goto('https://chatgpt.com/', {waitUntil:'domcontentloaded',timeout:45000});
  await page.locator('#prompt-textarea, #mobile-composer-prompt, textarea[aria-label="Chat with ChatGPT"]').first().waitFor({state:'visible',timeout:15000});
  await page.addScriptTag({content:fs.readFileSync(require('node:path').join(__dirname, '../app/src/main/assets/chatgpt-adapter.js'),'utf8')});
  const capabilities = await page.evaluate(() => window.metamanning.capabilities());
  assert.equal(capabilities.composer, true);
  assert.equal(await page.evaluate(() => window.metamanning.dictate('Composer compatibility check — not submitted.')), 'ok');
  assert.equal(capabilities.uploadsRequireLogin, true, 'Fresh logged-out profile must be detected before touching a file input.');
  await page.waitForTimeout(200);
  const inserted = await page.locator('#prompt-textarea, #mobile-composer-prompt, textarea[aria-label="Chat with ChatGPT"]').filter({visible:true}).first().evaluate(field => field.value ?? field.innerText);
  assert.equal(inserted, 'Composer compatibility check — not submitted.');
  const result = await page.evaluate(() => window.metamanning.attach('eA==', false));
  assert.equal(result, 'authentication-required', 'This probe is intended for a fresh logged-out profile; never upload in this test.');
  await page.locator('#prompt-textarea, #mobile-composer-prompt, textarea[aria-label="Chat with ChatGPT"]').filter({visible:true}).first().fill('');
  console.log(JSON.stringify({httpStatus:response.status(),capabilities,dictationInsertion:'passed',loggedOutUploadGate:result,signedInAccountVerified:false,messagesSubmitted:0,imagesSubmitted:0}));
 } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
