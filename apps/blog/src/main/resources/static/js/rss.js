document.addEventListener('DOMContentLoaded', () => {
  const input = document.querySelector('[data-rss-url]');
  const button = document.querySelector('[data-copy-rss]');
  const status = document.querySelector('[data-copy-status]');
  if (!input || !button || !status) return;

  button.addEventListener('click', async () => {
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(input.value);
      } else {
        input.select();
        if (!document.execCommand('copy')) throw new Error('copy failed');
      }
      button.textContent = '已复制';
      status.textContent = '订阅地址已复制，可以粘贴到你的 RSS 阅读器了。';
    } catch (_) {
      input.focus();
      input.select();
      button.textContent = '请手动复制';
      status.textContent = '浏览器没有授予剪贴板权限，地址已经选中，请手动复制。';
    }
  });
});
