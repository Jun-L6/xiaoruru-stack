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
  const contentForm = document.querySelector('[data-content-form]');
  const titleInput = document.querySelector('[data-title-input]');
  const titleLabel = document.querySelector('[data-title-label]');
  const titleHint = document.querySelector('[data-title-hint]');
  const excerptFields = document.querySelector('[data-excerpt-fields]');
  const classificationLock = document.querySelector('[data-classification-lock]');
  const categorySelect = formElement?.querySelector('[name="categoryId"]');
  if (!editor || !preview) return;

  let timer;
  let autoSaveTimer;
  // 请求发出后用户可能继续编辑；版本号用来避免把新修改误标为“已保存”。
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
  contentForm?.addEventListener('change', () => updateContentFormFields(true));
  categorySelect?.addEventListener('change', () => {
    if (classificationLock) classificationLock.checked = true;
  });
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
    // 服务器只返回站内资源 URL，编辑器按正文格式生成最小可用引用。
    let insertion;
    if (type.value === 'MARKDOWN') insertion = asset.mimeType.startsWith('image/') ? `\n![${label}](${asset.url})\n` : `\n[${label}](${asset.url})\n`;
    else if (type.value === 'HTML') insertion = asset.mimeType.startsWith('image/') ? `\n<img src="${asset.url}" alt="${label}">\n` : `\n<a href="${asset.url}">${label}</a>\n`;
    else insertion = `\n${asset.url}\n`;
    const start = editor.selectionStart;
    editor.setRangeText(insertion, start, editor.selectionEnd, 'end');
    editor.dispatchEvent(new Event('input'));
    state.textContent = '上传成功，已插入正文';
    if (mediaUpload) {
      mediaUpload.value = '';
      mediaUpload.dispatchEvent(new Event('change', { bubbles: true }));
    }
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

  function updateContentFormFields(userInitiated = false) {
    if (!contentForm || !titleInput) return;
    const automatic = contentForm.value === '';
    // 空值是编辑意图“AI 自动判断”；resolvedForm 是当前已持久化形态，只用于预览字段。
    const effectiveForm = automatic ? contentForm.dataset.resolvedForm : contentForm.value;
    const optionalTitle = automatic || effectiveForm === 'MOMENT' || effectiveForm === 'EXCERPT';
    titleInput.required = !optionalTitle;
    titleInput.placeholder = automatic ? 'AI 自动判断时可留空'
      : optionalTitle ? '可留空，系统会生成内部标题' : '请填写文章标题';
    if (titleLabel) titleLabel.textContent = optionalTitle ? '标题（可选）' : '标题';
    if (titleHint) {
      titleHint.textContent = automatic
        ? '留空后会从正文生成内部标题；AI 判断为动态或摘录时，前台不显示该标题。'
        : optionalTitle
          ? '留空后只生成后台、搜索和分享所需的内部标题，正文页不会显示。'
          : '长文、随笔和笔记需要标题。';
    }
    if (excerptFields) excerptFields.hidden = effectiveForm !== 'EXCERPT';
    const hint = document.querySelector('[data-content-form-hint]');
    if (hint) {
      hint.textContent = automatic
        ? 'AI 会同时判断内容形态、分类和标签，不会锁定结果'
        : '手动选择后会锁定当前分类结果；切回自动可重新交给 AI';
    }
    if (classificationLock) {
      // 手动形态必然锁定，不让表单再提交互相矛盾的组合。
      classificationLock.disabled = !automatic;
      if (userInitiated) classificationLock.checked = !automatic;
    }
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
  updateContentFormFields();
})();
