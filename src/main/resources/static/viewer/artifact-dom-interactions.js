class ArtifactDomInteractions extends EventTarget {
  constructor(frame, overlay) {
    super();
    this.frame = frame;
    this.overlay = overlay;
    this.highlight = overlay.querySelector('#element-highlight');
    this.pins = overlay.querySelector('#thread-pins');
    this.composerOpen = false;
    this.composerBusy = false;
    this.threads = new Map();
    this.active = false;
    this.selecting = false;
    frame.addEventListener('load', () => {
      this.selected = this.selectionAnchor ? this.find(this.selectionAnchor.xpath) : null;
      this.hovered = null;
      if (this.selectionAnchor && !this.selected) this.dispatchEvent(new Event('attachment-lost'));
      if (this.active) this.bindDocument();
    });
    this.frameObserver = new ResizeObserver(() => this.scheduleLayout());
    this.frameObserver.observe(frame);
    window.addEventListener('resize', () => this.scheduleLayout());
  }

  setActive(active) {
    this.active = active;
    this.overlay.hidden = !active;
    this.selected = null;
    this.hovered = null;
    if (!active) this.selecting = false;
    if (active) this.bindDocument();
    else this.unbindDocument();
  }

  setSelecting(selecting) {
    this.selecting = this.active && selecting;
    this.hovered = null;
    if (!this.composerOpen) this.selected = null;
    this.scheduleLayout();
  }

  setThreads(comments) {
    this.threads.clear();
    this.pins.replaceChildren();
    for (const comment of comments) {
      if (!comment.xpath || this.threads.has(comment.threadId)) continue;
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'thread-pin';
      button.textContent = String(comment.number);
      button.setAttribute('aria-label', 'View thread ' + button.textContent);
      button.dataset.threadId = comment.threadId;
      button.dataset.testid = this.overlay.dataset.pinTestid;
      button.addEventListener('click', () => {
        this.scrollTo(comment.xpath);
        this.dispatchEvent(new CustomEvent('thread-select', { detail: { threadId: comment.threadId } }));
      });
      this.threads.set(comment.threadId, { xpath: comment.xpath, relativeX: comment.relativeX, relativeY: comment.relativeY, button });
      this.pins.append(button);
    }
    this.scheduleLayout();
  }

  find(xpath) {
    try {
      const doc = this.frame.contentDocument;
      const node = doc?.evaluate(xpath, doc, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
      return node?.nodeType === 1 ? node : null;
    } catch {
      return null;
    }
  }

  xpath(element) {
    const parts = [];
    for (let node = element; node; node = node.parentElement) {
      let index = 1;
      for (let previous = node.previousElementSibling; previous; previous = previous.previousElementSibling) {
        if (previous.localName === node.localName && previous.namespaceURI === node.namespaceURI) index++;
      }
      const name = node.namespaceURI === 'http://www.w3.org/1999/xhtml'
        ? node.localName : "*[local-name()='" + node.localName + "']";
      parts.unshift(name + '[' + index + ']');
    }
    return '/' + parts.join('/');
  }

  scrollTo(xpath) {
    const element = this.find(xpath);
    if (!element) return false;
    this.selected = element;
    this.hovered = null;
    element.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'instant' });
    this.scheduleLayout();
    return true;
  }

  select(anchor) {
    this.selectionAnchor = anchor;
    this.selected = this.find(anchor.xpath);
    this.selectedPosition = { relativeX: anchor.relativeX, relativeY: anchor.relativeY };
    this.hovered = null;
    if (!this.selected && this.frame.contentDocument?.readyState === 'complete' && this.frame.contentDocument.URL !== 'about:blank') {
      this.dispatchEvent(new Event('attachment-lost'));
    }
    this.scheduleLayout();
  }

  clearSelection() {
    this.selectionAnchor = null;
    this.selected = null;
    this.hovered = null;
    this.scheduleLayout();
  }

  isSelectable(element) {
    const doc = this.frame.contentDocument;
    return element?.nodeType === 1 && element !== doc?.documentElement && element !== doc?.body;
  }

  unbindDocument() {
    this.listeners?.abort();
    this.mutations?.disconnect();
    this.documentObserver?.disconnect();
    cancelAnimationFrame(this.layoutRequest);
    this.layoutRequest = null;
    this.highlight.hidden = true;
  }

  bindDocument() {
    this.unbindDocument();
    const doc = this.frame.contentDocument;
    if (!doc?.documentElement) return;
    this.listeners = new AbortController();
    const options = { capture: true, signal: this.listeners.signal };
    doc.addEventListener('pointermove', event => {
      if (!this.selecting || this.composerOpen) return;
      this.hovered = this.isSelectable(event.target) ? event.target : null;
      this.scheduleLayout();
    }, options);
    doc.addEventListener('pointerleave', () => {
      this.hovered = null;
      this.scheduleLayout();
    }, { signal: this.listeners.signal });
    doc.addEventListener('click', event => {
      if (!this.selecting) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      if (this.composerBusy) return;
      if (!this.isSelectable(event.target)) return;
      this.selected = event.target;
      this.hovered = null;
      const rect = event.target.getBoundingClientRect();
      const relativeX = Math.max(0, Math.min(1, (event.clientX - rect.left) / (rect.width || 1)));
      const relativeY = Math.max(0, Math.min(1, (event.clientY - rect.top) / (rect.height || 1)));
      this.selectedPosition = { relativeX, relativeY };
      this.selectionAnchor = { xpath: this.xpath(event.target), relativeX, relativeY };
      this.dispatchEvent(new CustomEvent('element-select', {
        detail: this.selectionAnchor
      }));
      this.scheduleLayout();
    }, options);
    doc.addEventListener('keydown', event => {
      if (event.key === 'Escape') this.dispatchEvent(new Event('escape'));
    }, options);
    doc.addEventListener('scroll', () => this.scheduleLayout(), options);
    doc.addEventListener('load', () => this.scheduleLayout(), options);
    doc.fonts?.addEventListener('loadingdone', () => this.scheduleLayout(), { signal: this.listeners.signal });
    this.frame.contentWindow.addEventListener('resize', () => this.scheduleLayout(), { signal: this.listeners.signal });
    this.mutations = new MutationObserver(() => this.scheduleLayout());
    this.mutations.observe(doc.documentElement, { subtree: true, childList: true, attributes: true, characterData: true });
    this.documentObserver = new ResizeObserver(() => this.scheduleLayout());
    this.documentObserver.observe(doc.documentElement);
    this.scheduleLayout();
  }

  scheduleLayout() {
    if (!this.active || this.layoutRequest) return;
    this.layoutRequest = requestAnimationFrame(() => {
      this.layoutRequest = null;
      this.layout();
    });
  }

  layout() {
    if (!this.active) return;
    const frame = this.frame.getBoundingClientRect();
    Object.assign(this.overlay.style, { left: frame.left + 'px', top: frame.top + 'px', width: frame.width + 'px', height: frame.height + 'px' });
    const element = this.hovered || this.selected;
    const bounds = element?.isConnected ? element.getBoundingClientRect() : null;
    this.highlight.hidden = !bounds || !bounds.width || !bounds.height;
    if (!this.highlight.hidden) {
      Object.assign(this.highlight.style, { left: bounds.left + 'px', top: bounds.top + 'px', width: bounds.width + 'px', height: bounds.height + 'px' });
    }
    for (const { xpath, relativeX, relativeY, button } of this.threads.values()) {
      const target = this.find(xpath);
      const rect = target?.getBoundingClientRect();
      button.hidden = !rect || !rect.width || !rect.height || rect.bottom < 0 || rect.top > frame.height || rect.right < 0 || rect.left > frame.width;
      if (button.hidden) continue;
      const x = rect.left + (relativeX ?? 1) * rect.width;
      const y = rect.top + (relativeY ?? 0) * rect.height;
      button.hidden = x < 0 || y < 0 || x > frame.width || y > frame.height;
      if (button.hidden) continue;
      button.style.left = Math.max(2, Math.min(frame.width - 30, x - 2)) + 'px';
      button.style.top = Math.max(2, Math.min(frame.height - 30, y - 24)) + 'px';
    }
    const selectedBounds = this.selected?.isConnected ? this.selected.getBoundingClientRect() : null;
    const resolved = Array.from(new Set(Array.from(this.threads.values(), thread => thread.xpath))).filter(xpath => this.find(xpath));
    this.dispatchEvent(new CustomEvent('layout', { detail: {
      bounds: selectedBounds ? { left: selectedBounds.left, top: selectedBounds.top, width: selectedBounds.width, height: selectedBounds.height } : null,
      resolved
    } }));
  }
}
