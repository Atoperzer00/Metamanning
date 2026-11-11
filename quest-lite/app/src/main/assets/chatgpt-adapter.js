/* Account UI adapter. No credentials or private endpoints are accessed. */
(() => {
  if (location.origin !== 'https://chatgpt.com') return;
  const editor = () => document.querySelector('#prompt-textarea');
  const busy = () => !!document.querySelector('[data-testid="stop-button"]');
  const text = () => (editor()?.value ?? editor()?.innerText ?? '').trim();
  let pending = null;
  function write(value) {
    const field = editor();
    if (!field) return 'missing-composer';
    field.focus();
    if (field.tagName === 'TEXTAREA') {
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(field, value);
      field.dispatchEvent(new Event('input', {bubbles: true}));
    } else {
      field.textContent = value;
      field.dispatchEvent(new InputEvent('input', {bubbles: true, inputType: 'insertText', data: value}));
    }
    return 'ok';
  }
  window.metamanning = {
    dictate(value) {
      if (pending) return 'attachment-pending';
      return write([text(), value].filter(Boolean).join(' '));
    },
    startDictation() {
      const button = document.querySelector('[data-testid="composer-dictation-button"], button[aria-label*="Dictate"], button[aria-label*="dictate"]');
      if (!button || button.disabled) return 'dictation-unavailable';
      button.click(); return 'dictation-started';
    },
    focus() { const field = editor(); if (!field) return 'missing-composer'; field.focus(); return 'ok'; },
    attach(base64, autoSend) {
      if (!editor()) return 'missing-composer';
      if (busy() || pending) return 'busy';
      // Never replace a draft or an existing attachment during sampled live mode.
      const form = editor().closest('form');
      if (autoSend && (text() || form?.querySelector('img, [data-testid*="attachment"]'))) return 'draft-present';
      const inputs = [...document.querySelectorAll('input[type="file"]')];
      const input = inputs.find(i => !i.accept || /image|\.jpg|\.png/.test(i.accept));
      if (!input) return 'missing-file-input';
      const bytes = Uint8Array.from(atob(base64), c => c.charCodeAt(0));
      const file = new File([bytes], 'quest-camera-' + Date.now() + '.jpg', {type: 'image/jpeg'});
      const transfer = new DataTransfer(); transfer.items.add(file);
      input.files = transfer.files;
      input.dispatchEvent(new Event('change', {bubbles: true}));
      if (autoSend) pending = {at: Date.now(), form, url: location.href, prompted: false};
      return autoSend ? 'staged' : 'attached';
    },
    sendPending() {
      if (!pending) return 'none';
      if (Date.now() - pending.at > 45000) { pending = null; return 'upload-timeout'; }
      if (busy()) return 'waiting';
      const form = editor()?.closest('form');
      if (!form || form !== pending.form || location.href !== pending.url) { pending = null; return 'conversation-changed'; }
      if (Date.now() - pending.at < 3000) return 'waiting';
      // An enabled button alone does not prove the image has uploaded.
      if (!form.querySelector('img, [data-testid*="attachment"]') || form.querySelector('[role="progressbar"]')) return 'waiting';
      if (!pending.prompted) {
        if (text()) { pending = null; return 'draft-present'; }
        pending.prompt = 'Describe important changes in this latest camera view. Keep the answer brief.';
        write(pending.prompt);
        pending.prompted = true;
        return 'waiting';
      }
      if (text() !== pending.prompt) { pending = null; return 'draft-present'; }
      const send = form.querySelector('[data-testid="send-button"]');
      if (!send || send.disabled || send.getAttribute('aria-disabled') === 'true') return 'waiting';
      send.click(); pending = null; return 'sent';
    },
    cancel() { pending = null; return 'stopped'; }
  };
})();
