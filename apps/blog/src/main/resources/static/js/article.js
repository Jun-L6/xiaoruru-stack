(() => {
  const articleScript = document.currentScript;
  const mermaidSource = articleScript?.dataset.mermaidSrc || '';
  const diagramSources = new WeakMap();
  let diagramQueue = Promise.resolve();
  let mermaidLoader;

  function loadMermaid() {
    if (window.mermaid) return Promise.resolve(window.mermaid);
    if (mermaidLoader) return mermaidLoader;
    if (!mermaidSource) return Promise.reject(new Error('Mermaid resource is not configured'));

    mermaidLoader = new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = mermaidSource;
      script.async = true;
      script.dataset.rurublogMermaid = 'true';
      script.addEventListener('load', () => {
        if (window.mermaid) resolve(window.mermaid);
        else reject(new Error('Mermaid did not initialize'));
      }, { once: true });
      script.addEventListener('error', () => reject(new Error('Mermaid failed to load')), { once: true });
      document.head.append(script);
    }).catch((error) => {
      mermaidLoader = undefined;
      throw error;
    });
    return mermaidLoader;
  }

  function renderDiagrams(root = document) {
    diagramQueue = diagramQueue.catch(() => {}).then(async () => {
      const nodes = [...root.querySelectorAll('.mermaid')].filter(node => node.isConnected && diagramSources.has(node));
      if (!nodes.length) return;
      try {
        await loadMermaid();
        nodes.forEach(node => {
          node.classList.remove('mermaid-load-error');
          node.removeAttribute('role');
        });
      } catch (_) {
        nodes.forEach(node => {
          node.classList.add('mermaid-load-error');
          node.setAttribute('role', 'alert');
        });
        return;
      }
      for (const node of nodes) {
        node.removeAttribute('data-processed');
        node.textContent = diagramSources.get(node);
      }
      const dark = document.documentElement.dataset.theme === 'dark';
      window.mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: 'base', themeVariables: {
        darkMode: dark, primaryColor: dark ? '#233c30' : '#e6f4ee', primaryTextColor: dark ? '#e5eee8' : '#192823',
        primaryBorderColor: dark ? '#8adcb3' : '#087e63', lineColor: dark ? '#a1b2a8' : '#687870',
        secondaryColor: dark ? '#1e2c24' : '#eff4f1', tertiaryColor: dark ? '#18221d' : '#ffffff',
        fontFamily: '-apple-system, BlinkMacSystemFont, Segoe UI, PingFang SC, sans-serif'
      } });
      try {
        await window.mermaid.run({ nodes });
        nodes.forEach(node => {
          const svg = node.querySelector('svg');
          const width = svg?.viewBox?.baseVal?.width;
          if (width) svg.style.minWidth = `${Math.min(width, 560)}px`;
        });
      } catch (_) { /* Keep Mermaid's syntax error visible. */ }
    });
  }
  document.addEventListener('rurublog:theme', () => {
    if (window.mermaid) renderDiagrams();
  });
  function enhance(root = document) {
    root.querySelectorAll('a[href]').forEach((link) => {
      try {
        const url = new URL(link.getAttribute('href'), window.location.href);
        if ((url.protocol === 'http:' || url.protocol === 'https:') && url.origin !== window.location.origin) {
          link.rel = 'noopener noreferrer nofollow';
        }
      } catch (_) {
        // The server-side sanitizer already removes unsafe URL protocols.
      }
    });

    root.querySelectorAll('pre > code:not(.language-mermaid)').forEach((code) => {
      if (window.hljs && !code.dataset.highlighted) window.hljs.highlightElement(code);
      const pre = code.parentElement;
      pre.dataset.language = [...code.classList].find(name => name.startsWith('language-'))?.slice(9) || 'code';
      if (pre.querySelector('.copy-code')) return;
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'copy-code';
      button.textContent = '复制';
      button.addEventListener('click', async () => {
        try {
          await navigator.clipboard.writeText(code.textContent);
          button.textContent = '已复制';
        } catch (_) { button.textContent = '请手动复制'; }
        setTimeout(() => button.textContent = '复制', 1200);
      });
      pre.append(button);
    });

    const diagrams = root.querySelectorAll('pre > code.language-mermaid');
    diagrams.forEach((code) => {
      const host = document.createElement('div');
      host.className = 'mermaid';
      host.textContent = code.textContent;
      diagramSources.set(host, code.textContent);
      code.parentElement.replaceWith(host);
    });
    if (diagrams.length) {
      renderDiagrams(root);
    }
    if (window.renderMathInElement) {
      window.renderMathInElement(root, {
        throwOnError: false,
        delimiters: [
          { left: '$$', right: '$$', display: true },
          { left: '\\[', right: '\\]', display: true },
          { left: '\\(', right: '\\)', display: false },
          { left: '$', right: '$', display: false }
        ]
      });
    }

    if (root.matches?.('[data-article-content]')) buildTableOfContents(root);
  }


  function buildTableOfContents(article) {
    const toc = document.querySelector('[data-article-toc]');
    const rail = document.querySelector('[data-article-toc-rail]');
    const toggle = document.querySelector('[data-article-toc-toggle]');
    if (!toc || !rail || !toggle) return;
    if (rail.dataset.tocInitialized === 'true') return;
    rail.dataset.tocInitialized = 'true';
    const headings = [...article.querySelectorAll('h2, h3')];
    const list = toc.querySelector('ol');
    list.textContent = '';
    toc.setAttribute('aria-busy', 'false');
    const toggleLabel = toggle.querySelector('[data-article-toc-toggle-label]');
    if (!headings.length) {
      toc.dataset.state = 'empty';
      toggle.disabled = true;
      if (toggleLabel) toggleLabel.textContent = '本文暂无目录';
      return;
    }
    const items = headings.map((heading, index) => {
      if (!heading.id) heading.id = `section-${index + 1}`;
      const item = document.createElement('li');
      if (heading.tagName === 'H3') item.className = 'toc-level-3';
      const link = document.createElement('a');
      link.href = `#${encodeURIComponent(heading.id)}`;
      link.textContent = heading.textContent;
      item.append(link);
      list.append(item);
      return item;
    });
    toc.dataset.state = 'ready';
    toggle.disabled = false;
    if (toggleLabel) toggleLabel.textContent = '本文目录';

    let activeIndex = -1;
    function setActive(index) {
      if (index === activeIndex || index < 0) return;
      activeIndex = index;
      items.forEach((item, itemIndex) => {
        const active = itemIndex === index;
        item.classList.toggle('is-active', active);
        const link = item.querySelector('a');
        if (active) link.setAttribute('aria-current', 'location');
        else link.removeAttribute('aria-current');
      });
      const item = items[index];
      const visibleTop = toc.scrollTop + 44;
      const visibleBottom = toc.scrollTop + toc.clientHeight - 12;
      if (item.offsetTop < visibleTop) toc.scrollTop = Math.max(0, item.offsetTop - 52);
      else if (item.offsetTop + item.offsetHeight > visibleBottom) {
        toc.scrollTop = item.offsetTop + item.offsetHeight - toc.clientHeight + 12;
      }
    }

    function updateActive() {
      const readingLine = Math.min(280, Math.max(150, window.innerHeight * .28));
      let index = 0;
      headings.forEach((heading, headingIndex) => {
        if (heading.getBoundingClientRect().top <= readingLine) index = headingIndex;
      });
      if (window.scrollY + window.innerHeight >= document.documentElement.scrollHeight - 2) {
        index = headings.length - 1;
      }
      setActive(index);
    }

    let updateQueued = false;
    function scheduleActiveUpdate() {
      if (updateQueued) return;
      updateQueued = true;
      requestAnimationFrame(() => {
        updateQueued = false;
        updateActive();
      });
    }
    addEventListener('scroll', scheduleActiveUpdate, { passive: true });
    addEventListener('resize', scheduleActiveUpdate);

    const narrowScreen = matchMedia('(max-width: 1050px)');
    function setOpen(open) {
      rail.classList.toggle('is-open', open);
      toggle.setAttribute('aria-expanded', String(open));
      if (narrowScreen.matches) {
        toc.inert = !open;
        toc.setAttribute('aria-hidden', String(!open));
      }
    }
    function syncResponsiveState() {
      if (narrowScreen.matches) setOpen(false);
      else {
        rail.classList.remove('is-open');
        toggle.setAttribute('aria-expanded', 'false');
        toc.inert = false;
        toc.removeAttribute('aria-hidden');
      }
    }
    toggle.addEventListener('click', () => setOpen(!rail.classList.contains('is-open')));
    list.addEventListener('click', (event) => {
      const link = event.target.closest('a');
      if (!link) return;
      setActive(items.indexOf(link.parentElement));
      if (narrowScreen.matches) setOpen(false);
    });
    document.addEventListener('click', (event) => {
      if (narrowScreen.matches && rail.classList.contains('is-open') && !rail.contains(event.target)) setOpen(false);
    });
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && rail.classList.contains('is-open')) {
        setOpen(false);
        toggle.focus();
      }
    });
    narrowScreen.addEventListener('change', syncResponsiveState);
    syncResponsiveState();
    updateActive();
  }
  window.enhanceArticle = enhance;
  // 目录只依赖服务端已经输出的标题，优先于体积较大的代码高亮、公式和图表脚本生成。
  const initialArticle = document.querySelector('[data-article-content]');
  if (initialArticle) buildTableOfContents(initialArticle);
  document.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('[data-article-content]').forEach(enhance);
    const progress = document.querySelector('[data-reading-progress]');
    if (progress) {
      const update = () => {
        const available = document.documentElement.scrollHeight - innerHeight;
        progress.style.transform = `scaleX(${available > 0 ? Math.min(1, scrollY / available) : 0})`;
      };
      addEventListener('scroll', update, { passive: true });
      addEventListener('resize', update);
      update();
    }
  });
})();
