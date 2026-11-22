const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const os=require('node:os');
const {execFileSync}=require('node:child_process');
const root=path.resolve(__dirname,'..');
const temporary=fs.mkdtempSync(path.join(os.tmpdir(),'metamanning-widget-'));
execFileSync(process.env.JAVAC || 'javac',['-d',temporary,path.join(root,'app/src/main/java/com/metamanning/questlite/WidgetDocument.java'),path.join(root,'tools/RenderWidgetFixture.java')]);
function render(html, network = false) {
 const file = path.join(temporary, 'widget.html');
 fs.writeFileSync(file, html);
 // The fixture prints to stdout, whose charset on Windows JDK 17 may differ from UTF-8.
 return execFileSync(process.env.JAVA || 'java', [
  '-Dsun.stdout.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', temporary,
  'com.metamanning.questlite.RenderWidgetFixture', file, String(network)
 ], {encoding:'utf8', maxBuffer:2*1024*1024});
}

function renderWithState(html, state) {
 const bridge = `<script>
  (() => {
   let stored = ${JSON.stringify(state).replace(/</g, '\\u003c')};
   window.LumaState = {
    loadState: () => stored,
    saveState: value => {
     if (new TextEncoder().encode(value).length > 32768) return false;
     stored = value;
     return true;
    }
   };
  })();
 </script>`;
 // Android injects the bridge before the SDK runs. Emulate that in the same sandbox.
 const encoded = bridge.replace(/&/g, '&amp;').replace(/"/g, '&quot;')
  .replace(/</g, '&lt;').replace(/>/g, '&gt;');
 return render(html).replace('&lt;script&gt;', () => encoded + '&lt;script&gt;');
}

async function checkMindInbox(widget, remount) {
 const thought = '<b>Call Sam</b>';
 const capture = widget.getByRole('textbox', {name:'Quick capture'});
 const toggle = widget.getByRole('button', {name:'Next action for ' + thought});
 const list = widget.locator('#thoughts');

 await capture.fill('   ');
 await capture.press('Enter');
 assert.equal(await list.locator('li').count(), 0);
 await capture.fill('  ' + thought + '  ');
 await capture.press('Enter');
 assert.equal(await capture.inputValue(), '');
 assert.equal(await list.locator('span').textContent(), thought);
 assert.equal(await list.locator('b').count(), 0);
 await toggle.click();
 assert.equal(await toggle.getAttribute('aria-pressed'), 'true');
 await toggle.click();
 assert.equal(await toggle.getAttribute('aria-pressed'), 'false');
 await toggle.click();

 await remount();
 assert.equal(await list.locator('span').textContent(), thought);
 assert.equal(await toggle.getAttribute('aria-pressed'), 'true');
 await widget.getByRole('button', {name:'Remove ' + thought}).click();
 await remount();
 assert.equal(await list.locator('li').count(), 0);
 await checkCapacity(widget, capture, list, 24, 180, 'Inbox is full.');
}

async function checkToday(widget, remount) {
 const priority = widget.getByRole('textbox', {name:'One priority'});
 const step = widget.getByRole('textbox', {name:'New step'});
 const add = widget.getByRole('button', {name:'Add step'});
 const first = widget.getByRole('checkbox', {name:'Open the document'});
 const second = widget.getByRole('checkbox', {name:'<b>Write one line</b>'});

 await priority.fill('Sort the mail');
 await priority.fill('Draft the plan');
 await step.fill('   ');
 await add.click();
 assert.equal(await widget.getByRole('checkbox').count(), 0);
 await step.fill('Open the document');
 await add.click();
 await step.fill('<b>Write one line</b>');
 await step.press('Enter');
 await first.check();

 await remount();
 assert.equal(await priority.inputValue(), 'Draft the plan');
 assert.deepEqual(await widget.locator('#steps span').allTextContents(), ['Open the document', '<b>Write one line</b>']);
 assert.equal(await first.isChecked(), true);
 assert.equal(await second.isChecked(), false);
 assert.equal(await widget.locator('#steps b').count(), 0);
 await first.uncheck();
 await widget.getByRole('button', {name:'Remove <b>Write one line</b>'}).click();
 await priority.clear();
 await remount();
 assert.equal(await priority.inputValue(), '');
 assert.equal(await first.isChecked(), false);
 assert.equal(await second.count(), 0);
 assert.equal(await priority.getAttribute('maxlength'), '180');
 await priority.fill('\u0001'.repeat(180));
 await widget.getByRole('button', {name:'Remove Open the document'}).click();
 await checkCapacity(widget, step, widget.locator('#steps'), 12, 100, 'Step list is full.');
}

async function checkCapacity(widget, input, list, count, length, fullMessage) {
 assert.equal(await input.getAttribute('maxlength'), String(length));
 // JSON escapes these control characters as six bytes per input code unit.
 const longestEscapedInput = '\u0001'.repeat(length);
 for (let i = 0; i < count; i++) {
  await input.fill(longestEscapedInput);
  await input.press('Enter');
 }
 assert.equal(await list.locator('li').count(), count);
 const stored = await input.evaluate(() => window.LumaState.loadState());
 assert.ok(Buffer.byteLength(stored, 'utf8') <= 32768);
 await input.fill('One more');
 await input.press('Enter');
 assert.equal(await list.locator('li').count(), count);
 assert.equal(await input.inputValue(), 'One more');
 assert.ok((await widget.getByRole('status').textContent()).startsWith(fullMessage));
}

async function capturePreviews(page, loadTemplate, directory) {
 const samples = [
  ['Mind Inbox', 'mind-inbox.png', {
   mindInbox: [
    {text:'Ask Sam about Friday', next:true},
    {text:'Look for the book I wanted to borrow', next:false},
    {text:'Idea for a quieter morning routine', next:false}
   ]
  }, '#thoughts li'],
  ['Today', 'today.png', {
   today: {
    priority:'Send the project update',
    steps:[
     {text:'Open the draft', done:true},
     {text:'Write one sentence about what changed', done:false},
     {text:'Read it once before sending', done:false}
    ]
   }
  }, '#steps li']
 ];
 fs.mkdirSync(directory, {recursive:true});
 await page.setViewportSize({width:1000, height:820});
 for (const [title, filename, state, rows] of samples) {
  await loadTemplate(title, JSON.stringify(state));
  const widget = page.frameLocator('iframe');
  await widget.locator(rows).last().waitFor();
  assert.equal(await widget.locator(rows).count(), 3);
  await page.screenshot({path:path.join(directory, filename)});
 }
 console.log('Widget previews written to ' + path.resolve(directory));
}

(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||(fs.existsSync('/usr/bin/chromium')?'/usr/bin/chromium':undefined),headless:true,args:['--no-sandbox'],proxy:process.env.HTTPS_PROXY?{server:process.env.HTTPS_PROXY}:undefined});
 try {
  let document='',remoteRequests=0;
  const context=await browser.newContext();
  const page=await context.newPage();
  page.setDefaultTimeout(8000);
  const errors=[]; page.on('pageerror',error=>errors.push(error.message));
  await context.route('https://example.com/**',route=>route.fulfill({contentType:'text/html; charset=utf-8',body:document}));
  await context.route('https://example.org/**',route=>{remoteRequests++;return route.fulfill({contentType:'application/javascript',headers:{'access-control-allow-origin':'*'},body:"document.getElementById('result').textContent='network allowed';"});});
  document=render(`<h1 id="result">Loading</h1><script>let isolation;try{parent.document.body.dataset.pwned='yes';isolation='failed'}catch(e){isolation='isolated'}document.getElementById('result').textContent=isolation;fetch('https://example.org/data').then(()=>document.body.dataset.network='allowed').catch(()=>document.body.dataset.network='blocked');try{localStorage.setItem('account','stolen')}catch(e){document.body.dataset.storage='isolated'}</script>`);
  await page.goto('https://example.com/offline');
  const frame=page.frameLocator('iframe');
  await frame.locator('#result').waitFor();
  assert.equal(await frame.locator('#result').textContent(),'isolated');
  await page.waitForTimeout(100);
  assert.equal(await frame.locator('body').getAttribute('data-network'),'blocked');
  assert.equal(await frame.locator('body').getAttribute('data-storage'),'isolated');
  assert.equal(remoteRequests,0);
  assert.equal(await page.locator('body').getAttribute('data-pwned'),null);
  console.log('Widget offline isolation passed');
  document=render(`<p id="result"></p><script src="https://cdn.jsdelivr.net/npm/dayjs@1.11.13/dayjs.min.js"></script><script>document.getElementById('result').textContent=typeof dayjs==='function'?'network allowed':'remote failed';</script>`,true);
  await page.goto('https://example.com/online');
  assert.equal(await page.frameLocator('iframe').locator('#result').textContent(),'network allowed');
  console.log('Widget explicit HTTPS mode loaded a real public CDN script');
  const templates=JSON.parse(fs.readFileSync(path.join(root,'app/src/main/assets/widget-templates.json'),'utf8'));
  const mindKitChecks = new Map([['Mind Inbox', checkMindInbox], ['Today', checkToday]]);
  for (const title of mindKitChecks.keys()) {
   assert.equal(templates.filter(template => template.title === title).length, 1, title + ' must be available exactly once');
  }
  const offlineRequests = [];
  context.on('request', request => {
   if (!request.isNavigationRequest() || request.url() !== 'https://example.com/template') {
    offlineRequests.push(request.url());
   }
  });
  for(const template of templates){
   const checkMindKit = mindKitChecks.get(template.title);
   document = checkMindKit ? renderWithState(template.html, '{}') : render(template.html);
   await page.goto('https://example.com/template');
   const widget=page.frameLocator('iframe');await widget.locator('h1').waitFor();
   if(template.title==='Calculator'){await widget.locator('#a').fill('7');await widget.locator('#b').fill('6');await widget.locator('#operation').selectOption('×');assert.equal(await widget.locator('#answer').textContent(),'42');}
   if(template.title==='Checklist'){await widget.locator('#task').fill('Spatial checklist');await widget.getByRole('button',{name:'Add',exact:true}).click();assert.match(await widget.locator('#list').textContent(),/Spatial checklist/);}
   if(template.title==='Notes'){await widget.locator('#note').fill('A room note');await page.waitForTimeout(400);assert.equal(await widget.locator('#status').textContent(),'Saved locally');}
   if(template.title==='Focus timer'){await widget.locator('#start').click();assert.match(await widget.locator('#readout').textContent(),/^24:|^25:/);await widget.locator('#stop').click();assert.equal(await widget.locator('#readout').textContent(),'25:00');}
   if (checkMindKit) {
    assert.equal(await widget.locator('h1').textContent(), template.title);
    await checkMindKit(widget, async () => {
     const stored = await widget.locator('h1').evaluate(() => window.LumaState.loadState());
     assert.ok(Buffer.byteLength(stored, 'utf8') <= 32768);
     document = renderWithState(template.html, stored);
     await page.reload();
    });
   }
  }
  const previewDir = process.env.LUMA_PREVIEW_DIR;
  if (previewDir) {
   await capturePreviews(page, async (title, state) => {
    document = renderWithState(templates.find(template => template.title === title).html, state);
    await page.goto('https://example.com/template');
   }, previewDir);
  }
  assert.deepEqual(offlineRequests, [], 'Built-in templates must make no network requests');
  assert.deepEqual(errors,[],'Widgets must not produce unhandled JavaScript errors');
  console.log('All built-in templates passed in Chromium. Mind Inbox and Today restored state through the test bridge after reload.');
 }finally{await browser.close();fs.rmSync(temporary,{recursive:true,force:true});}
})().catch(error=>{console.error(error);process.exitCode=1;});
