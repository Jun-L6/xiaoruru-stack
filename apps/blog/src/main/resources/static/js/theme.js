(() => {
  const root = document.documentElement;
  const preference = matchMedia('(prefers-color-scheme: dark)');
  let stored;
  try { stored = localStorage.getItem('rurublog-theme') || localStorage.getItem('paper-blog-theme'); } catch (_) {}
  let manual = stored === 'light' || stored === 'dark';
  function apply(theme) {
    root.dataset.theme = theme;
    document.querySelectorAll('[data-theme-toggle]').forEach(button => button.setAttribute('aria-pressed', String(theme === 'dark')));
    document.dispatchEvent(new CustomEvent('rurublog:theme', { detail: theme }));
  }
  apply(manual ? stored : (preference.matches ? 'dark' : 'light'));
  preference.addEventListener('change', event => { if (!manual) apply(event.matches ? 'dark' : 'light'); });
  document.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('[data-theme-toggle]').forEach((button) => {
      button.setAttribute('aria-pressed', String(root.dataset.theme === 'dark'));
      button.addEventListener('click', () => {
        const current = root.dataset.theme || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
        const next = current === 'dark' ? 'light' : 'dark';
        manual = true;
        apply(next);
        try { localStorage.setItem('rurublog-theme', next); } catch (_) {}
      });
    });
    const menu = document.querySelector('[data-admin-menu]');
    menu?.addEventListener('click', () => {
      const open = menu.getAttribute('aria-expanded') !== 'true';
      menu.setAttribute('aria-expanded', String(open));
      menu.setAttribute('aria-label', open ? '收起后台导航' : '展开后台导航');
    });
    document.addEventListener('keydown', event => {
      if (event.key === 'Escape' && menu?.getAttribute('aria-expanded') === 'true') {
        menu.setAttribute('aria-expanded', 'false');
        menu.setAttribute('aria-label', '展开后台导航');
        menu.focus();
      }
    });
  });
})();
