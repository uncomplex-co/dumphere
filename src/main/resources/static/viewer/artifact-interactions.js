// Viewer-side bridge. Only the wrapper inspects the artifact DOM.
class ArtifactInteractions extends EventTarget {
  constructor(frame, overlay) {
    super();
    this.frame = frame;
    this.overlay = overlay;
    this.composer = overlay.querySelector('#attached-composer');
    const frameOrigin = new URL(frame.src, location.href).origin;
    this.origin = frameOrigin === 'null' ? location.origin : frameOrigin;
    this.active = false;
    this.selecting = false;
    this.ready = false;
    this.threads = new Map();
    this.resolved = new Set();
    this.selection = null;
    this.bounds = null;
    window.addEventListener('message', event => this.receive(event));
    frame.addEventListener('load', () => {
      this.ready = false;
      this.resolved.clear();
      this.bounds = null;
      this.send('hello');
      this.dispatchEvent(new Event('layout'));
    });
    this.frameObserver = new ResizeObserver(() => this.scheduleLayout());
    this.frameObserver.observe(frame);
    this.composerObserver = new ResizeObserver(() => this.scheduleLayout());
    this.composerObserver.observe(this.composer);
    this.composer.addEventListener('composer-statechange', () => this.syncState());
    window.addEventListener('resize', () => this.scheduleLayout());
    this.send('hello');
  }

  send(type, payload) {
    ArtifactMessages.send(this.frame.contentWindow, this.origin, type, payload);
  }

  receive(event) {
    if (!ArtifactMessages.accepts(event, this.frame.contentWindow, this.origin)) return;
    const { type, payload } = event.data;
    if (type === 'ready') {
      this.ready = true;
      this.syncState();
      this.sendThreads();
      if (this.selection) this.send('select', this.selection);
    } else if (type === 'layout' && payload && (payload.bounds === null || ArtifactMessages.isBounds(payload.bounds)) && Array.isArray(payload.resolved)) {
      this.bounds = payload.bounds;
      const known = new Set(Array.from(this.threads.values(), thread => thread.xpath));
      this.resolved = new Set(payload.resolved.filter(xpath => known.has(xpath)));
      this.layout();
      this.dispatchEvent(new Event('layout'));
    } else if (type === 'element-select' && this.active && this.selecting && ArtifactMessages.isAnchor(payload)) {
      if (this.composer.querySelector('comment-composer')?.submitting) return;
      this.selection = { xpath: payload.xpath, relativeX: payload.relativeX, relativeY: payload.relativeY };
      this.dispatchEvent(new CustomEvent('element-select', { detail: this.selection }));
    } else if (type === 'thread-select' && this.active && this.threads.has(payload?.threadId)) {
      this.dispatchEvent(new CustomEvent('thread-select', { detail: { threadId: payload.threadId } }));
    } else if (type === 'escape' && this.active) {
      this.dispatchEvent(new Event('escape'));
    } else if (type === 'attachment-lost') {
      this.bounds = null;
      this.dispatchEvent(new Event('attachment-lost'));
    }
  }

  syncState() {
    if (!this.ready) return;
    this.send('state', {
      active: this.active,
      selecting: this.selecting,
      composerOpen: !this.composer.hidden,
      composerBusy: !!this.composer.querySelector('comment-composer')?.submitting
    });
  }

  setActive(active) {
    this.active = active;
    this.overlay.hidden = !active;
    if (!active) {
      this.selecting = false;
      this.clearSelection();
    }
    this.syncState();
    this.scheduleLayout();
  }

  setSelecting(selecting) {
    this.selecting = this.active && selecting;
    this.syncState();
  }

  setThreads(comments) {
    this.threads.clear();
    for (const comment of comments) {
      if (!comment.xpath || this.threads.has(comment.threadId)) continue;
      this.threads.set(comment.threadId, {
        threadId: comment.threadId, number: comment.threadNumber,
        xpath: comment.xpath, relativeX: comment.relativeX ?? 1, relativeY: comment.relativeY ?? 0
      });
    }
    this.sendThreads();
  }

  sendThreads() {
    if (this.ready) this.send('threads', Array.from(this.threads.values()));
  }

  threadNumber(threadId) {
    return this.threads.get(threadId)?.number;
  }

  isResolved(xpath) {
    return this.resolved.has(xpath);
  }

  scrollTo(xpath) {
    const thread = Array.from(this.threads.values()).find(thread => thread.xpath === xpath);
    this.selection = { xpath, relativeX: thread?.relativeX ?? 0.5, relativeY: thread?.relativeY ?? 0.5 };
    this.send('scroll-to', this.selection);
  }

  clearSelection() {
    this.selection = null;
    this.bounds = null;
    this.send('clear-selection');
  }

  scheduleLayout() {
    this.syncState();
    if (this.ready) this.send('measure');
    this.layout();
  }

  layout() {
    if (!this.active) return;
    const frame = this.frame.getBoundingClientRect();
    Object.assign(this.overlay.style, { left: frame.left + 'px', top: frame.top + 'px', width: frame.width + 'px', height: frame.height + 'px' });
    if (this.composer.hidden || !this.bounds || !this.selection) return;
    const width = this.composer.offsetWidth;
    const height = this.composer.offsetHeight;
    const x = this.bounds.left + this.selection.relativeX * this.bounds.width;
    const y = this.bounds.top + this.selection.relativeY * this.bounds.height;
    this.composer.style.left = Math.max(8, Math.min(frame.width - width - 8, x + 12)) + 'px';
    const below = y + 12;
    this.composer.style.top = Math.max(8, Math.min(frame.height - height - 8, below + height < frame.height ? below : y - height - 12)) + 'px';
  }
}
