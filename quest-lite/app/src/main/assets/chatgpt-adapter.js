/* Account UI adapter. No credentials or private endpoints are accessed. */
(() => {
  if (location.origin !== 'https://chatgpt.com') return;
  if (typeof window.metamanning?.dispose === 'function') window.metamanning.dispose();
  const visible = element => !!element && element.getClientRects().length > 0;
  const editor = () => [...document.querySelectorAll('#prompt-textarea, #mobile-composer-prompt, textarea[aria-label="Chat with ChatGPT"], [contenteditable="true"][role="textbox"]')].find(visible);
  const formFor = () => editor()?.closest('form');
  const filesFor = form => [...(form?.querySelectorAll('input[type="file"]') || [])];
  const signedOutControls = () => [...document.querySelectorAll('button, a')].some(element =>
    visible(element) && /^(?:log in|sign in|sign up for free)$/i.test((element.innerText || '').trim()) && !element.closest('[data-message-author-role]'));
  const loginRequired = form => signedOutControls() || [...(form?.querySelectorAll('button[aria-label]') || [])].some(button => visible(button) && /(?:log|sign) in.*(?:use|upload)|(?:files|attach).*log in/i.test(button.getAttribute('aria-label') || ''));
  const busy = () => !!document.querySelector('[data-testid="stop-button"], button[aria-label="Stop generating"], button[aria-label="Stop streaming"]');
  const text = () => (editor()?.value ?? editor()?.innerText ?? '').trim();
  let pending = null;
  let roleText = '', roleEnabled = false, roleMarker = '';
  const briefed = new Set();
  const conversationKey = () => location.pathname || '/';
  function hasRoleInConversation() {
    if (briefed.has(conversationKey())) return true;
    const messages = [...document.querySelectorAll('[data-message-author-role="user"]')];
    if (roleMarker && messages.some(message => (message.innerText || message.textContent || '').includes(roleMarker))) {
      briefed.add(conversationKey());
      if (briefed.size > 128) briefed.delete(briefed.values().next().value);
      return true;
    }
    return false;
  }
  function roleNeeded() { return roleEnabled && !!roleText && !hasRoleInConversation(); }
  function rolePrompt(value) {
    return roleMarker + '\n' + roleText + '\n[/LUMA_ROLE_V1]\n\nUser request:\n' + (value || 'Help with the attached image.');
  }
  function prepareRoleDraft(force = false) {
    if (!roleText || (!roleEnabled && !force)) return 'role-disabled';
    if (!editor()) return 'missing-composer';
    if (pending || busy()) return 'busy';
    if (text().includes(roleMarker)) return 'already-prepared';
    if (!force && hasRoleInConversation()) return 'already-briefed';
    const value = text();
    const attachment = formFor()?.querySelector('img, [data-testid*="attachment"]');
    if (!value && !attachment && !force) return 'empty';
    const result = write(rolePrompt(value || (force ? 'Please acknowledge this role and ask what you can help with.' : '')));
    if (result === 'ok') { if (typeof console !== 'undefined') console.info('LUMA_ROLE_PREPARED'); return 'prepared'; }
    return result;
  }
  function interceptSend(event) {
    const button = event.target?.closest?.('[data-testid="send-button"], button[aria-label="Send message"], button[aria-label="Send prompt"]');
    if (!button || button.disabled || !roleEnabled) return;
    if (prepareRoleDraft() === 'prepared') { event.preventDefault(); event.stopImmediatePropagation(); }
  }
  function interceptEnter(event) {
    const field = editor();
    if (!field || !roleEnabled || event.key !== 'Enter' || event.shiftKey || event.ctrlKey || event.altKey || event.metaKey || event.isComposing) return;
    if (event.target !== field && !field.contains(event.target)) return;
    if (prepareRoleDraft() === 'prepared') { event.preventDefault(); event.stopImmediatePropagation(); }
  }
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
    configureRole(value, enabled) {
      if (typeof value !== 'string' || value.length > 12000) return 'role-too-long';
      const changed = value !== roleText;
      roleText = value.trim(); roleEnabled = !!enabled && !!roleText;
      let hash = 2166136261;
      for (let index = 0; index < roleText.length; index++) hash = Math.imul(hash ^ roleText.charCodeAt(index), 16777619) >>> 0;
      roleMarker = '[LUMA_ROLE_V1_' + hash.toString(16) + ']';
      if (changed) briefed.clear();
      return roleEnabled ? 'role-enabled' : 'role-disabled';
    },
    prepareRoleDraft(force = false) { return prepareRoleDraft(force); },
    dispose() {
      document.removeEventListener('click', interceptSend, true);
      document.removeEventListener('keydown', interceptEnter, true);
    },
    draft(value) {
      if (!editor()) return 'missing-composer';
      if (pending || busy() || text()) return 'draft-present';
      return write(value);
    },
    latestHtml() {
      if (busy()) return 'reply-still-generating';
      const replies = [...document.querySelectorAll('[data-message-author-role="assistant"]')];
      const last = replies[replies.length - 1];
      if (!last) return 'no-widget-html';
      const blocks = [...last.querySelectorAll('pre code')];
      let source = blocks.filter(block => /(?:language|lang)-html/i.test(block.className || '')).map(block => block.textContent).pop();
      if (!source) source = blocks.map(block => block.textContent).filter(value => /^\s*(?:<!doctype html|<html|<style|<div|<section|<main|<h[1-6])/i.test(value)).pop();
      if (!source) { const match = (last.innerText || '').match(/```(?:html|htm)\s*\n([\s\S]*?)```/i); source = match && match[1]; }
      if (!source) return 'no-widget-html';
      return new TextEncoder().encode(source).length > 128 * 1024 ? 'widget-too-large' : source;
    },
    capabilities() {
      const form = formFor();
      return {composer: !!editor(), uploadsRequireLogin: loginRequired(form), photoInput: filesFor(form).length > 0};
    },
    dictate(value) {
      if (pending) return 'attachment-pending';
      return write([text(), value].filter(Boolean).join(' '));
    },
    startDictation() {
      const button = document.querySelector('[data-testid="composer-dictation-button"], button[aria-label="Start dictation"], button[aria-label*="Dictate"], button[aria-label*="dictate"]');
      if (!button || button.disabled) return 'dictation-unavailable';
      button.click(); return 'dictation-started';
    },
    focus() { const field = editor(); if (!field) return 'missing-composer'; field.focus(); return 'ok'; },
    attach(base64, autoSend, prompt) {
      if (!editor()) return 'missing-composer';
      if (busy() || pending) return 'busy';
      // Never replace a draft or an existing attachment during sampled live mode.
      const form = formFor();
      if (loginRequired(form)) return 'authentication-required';
      if (autoSend && (text() || form?.querySelector('img, [data-testid*="attachment"]'))) return 'draft-present';
      const inputs = filesFor(form);
      const input = inputs.find(i => !i.accept || /image|\.jpg|\.png/.test(i.accept));
      if (!input) return 'missing-file-input';
      const bytes = Uint8Array.from(atob(base64), c => c.charCodeAt(0));
      const file = new File([bytes], 'quest-camera-' + Date.now() + '.jpg', {type: 'image/jpeg'});
      const transfer = new DataTransfer(); transfer.items.add(file);
      input.files = transfer.files;
      input.dispatchEvent(new Event('change', {bubbles: true}));
      if (autoSend) pending = {at: Date.now(), form, url: location.href, prompted: false, prompt: (prompt || 'Describe important changes in this latest camera view. Keep the answer brief.').slice(0, 1000)};
      return autoSend ? 'staged' : 'attached';
    },
    sendPending() {
      if (!pending) return 'none';
      if (Date.now() - pending.at > 45000) { pending = null; return 'upload-timeout'; }
      if (busy()) return 'waiting';
      const form = formFor();
      if (!form || form !== pending.form || location.href !== pending.url) { pending = null; return 'conversation-changed'; }
      if (Date.now() - pending.at < 3000) return 'waiting';
      // An enabled button alone does not prove the image has uploaded.
      if (!form.querySelector('img, [data-testid*="attachment"]') || form.querySelector('[role="progressbar"]')) return 'waiting';
      if (!pending.prompted) {
        if (text()) { pending = null; return 'draft-present'; }
        if (roleNeeded() && !pending.prompt.includes(roleMarker)) pending.prompt = rolePrompt(pending.prompt);
        write(pending.prompt);
        pending.prompted = true;
        return 'waiting';
      }
      if (text() !== pending.prompt) { pending = null; return 'draft-present'; }
      const send = form.querySelector('[data-testid="send-button"], button[aria-label="Send message"], button[aria-label="Send prompt"]');
      if (!send || send.disabled || send.getAttribute('aria-disabled') === 'true') return 'waiting';
      send.click(); pending = null; return 'sent';
    },
    cancel() { pending = null; return 'stopped'; }
  };
  window.luma = window.metamanning;
  document.addEventListener('click', interceptSend, true);
  document.addEventListener('keydown', interceptEnter, true);
})();
