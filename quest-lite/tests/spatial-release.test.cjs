const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const manifest = fs.readFileSync(path.join(__dirname, '../spatial/src/main/AndroidManifest.xml'), 'utf8');

// Regression: a compilable APK crashed on Horizon when external panel launch metadata was missing.
for (const name of ['MainActivity', 'BrowserActivity', 'WidgetActivity']) {
  test(`${name} can be launched on a Horizon panel display`, () => {
    const activity = manifest.match(new RegExp(`<activity\\s+android:name="\\.${name}"[^>]*>[\\s\\S]*?</activity>`));
    assert.ok(activity, `${name} must declare a complete activity with a panel intent filter`);
    assert.match(activity[0].split('>')[0], /android:exported="true"/);
    assert.match(activity[0], /android.intent.action.MAIN/);
    assert.match(activity[0], /com.oculus.intent.category.2D/);
  });
}
