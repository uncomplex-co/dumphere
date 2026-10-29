(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const element = (tag, className, text) => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text) node.textContent = text;
    return node;
  };
  const text = (node, value) => { if (node.textContent !== value) node.textContent = value; };
  const set = (node, property, value) => { if (node[property] !== value) node[property] = value; };
  const attr = (node, name, value) => { if (node.getAttribute(name) !== String(value)) node.setAttribute(name, String(value)); };
  const toggle = (node, name, value) => { if (node.classList.contains(name) !== value) node.classList.toggle(name, value); };
  const button = (label, testid, icon, className = 'icon-button') => {
    const node = element('button', className);
    node.type = 'button';
    node.dataset.testid = testid;
    node.setAttribute('aria-label', label);
    node.title = label;
    if (icon) node.innerHTML = '<svg aria-hidden="true"><use href="#' + icon + '"/></svg>';
    else node.textContent = label;
    return node;
  };
  const place = (parent, nodes) => {
    let next = parent.firstElementChild;
    for (const node of nodes) {
      if (node === next) next = next.nextElementSibling;
      else if (parent.moveBefore && node.parentNode === parent && parent.isConnected) parent.moveBefore(node, next);
      else parent.insertBefore(node, next);
    }
  };

  class CommentView {
    constructor(panel, id) {
      this.panel = panel;
      this.id = id;
      this.key = 'edit:' + id;
      this.node = element('li', 'comment');
      this.node.dataset.commentId = id;
      this.node.dataset.testid = panel.initial.commentTestid;
      this.node.tabIndex = 0;
      const meta = element('div', 'comment-meta');
      const details = element('div', 'comment-author-details');
      const authorGroup = element('div', 'comment-author-group');
      this.number = element('span', 'comment-thread-number');
      this.author = element('span', 'comment-author');
      authorGroup.append(this.number, this.author);
      const timestamps = element('div', 'comment-timestamps');
      this.time = element('time');
      this.edited = element('span', 'comment-edited', 'Edited');
      timestamps.append(this.time, this.edited);
      details.append(authorGroup, timestamps);
      const trailing = element('div', 'comment-meta-trailing');
      this.resolve = button('Resolve thread', panel.ids.resolve, 'icon-check', 'icon-button thread-resolve-button');
      this.resolve.addEventListener('click', () => panel.resolve(this.record.threadId, true));
      this.trigger = button('Comment options', panel.initial.commentMenuTestid, 'icon-more', 'icon-button comment-menu-trigger');
      this.trigger.setAttribute('aria-haspopup', 'menu');
      this.trigger.setAttribute('aria-controls', 'comment-menu-' + id);
      this.trigger.addEventListener('click', () => panel.state.change({ menuId: panel.state.view.menuId === id ? null : id }));
      this.trigger.addEventListener('keydown', event => {
        if (!['ArrowDown', 'ArrowUp'].includes(event.key)) return;
        event.preventDefault();
        panel.state.change({ menuId: id });
        this.menuItems().at(event.key === 'ArrowUp' ? -1 : 0)?.focus();
      });
      trailing.append(this.resolve, this.trigger);
      meta.append(details, trailing);
      this.body = element('p', 'comment-body');
      this.menu = element('div', 'artifact-menu comment-menu');
      this.menu.id = 'comment-menu-' + id;
      this.menu.popover = 'auto';
      this.menu.setAttribute('role', 'menu');
      this.menu.setAttribute('aria-label', 'Comment options');
      this.edit = button('Edit', panel.initial.commentEditTestid, 'icon-edit');
      this.edit.append(element('span', '', 'Edit'));
      this.remove = button('Delete', panel.ids.delete, 'icon-delete');
      this.remove.append(element('span', '', 'Delete'));
      this.reopen = button('Re-open', panel.ids.reopen, null);
      for (const action of [this.edit, this.remove, this.reopen]) action.setAttribute('role', 'menuitem');
      this.menu.append(this.edit, this.remove, this.reopen);
      this.edit.addEventListener('click', () => {
        panel.state.change({ menuId: null });
        panel.state.compose(this.key, { mode: 'edit', threadId: this.record.threadId, commentId: id });
        this.composer.input.focus();
      });
      this.remove.addEventListener('click', () => {
        panel.state.change({ menuId: null });
        panel.state.requestDelete(id);
      });
      this.reopen.addEventListener('click', () => {
        panel.state.change({ menuId: null });
        panel.resolve(this.record.threadId, false);
      });
      this.menu.addEventListener('toggle', event => {
        if (event.newState === 'closed' && panel.state.view.menuId === id) panel.state.change({ menuId: null });
      });
      this.menu.addEventListener('keydown', event => {
        if (event.key === 'Escape' || event.key === 'Tab') {
          event.stopPropagation();
          panel.state.change({ menuId: null });
          if (event.key === 'Escape') { event.preventDefault(); this.trigger.focus(); }
        } else if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
          event.preventDefault();
          const items = this.menuItems();
          const index = items.indexOf(document.activeElement);
          const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1
            : (index + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
          items[next]?.focus();
        }
      });
      this.composer = panel.composer(this.key, 'Edit comment', 'Save', 'Saving...', true);
      this.node.append(meta, this.body, this.menu, this.composer);
      this.node.addEventListener('click', event => {
        if (event.target.closest('button, comment-composer, [role="menu"]')) return;
        panel.select(this.record);
      });
      this.node.addEventListener('keydown', event => {
        if (event.target === this.node && ['Enter', ' '].includes(event.key)) {
          event.preventDefault();
          panel.select(this.record);
        }
      });
    }

    menuItems() { return [...this.menu.children].filter(item => !item.hidden); }

    render(comment, first) {
      const { data, view } = this.panel.state;
      const draft = view.composers.get(this.key);
      const exists = data.comments.has(comment.id);
      const signature = JSON.stringify([first, view.selectedThreadId === comment.threadId,
        view.attached.has(comment.xpath), view.canManage, exists, view.resolutions.has(comment.threadId),
        view.menuId === comment.id, draft?.body, draft?.pending, draft?.error, draft?.remoteDeleted, draft?.remoteChanged]);
      if (this.record === comment && this.signature === signature) return;
      this.signature = signature;
      const changed = this.record !== comment;
      this.record = comment;
      if (changed) {
        this.node.dataset.threadId = comment.threadId;
        this.node.dataset.xpath = comment.xpath || '';
        text(this.author, comment.author);
        if (this.time.dateTime !== comment.createdAt) {
          this.time.dateTime = comment.createdAt;
          text(this.time, new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(comment.createdAt)));
        }
        this.edited.hidden = !comment.editedAt;
        this.edited.title = comment.editedAt ? 'Edited ' + new Date(comment.editedAt).toLocaleString() : '';
        text(this.body, comment.body);
        text(this.number, String(comment.threadNumber));
        this.number.setAttribute('aria-label', 'Thread ' + comment.threadNumber);
      }
      toggle(this.node, 'is-selected', view.selectedThreadId === comment.threadId);
      toggle(this.node, 'is-attached', !!comment.xpath && view.attached.has(comment.xpath));
      this.number.hidden = !first || !comment.xpath;
      this.body.hidden = !!draft;
      this.resolve.hidden = !first || !!comment.resolvedAt || !view.canManage || !exists;
      this.resolve.disabled = view.resolutions.has(comment.threadId);
      this.edit.hidden = this.remove.hidden = !comment.canEdit || !exists;
      this.reopen.hidden = !first || !comment.resolvedAt || !view.canManage || !exists;
      this.reopen.disabled = view.resolutions.has(comment.threadId);
      this.trigger.hidden = this.menuItems().length === 0;
      this.trigger.disabled = !!draft;
      this.trigger.setAttribute('aria-expanded', String(view.menuId === comment.id));
      this.composer.render(draft);
    }

    syncMenu() {
      const open = this.panel.state.view.menuId === this.id && !this.trigger.hidden && !this.trigger.disabled;
      if (open && !this.menu.matches(':popover-open')) {
        const bounds = this.trigger.getBoundingClientRect();
        this.menu.style.left = Math.max(8, Math.min(bounds.right - 140, innerWidth - 148)) + 'px';
        this.menu.style.top = Math.max(8, Math.min(bounds.bottom + 4, innerHeight - this.menuItems().length * 40 - 16)) + 'px';
        this.menu.showPopover();
        this.menuItems()[0]?.focus();
      } else if (!open && this.menu.matches(':popover-open')) this.menu.hidePopover();
    }
  }

  class ThreadView {
    constructor(panel, id) {
      this.panel = panel;
      this.id = id;
      this.node = element('li', 'comment-thread');
      this.node.dataset.threadId = id;
      this.node.dataset.testid = panel.initial.threadTestid;
      this.list = element('ol', 'thread-comments');
      this.list.setAttribute('aria-label', 'Thread comments, oldest first');
      this.comments = new Map();
      this.footer = element('div', 'thread-reply');
      this.reply = button('Reply...', panel.initial.replyTestid, null, 'thread-reply-button');
      this.key = 'reply:' + id;
      this.composer = panel.composer(this.key, 'Reply', 'Reply', 'Posting...');
      this.composer.setAttribute('compact', '');
      this.reply.addEventListener('click', () => {
        panel.state.compose(this.key, { mode: 'reply', threadId: id });
        this.composer.input.focus();
      });
      this.footer.append(this.reply, this.composer);
      this.node.append(this.list, this.footer);
    }

    render(thread) {
      const ids = new Set(thread.comments.map(comment => comment.id));
      for (const [id, comment] of this.comments) if (!ids.has(id)) { comment.node.remove(); this.comments.delete(id); }
      const nodes = thread.comments.map((record, index) => {
        if (!this.comments.has(record.id)) this.comments.set(record.id, new CommentView(this.panel, record.id));
        const comment = this.comments.get(record.id);
        if (!comment.node.isConnected) this.list.append(comment.node);
        comment.render(record, index === 0);
        return comment.node;
      });
      place(this.list, nodes);
      const { view, data } = this.panel.state;
      const draft = view.composers.get(this.key);
      toggle(this.node, 'is-selected', view.selectedThreadId === thread.id);
      set(this.footer, 'hidden', view.selectedThreadId !== thread.id && !draft);
      set(this.reply, 'hidden', !!draft);
      set(this.reply, 'disabled', !thread.comments.some(comment => data.comments.has(comment.id)));
      this.composer.render(draft);
    }
  }

  window.DiscussionPanel = class DiscussionPanel {
    constructor({ id, csrf, interactions, notify, context }) {
      this.initial = document.body.dataset;
      this.toggleLabel = $('comments-toggle').getAttribute('aria-label');
      this.interactions = interactions;
      this.notify = notify;
      this.state = new DiscussionState(new DiscussionApi(id, csrf));
      Object.assign(this.state.view, context);
      this.threads = new Map();
      this.ids = {
        resolve: $('discussion-filter').dataset.resolveTestid, reopen: $('discussion-filter').dataset.reopenTestid,
        delete: $('comment-delete-dialog').dataset.actionTestid
      };
      this.heading = element('li', 'discussion-group-heading');
      this.heading.append(element('h3', '', 'Resolved'));
      this.bindComposer($('comment-composer'), 'new');
      this.bindComposer($('attached-comment-composer'), 'attached');
      this.state.subscribe(() => this.render());
      this.bindDrag();
      $('comments-toggle').addEventListener('click', () => {
        if (this.state.view.open) this.close();
        else if (this.state.view.canView) {
          this.state.change({ open: true });
          $('comments-close').focus();
          this.state.refresh();
        }
      });
      $('comments-close').addEventListener('click', () => this.close());
      $('comments-retry').addEventListener('click', () => this.state.refresh());
      $('discussion-filter').addEventListener('click', () => this.state.change({ showResolved: !this.state.view.showResolved }));
      $('comment-add').addEventListener('click', () => this.start());
      $('discussion-start').addEventListener('click', () => this.start());
      $('element-selection').addEventListener('click', () => this.state.change({ selecting: !this.state.view.selecting }));
      $('comments-panel').addEventListener('keydown', event => {
        if (event.key === 'Escape') { event.preventDefault(); this.escape(); }
      });
      interactions.addEventListener('thread-select', event => {
        this.state.change({ selectedThreadId: event.detail.threadId });
        const comment = this.threads.get(event.detail.threadId)?.comments.values().next().value;
        comment?.node.scrollIntoView({ block: 'nearest' });
        comment?.node.focus({ preventScroll: true });
      });
      interactions.addEventListener('element-select', event => {
        if (this.state.view.composers.get('attached')?.pending) return;
        if (this.state.view.composers.has('attached')) {
          this.notify('Finish or cancel the attached comment before selecting another element.');
          return;
        }
        this.state.compose('attached', { mode: 'new', anchor: event.detail });
        $('attached-comment-composer').input.focus();
      });
      interactions.addEventListener('escape', () => this.escape());
      interactions.addEventListener('attachment-lost', () => {
        if (!this.state.view.composers.has('attached')) return;
        this.notify('The selected element is no longer present. Your draft is preserved.');
      });
      interactions.addEventListener('layout', () => {
        const attached = new Set(interactions.resolved);
        if (attached.size !== this.state.view.attached.size || [...attached].some(path => !this.state.view.attached.has(path))) this.state.change({ attached });
      });
      const dialog = $('comment-delete-dialog');
      dialog.addEventListener('cancel', event => { event.preventDefault(); this.state.cancelDelete(); });
      $('comment-delete-cancel').addEventListener('click', () => this.state.cancelDelete());
      $('comment-delete-confirm').addEventListener('click', async () => {
        if (await this.state.delete()) $('comment-add').focus();
      });
      window.addEventListener('blur', () => setTimeout(() => {
        if (document.activeElement === $('artifact-frame') && this.state.view.menuId != null) this.state.change({ menuId: null });
      }, 0));
      this.poll = setInterval(() => this.refreshVisible(), 4000);
      document.addEventListener('visibilitychange', () => this.refreshVisible());
      window.addEventListener('pageshow', () => this.refreshVisible());
      this.render();
      this.refreshVisible();
    }

    updateAccess(context) { this.state.access(context); }
    refreshVisible() { if (!document.hidden && this.state.view.canView) return this.state.refresh(); }
    close() {
      this.state.close();
      $('comments-toggle').focus();
    }
    escape() {
      if (this.state.view.selecting) this.state.change({ selecting: false });
      else this.close();
    }
    start() {
      this.state.compose('new', { mode: 'new' });
      $('comment-composer').input.focus();
    }
    select(comment) {
      this.state.change({ selectedThreadId: comment.threadId });
      if (comment.xpath) this.interactions.scrollTo(comment.xpath);
    }

    bindDrag() {
      const handle = $('attached-comment-drag');
      const popup = $('attached-composer');
      const position = () => {
        const bounds = popup.getBoundingClientRect();
        const overlay = $('artifact-annotations').getBoundingClientRect();
        return { x: bounds.left - overlay.left, y: bounds.top - overlay.top };
      };
      const move = (x, y) => {
        const overlay = $('artifact-annotations').getBoundingClientRect();
        this.state.moveAttached({
          x: Math.max(8, Math.min(overlay.width - popup.offsetWidth - 8, x)),
          y: Math.max(8, Math.min(overlay.height - popup.offsetHeight - 8, y))
        });
      };
      const finish = cancel => {
        const drag = this.state.view.drag;
        if (!drag) return;
        this.state.change({ drag: null });
        if (cancel) this.state.moveAttached(drag.previous);
        if (handle.hasPointerCapture(drag.pointerId)) handle.releasePointerCapture(drag.pointerId);
      };
      handle.addEventListener('pointerdown', event => {
        if (!event.isPrimary || event.button !== 0 || !this.state.view.composers.has('attached')) return;
        event.preventDefault();
        const origin = position();
        this.state.change({ drag: {
          pointerId: event.pointerId, startX: event.clientX, startY: event.clientY,
          origin, previous: this.state.view.composers.get('attached').position || null
        } });
        handle.focus({ preventScroll: true });
        handle.setPointerCapture(event.pointerId);
      });
      handle.addEventListener('pointermove', event => {
        const drag = this.state.view.drag;
        if (!drag || drag.pointerId !== event.pointerId) return;
        event.preventDefault();
        move(drag.origin.x + event.clientX - drag.startX, drag.origin.y + event.clientY - drag.startY);
      });
      handle.addEventListener('pointerup', event => {
        if (this.state.view.drag?.pointerId === event.pointerId) finish(false);
      });
      handle.addEventListener('pointercancel', () => finish(true));
      handle.addEventListener('lostpointercapture', () => finish(false));
      handle.addEventListener('keydown', event => {
        if (event.key === 'Escape') {
          event.preventDefault();
          event.stopPropagation();
          if (this.state.view.drag) finish(true);
          else this.state.cancel('attached');
        } else if (event.key === 'Home') {
          event.preventDefault();
          this.state.moveAttached(null);
        } else if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) {
          event.preventDefault();
          const origin = position();
          const step = event.shiftKey ? 40 : 10;
          move(origin.x + (event.key === 'ArrowRight' ? step : event.key === 'ArrowLeft' ? -step : 0),
            origin.y + (event.key === 'ArrowDown' ? step : event.key === 'ArrowUp' ? -step : 0));
        }
      });
    }
    async resolve(threadId, resolved) {
      if (await this.state.resolve(threadId, resolved)) {
        $('discussion-filter').focus();
        this.notify(resolved ? 'Thread resolved.' : 'Thread reopened.');
      }
    }

    composer(key, label, submit, pending, edit = false) {
      const node = element('comment-composer', edit ? 'comment-edit-composer' : '');
      node.hidden = true;
      node.setAttribute('label', label);
      node.setAttribute('submit-label', submit);
      node.setAttribute('pending-label', pending);
      node.dataset.bodyTestid = edit ? this.initial.commentEditBodyTestid : this.initial.commentBodyTestid;
      node.dataset.postTestid = edit ? this.initial.commentEditSaveTestid : this.initial.commentPostTestid;
      node.dataset.cancelTestid = edit ? this.initial.commentEditCancelTestid : this.initial.commentCancelTestid;
      this.bindComposer(node, key);
      return node;
    }

    bindComposer(node, key) {
      node.addEventListener('comment-input', event => this.state.draft(key, event.detail.body));
      node.addEventListener('comment-cancel', () => this.state.cancel(key));
      node.addEventListener('keydown', event => {
        if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); this.state.cancel(key); }
      });
      node.addEventListener('comment-submit', async () => {
        const submitted = this.state.view.composers.get(key);
        if (await this.state.submit(key)) {
          const current = this.state.view.composers.get(key);
          if (!this.state.view.open || (current && current !== submitted)) return;
          const id = this.state.view.selectedThreadId;
          (this.threads.get(id)?.reply || $('comment-add')).focus({ preventScroll: true });
        }
      });
    }

    render() {
      const { data, view } = this.state;
      const panel = $('comments-panel');
      const top = panel.getBoundingClientRect().top;
      const anchor = [...$('comments-list').querySelectorAll('.comment-thread:not([hidden])')].find(node => node.getBoundingClientRect().bottom > top);
      const anchorTop = anchor?.getBoundingClientRect().top;
      const focused = document.activeElement;
      const selection = focused instanceof HTMLTextAreaElement ? [focused.selectionStart, focused.selectionEnd, focused.selectionDirection] : null;
      set(panel, 'hidden', !view.open);
      attr($('comments-toggle'), 'aria-expanded', view.open);
      toggle($('comments-toggle'), 'has-unread', view.unread);
      const toggleLabel = this.toggleLabel + (view.unread ? ', unread discussion updates' : '');
      attr($('comments-toggle'), 'aria-label', toggleLabel);
      set($('comments-toggle'), 'title', toggleLabel);
      toggle(document.body, 'comments-open', view.open);
      attr($('discussion-filter'), 'aria-pressed', view.showResolved);
      attr($('element-selection'), 'aria-pressed', view.selecting);
      set($('element-selection'), 'title', view.selecting ? 'Stop selecting elements (Escape)' : 'Select elements for comments');
      $('comment-composer').render(view.composers.get('new'));
      const attachedDraft = view.composers.get('attached');
      set($('attached-composer'), 'hidden', !view.open || !attachedDraft);
      toggle($('attached-composer'), 'is-dragging', !!view.drag);
      $('attached-comment-composer').render(attachedDraft);
      const all = this.state.threads();
      const visible = this.state.visibleThreads();
      const visibleIds = new Set(visible.map(thread => thread.id));
      const ids = new Set(all.map(thread => thread.id));
      for (const [id, thread] of this.threads) if (!ids.has(id)) { thread.node.remove(); this.threads.delete(id); }
      for (const thread of all) {
        if (!this.threads.has(thread.id)) this.threads.set(thread.id, new ThreadView(this, thread.id));
        const node = this.threads.get(thread.id);
        if (!node.node.isConnected) $('comments-list').append(node.node);
        set(node.node, 'hidden', !visibleIds.has(thread.id));
        node.render(thread);
      }
      const nodes = [];
      for (const resolved of [false, true]) {
        const group = visible.filter(thread => !!thread.displayResolved === resolved);
        if (resolved && view.showResolved && group.length) nodes.push(this.heading);
        nodes.push(...group.map(thread => this.threads.get(thread.id).node));
      }
      if (!nodes.includes(this.heading)) this.heading.remove();
      nodes.push(...all.filter(thread => !visibleIds.has(thread.id)).map(thread => this.threads.get(thread.id).node));
      place($('comments-list'), nodes);
      for (const thread of this.threads.values()) for (const comment of thread.comments.values()) comment.syncMenu();
      const empty = data.comments.size === 0;
      set($('discussion-empty'), 'hidden', !empty || view.loading || !!view.error || view.composers.has('new'));
      text($('comments-status'), view.error || (view.loading && empty ? 'Loading comments...' : !visible.length && !empty ? 'No open discussions.' : ''));
      set($('comments-retry'), 'hidden', !view.error);
      this.renderDeletion();
      const active = view.open && this.initial.format === 'html';
      if (this.interactions.active !== active) this.interactions.setActive(active);
      if (this.interactions.selecting !== view.selecting) this.interactions.setSelecting(view.selecting);
      this.interactions.setComposerPosition(attachedDraft?.position || null);
      const pins = visible.flatMap(thread => thread.comments.slice(0, 1)).filter(comment => comment.xpath && data.comments.has(comment.id));
      const projection = JSON.stringify(pins.map(({ threadId, threadNumber, xpath, relativeX, relativeY }) => ({ threadId, threadNumber, xpath, relativeX, relativeY })));
      if (this.pins !== projection) { this.pins = projection; this.interactions.setThreads(pins); }
      if (this.attachment !== attachedDraft) {
        this.attachment = attachedDraft;
        if (!attachedDraft) this.interactions.clearSelection();
      }
      if (anchor?.isConnected && !anchor.hidden && view.open) panel.scrollTop += anchor.getBoundingClientRect().top - anchorTop;
      if (focused?.isConnected && document.activeElement === document.body && !view.deletion && view.menuId == null) {
        focused.focus({ preventScroll: true });
      }
      if (selection && document.activeElement === focused &&
          (focused.selectionStart !== selection[0] || focused.selectionEnd !== selection[1])) focused.setSelectionRange(...selection);
    }

    renderDeletion() {
      const deletion = this.state.view.deletion;
      const dialog = $('comment-delete-dialog');
      if (!deletion) { if (dialog.open) dialog.close(); return; }
      text($('comment-delete-title'), deletion.root ? 'Delete this comment thread?' : 'Delete this comment?');
      text($('comment-delete-detail'), deletion.root ? 'All replies will also be deleted.' : '');
      text($('comment-delete-error'), deletion.error);
      $('comment-delete-confirm').disabled = $('comment-delete-cancel').disabled = deletion.pending;
      text($('comment-delete-confirm'), deletion.pending ? 'Deleting...' : 'Delete');
      if (!dialog.open) {
        const bounds = this.threads.get(deletion.threadId).node.getBoundingClientRect();
        const height = Math.min(Math.max(bounds.height, 200), innerHeight - 16);
        Object.assign(dialog.style, { left: Math.max(8, bounds.left) + 'px', top: Math.max(8, Math.min(bounds.top, innerHeight - height - 8)) + 'px', width: Math.min(bounds.width, innerWidth - 16) + 'px', height: height + 'px' });
        dialog.showModal();
      }
    }
  };
})();
