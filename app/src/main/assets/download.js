(() => {
  'use strict';
  if (window.GifDownloads || !window.GifNative) return;
  let pending = null, offset = 0, transmitting = false, generation = 0;
  const post = value => GifNative.postMessage(JSON.stringify(value));
  const cancel = () => { generation++; pending = null; transmitting = false; offset = 0; };
  async function offer(url, name) {
    if (pending) return;
    const token = ++generation;
    pending = { loading: true };
    try {
      const response = await fetch(url);
      const blob = await response.blob();
      if (token !== generation) return;
      if (blob.type && blob.type !== 'image/gif') throw Error('Not a GIF');
      pending = { blob };
      post({ type: 'offer', name: name || 'haeyang.gif', size: blob.size });
    } catch (_) { if (token === generation) { cancel(); post({ type: 'error' }); } }
  }
  async function next() {
    if (!pending?.blob || !transmitting) return;
    const entry = pending;
    try {
      if (offset === pending.blob.size) { post({ type: 'end' }); return; }
      const bytes = new Uint8Array(await pending.blob.slice(offset, offset + 49152).arrayBuffer());
      if (pending !== entry || !transmitting) return;
      let binary = '';
      for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
      offset += bytes.length;
      post({ type: 'chunk', data: btoa(binary) });
    } catch (_) { if (pending === entry) { cancel(); post({ type: 'error' }); } }
  }
  GifNative.onmessage = event => {
    if (event.data === 'next') next();
    else if (['saved', 'error', 'busy'].includes(event.data)) cancel();
  };
  window.GifDownloads = {
    offer, cancel,
    start() { if (pending?.blob) { offset = 0; transmitting = true; next(); } }
  };
  // Capturing also handles programmatic anchor.click() used by this site.
  document.addEventListener('click', event => {
    const anchor = event.target.closest?.('a');
    if (!anchor || !anchor.hasAttribute('download')) return;
    if (!anchor.href.startsWith('blob:') && !anchor.href.startsWith('data:')) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    offer(anchor.href, anchor.download);
  }, true);
})();
