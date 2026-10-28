// Fill in the total downloads that scripts/download_count.py writes at deploy time; stay hidden without it.
(() => {
  const label = document.querySelector('.downloads');
  if (!label || !window.fetch) return;
  fetch(new URL('downloads.json', document.currentScript.src))
    .then(response => response.ok ? response.json() : null)
    .then(data => {
      const total = data && Number(data.apkDownloads);
      if (!(total > 0)) return;
      let text = String(total);
      try { text = new Intl.NumberFormat(document.documentElement.lang + '-u-nu-latn').format(total); } catch (_) {}
      label.querySelector('bdi').textContent = text;
      label.hidden = false;
    })
    .catch(() => {});
})();
