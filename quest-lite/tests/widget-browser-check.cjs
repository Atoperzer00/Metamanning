const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const os=require('node:os');
const {execFileSync}=require('node:child_process');
const root=path.resolve(__dirname,'..');
const temporary=fs.mkdtempSync(path.join(os.tmpdir(),'metamanning-widget-'));
execFileSync(process.env.JAVAC || 'javac',['-d',temporary,path.join(root,'app/src/main/java/com/metamanning/questlite/WidgetDocument.java'),path.join(root,'tools/RenderWidgetFixture.java')]);
function render(html,network=false){const file=path.join(temporary,'widget.html');fs.writeFileSync(file,html);return execFileSync(process.env.JAVA || 'java',['-cp',temporary,'com.metamanning.questlite.RenderWidgetFixture',file,String(network)],{encoding:'utf8',maxBuffer:2*1024*1024});}
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||(fs.existsSync('/usr/bin/chromium')?'/usr/bin/chromium':undefined),headless:true,args:['--no-sandbox'],proxy:process.env.HTTPS_PROXY?{server:process.env.HTTPS_PROXY}:undefined});
 try {
  let document='',remoteRequests=0;
  const context=await browser.newContext();
  const page=await context.newPage();
  page.setDefaultTimeout(8000);
  const errors=[]; page.on('pageerror',error=>errors.push(error.message));
  await context.route('https://example.com/**',route=>route.fulfill({contentType:'text/html',body:document}));
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
  for(const template of templates){
   document=render(template.html);await page.goto('https://example.com/template');
   const widget=page.frameLocator('iframe');await widget.locator('h1').waitFor();
   if(template.title==='Calculator'){await widget.locator('#a').fill('7');await widget.locator('#b').fill('6');await widget.locator('#operation').selectOption('×');assert.equal(await widget.locator('#answer').textContent(),'42');}
   if(template.title==='Checklist'){await widget.locator('#task').fill('Spatial checklist');await widget.getByRole('button',{name:'Add',exact:true}).click();assert.match(await widget.locator('#list').textContent(),/Spatial checklist/);}
   if(template.title==='Notes'){await widget.locator('#note').fill('A room note');await page.waitForTimeout(400);assert.equal(await widget.locator('#status').textContent(),'Saved locally');}
   if(template.title==='Focus timer'){await widget.locator('#start').click();assert.match(await widget.locator('#readout').textContent(),/^24:|^25:/);await widget.locator('#stop').click();assert.equal(await widget.locator('#readout').textContent(),'25:00');}
  }
  assert.deepEqual(errors,[],'Widgets must not produce unhandled JavaScript errors');
  console.log('Actual WidgetDocument renderer: sandbox isolation, blocked default network, explicit HTTPS mode and all five interactive templates passed in Chromium. Native bridge/device persistence is separately gated.');
 }finally{await browser.close();fs.rmSync(temporary,{recursive:true,force:true});}
})().catch(error=>{console.error(error);process.exitCode=1;});
