(() => {
  const editor = document.querySelector('[data-content-editor]');
  const preview = document.querySelector('[data-editor-preview]');
  const toggle = document.querySelector('[data-preview-toggle]');
  const type = document.querySelector('[data-content-type]');
  const mediaUpload = document.querySelector('[data-media-upload]');
  const formElement = document.querySelector('#article-form');
  const saveState = document.querySelector('[data-save-state]');
  const articleId = formElement?.querySelector('[name="id"]')?.value;
  const markdownTools = document.querySelector('[data-markdown-tools]');
  if (!editor || !preview) return;

  let timer;
  let autoSaveTimer;
  let changeVersion = 0;
  let previewVisible = false;
  const csrf = document.querySelector('meta[name="_csrf"]')?.content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;

  async function updatePreview() {
    if (!previewVisible) return;
    const headers = { 'Content-Type': 'application/json' };
    if (csrf && csrfHeader) headers[csrfHeader] = csrf;
    const response = await fetch('/admin/api/preview', {
      method: 'POST', headers,
      body: JSON.stringify({ contentType: type.value, content: editor.value })
    });
    if (!response.ok) {
      preview.textContent = '预览失败，请保存后重试。';
      return;
    }
    preview.innerHTML = await response.text();
    if (window.enhanceArticle) window.enhanceArticle(preview);
  }

  function schedule() {
    clearTimeout(timer);
    timer = setTimeout(updatePreview, 500);
    changeVersion += 1;
    saveState.textContent = articleId ? '有未保存修改' : '首次请手动保存';
    if (articleId) {
      clearTimeout(autoSaveTimer);
      autoSaveTimer = setTimeout(autoSave, 5000);
    }
  }
  formElement.querySelectorAll('input:not([type="file"]), textarea, select').forEach((field) => {
    field.addEventListener('input', schedule);
    field.addEventListener('change', schedule);
  });
  type.addEventListener('change', updateToolVisibility);
  toggle?.addEventListener('click', () => {
    previewVisible = !previewVisible;
    preview.hidden = !previewVisible;
    editor.hidden = previewVisible;
    toggle.textContent = previewVisible ? '返回编辑' : '切换预览';
    updatePreview();
  });

  async function autoSave() {
    const savingVersion = changeVersion;
    saveState.textContent = '自动保存中…';
    try {
      const response = await fetch(formElement.action, { method: 'POST', body: new FormData(formElement) });
      const saved = response.ok && response.redirected && /\/admin\/articles\/\d+$/.test(new URL(response.url).pathname);
      if (!saved) throw new Error('save rejected');
      saveState.textContent = savingVersion === changeVersion ? '已自动保存' : '有未保存修改';
      if (savingVersion !== changeVersion) {
        clearTimeout(autoSaveTimer);
        autoSaveTimer = setTimeout(autoSave, 5000);
      }
    } catch (_) {
      saveState.textContent = '自动保存失败，请手动保存';
    }
  }

  async function uploadFile(file) {
    if (!file) return;
    const state = document.querySelector('[data-upload-state]');
    state.textContent = '正在上传…';
    const form = new FormData();
    form.append('file', file);
    const headers = {};
    if (csrf && csrfHeader) headers[csrfHeader] = csrf;
    const response = await fetch('/admin/api/media', { method: 'POST', headers, body: form });
    if (!response.ok) {
      state.textContent = '上传失败，请到资源页重试';
      return false;
    }
    const asset = await response.json();
    const label = asset.name.replace(/[\[\]]/g, '');
    let insertion;
    if (type.value === 'MARKDOWN') insertion = asset.mimeType.startsWith('image/') ? `\n![${label}](${asset.url})\n` : `\n[${label}](${asset.url})\n`;
    else if (type.value === 'HTML') insertion = asset.mimeType.startsWith('image/') ? `\n<img src="${asset.url}" alt="${label}">\n` : `\n<a href="${asset.url}">${label}</a>\n`;
    else insertion = `\n${asset.url}\n`;
    const start = editor.selectionStart;
    editor.setRangeText(insertion, start, editor.selectionEnd, 'end');
    editor.dispatchEvent(new Event('input'));
    state.textContent = '上传成功，已插入正文';
    if (mediaUpload) mediaUpload.value = '';
    return true;
  }

  mediaUpload?.addEventListener('change', () => uploadFile(mediaUpload.files?.[0]));
  editor.addEventListener('paste', (event) => {
    const file = [...(event.clipboardData?.files || [])].find((item) => item.type.startsWith('image/'));
    if (file) { event.preventDefault(); uploadFile(file); }
  });
  editor.addEventListener('dragover', (event) => event.preventDefault());
  editor.addEventListener('drop', (event) => {
    const file = [...(event.dataTransfer?.files || [])][0];
    if (file) { event.preventDefault(); uploadFile(file); }
  });

  function updateToolVisibility() {
    if (markdownTools) markdownTools.hidden = type.value !== 'MARKDOWN';
  }

  function insertText(text) {
    const start = editor.selectionStart;
    editor.setRangeText(text, start, editor.selectionEnd, 'end');
    editor.focus();
    editor.dispatchEvent(new Event('input'));
  }

  markdownTools?.addEventListener('click', (event) => {
    const button = event.target.closest('button');
    if (!button) return;
    const selected = editor.value.slice(editor.selectionStart, editor.selectionEnd);
    if (button.dataset.wrap) insertText(`${button.dataset.wrap}${selected || '文字'}${button.dataset.wrap}`);
    else if (button.dataset.prefix) insertText(selected.split('\n').map((line) => button.dataset.prefix + line).join('\n'));
    else if (button.dataset.block) insertText(`\n${button.dataset.block}\n`);
    else if (button.dataset.insert) insertText(`${button.dataset.insert} `);
    else if (button.hasAttribute('data-wrap-link')) insertText(`[${selected || '链接文字'}](https://)`);
  });
  updateToolVisibility();
})();
