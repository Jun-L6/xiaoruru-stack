(() => {
  const diagramSources = new WeakMap();
  let diagramQueue = Promise.resolve();
  function renderDiagrams(root = document) {
    diagramQueue = diagramQueue.catch(() => {}).then(async () => {
      if (!window.mermaid) return;
      const nodes = [...root.querySelectorAll('.mermaid')].filter(node => node.isConnected && diagramSources.has(node));
      if (!nodes.length) return;
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
  document.addEventListener('rurublog:theme', () => renderDiagrams());
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
    if (window.mermaid && diagrams.length) {
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
    if (!toc) return;
    const headings = [...article.querySelectorAll('h2, h3')];
    if (headings.length < 2) return;
    const list = toc.querySelector('ol');
    list.textContent = '';
    headings.forEach((heading, index) => {
      if (!heading.id) heading.id = `section-${index + 1}`;
      const item = document.createElement('li');
      if (heading.tagName === 'H3') item.className = 'toc-level-3';
      const link = document.createElement('a');
      link.href = `#${encodeURIComponent(heading.id)}`;
      link.textContent = heading.textContent;
      item.append(link);
      list.append(item);
    });
    toc.hidden = false;
  }
  window.enhanceArticle = enhance;
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
