(() => {
  'use strict';
  const frame = document.getElementById('artifact-document');
  const interactions = new ArtifactDomInteractions(frame, document.getElementById('artifact-annotations'));
  // Stage one uses one origin. A separate rendering host will need an explicit viewer origin.
  const viewerOrigin = location.origin;
  const send = (type, payload) => ArtifactMessages.send(parent, viewerOrigin, type, payload);

  window.addEventListener('message', event => {
    if (!ArtifactMessages.accepts(event, parent, viewerOrigin)) return;
    const { type, payload } = event.data;
    if (type === 'hello') send('ready');
    else if (type === 'state' && payload && ['active', 'selecting', 'composerOpen', 'composerBusy'].every(key => typeof payload[key] === 'boolean')) {
      interactions.composerOpen = payload.composerOpen;
      interactions.composerBusy = payload.composerBusy;
      if (interactions.active !== payload.active) interactions.setActive(payload.active);
      if (interactions.selecting !== payload.selecting) interactions.setSelecting(payload.selecting);
    } else if (type === 'threads' && Array.isArray(payload) && payload.every(thread =>
      ArtifactMessages.isAnchor(thread) && Number.isSafeInteger(thread.threadId) && thread.threadId > 0 && Number.isSafeInteger(thread.number) && thread.number > 0
    )) {
      interactions.setThreads(payload);
    } else if (type === 'scroll-to' && ArtifactMessages.isAnchor(payload)) {
      interactions.select(payload);
      interactions.scrollTo(payload.xpath);
    } else if (type === 'select' && ArtifactMessages.isAnchor(payload)) {
      interactions.select(payload);
    } else if (type === 'clear-selection') interactions.clearSelection();
    else if (type === 'measure') interactions.scheduleLayout();
  });

  for (const type of ['element-select', 'thread-select', 'layout']) {
    interactions.addEventListener(type, event => send(type, event.detail));
  }
  for (const type of ['escape', 'attachment-lost']) {
    interactions.addEventListener(type, () => send(type));
  }
  send('ready');
})();
