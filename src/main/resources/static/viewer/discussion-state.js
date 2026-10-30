// Discussion records belong to data. Drafts and interaction state belong to view.
class DiscussionState {
  constructor(api) {
    this.api = api;
    this.data = { comments: new Map(), loaded: false };
    this.view = {
      open: false, showResolved: false, selectedThreadId: null, selecting: false, menuId: null,
      canView: true, canManage: false, version: 1, loading: false, error: '', unread: false,
      composers: new Map(), deletion: null, attached: new Set(), resolutions: new Set(), drag: null,
      reactionPicker: null, reacting: new Set()
    };
    this.listeners = new Set();
    this.generation = 0;
    this.writes = 0;
    this.queues = new Map();
  }

  subscribe(listener) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  change(patch) {
    Object.assign(this.view, patch);
    if (this.view.open) this.view.unread = false;
    this.emit();
  }

  close() {
    for (const [key, composer] of this.view.composers) {
      if (composer.mode !== 'edit') this.view.composers.delete(key);
    }
    Object.assign(this.view, { open: false, selecting: false, menuId: null, drag: null, reactionPicker: null });
    this.emit();
  }

  access(access) {
    if (Object.entries(access).every(([key, value]) => this.view[key] === value)) return;
    Object.assign(this.view, access);
    if (!access.canView) {
      this.generation++;
      this.data.comments.clear();
      this.data.loaded = false;
      this.view.composers.clear();
      Object.assign(this.view, { open: false, selecting: false, deletion: null, selectedThreadId: null, error: '', unread: false, drag: null, reactionPicker: null });
    }
    this.emit();
  }

  async refresh() {
    if (!this.view.canView || this.writes || this.request) return;
    const generation = this.generation;
    this.view.loading = true;
    this.emit();
    const request = this.api.list();
    this.request = request;
    try {
      const comments = await request;
      if (generation !== this.generation || this.writes || !this.view.canView) return;
      const previous = this.data.comments;
      // createdAt is immutable; PostgreSQL may round its precision after a local write.
      const changed = previous.size !== comments.length || comments.some(comment => {
        const old = previous.get(comment.id);
        return !old || !this.sameComment(old, comment, true);
      });
      if (this.data.loaded && changed && !this.view.open) this.view.unread = true;
      this.data.comments = new Map(comments.map(comment => {
        const old = previous.get(comment.id);
        return [comment.id, old && this.sameComment(old, comment) ? old : comment];
      }));
      this.data.loaded = true;
      this.view.error = '';
      this.checkDrafts();
    } catch (error) {
      if (generation === this.generation && this.view.canView) this.view.error = error.message;
    } finally {
      if (this.request === request) this.request = null;
      this.view.loading = false;
      this.emit();
    }
  }

  sameComment(a, b, ignoreCreatedAt = false) {
    return ['id', 'threadId', 'threadNumber', 'body', 'author', 'createdAt', 'editedAt',
      'xpath', 'relativeX', 'relativeY', 'canEdit', 'resolvedAt'].every(key => (ignoreCreatedAt && key === 'createdAt') || a[key] === b[key])
      && JSON.stringify(a.reactions || []) === JSON.stringify(b.reactions || []);
  }

  compose(key, options) {
    const existing = this.view.composers.get(key);
    if (existing?.open) return;
    const comment = options.commentId ? this.data.comments.get(options.commentId) : null;
    const context = [...this.data.comments.values()].filter(item => item.threadId === options.threadId);
    if (options.mode === 'edit' && !comment?.canEdit) return;
    if (options.mode === 'reply' && !context.length) return;
    this.view.composers.set(key, {
      ...options, context, open: true, body: comment?.body || '', version: this.view.version,
      baseBody: comment?.body, baseEditedAt: comment?.editedAt, pending: false, error: '',
      remoteChanged: false, remoteDeleted: false
    });
    if (options.threadId) this.view.selectedThreadId = options.threadId;
    if (key === 'attached') { this.view.selecting = false; this.view.drag = null; }
    this.emit();
  }

  draft(key, body) {
    const composer = this.view.composers.get(key);
    if (!composer || composer.pending) return;
    composer.body = body;
    composer.error = '';
    this.emit();
  }

  cancel(key) {
    if (this.view.composers.get(key)?.pending) return;
    this.view.composers.delete(key);
    if (key === 'attached') { this.view.selecting = false; this.view.drag = null; }
    this.emit();
  }

  moveAttached(position) {
    const composer = this.view.composers.get('attached');
    if (!composer) return;
    composer.position = position;
    this.emit();
  }

  checkDrafts() {
    for (const composer of this.view.composers.values()) {
      const current = this.data.comments.get(composer.commentId);
      if (composer.mode === 'edit') {
        composer.remoteDeleted = !current;
        composer.remoteChanged = !!current && (current.body !== composer.baseBody || current.editedAt !== composer.baseEditedAt);
      } else if (composer.mode === 'reply') {
        composer.remoteDeleted = ![...this.data.comments.values()].some(comment => comment.threadId === composer.threadId);
      }
    }
  }

  async mutate(operation, apply, key) {
    this.generation++;
    this.writes++;
    // Serialize local writes to a thread, so a late edit/reply cannot undo its deletion or resolution.
    const previous = this.queues.get(key) || Promise.resolve();
    const execution = previous.catch(() => {}).then(async () => {
      if (!this.view.canView) throw new Error('Your access has changed. Reload the page to continue.');
      const result = await operation();
      if (this.view.canView) {
        apply(result);
        this.view.error = '';
        this.checkDrafts();
      }
    });
    this.queues.set(key, execution);
    try {
      await execution;
      return true;
    } finally {
      if (this.queues.get(key) === execution) this.queues.delete(key);
      this.generation++;
      this.writes--;
      this.emit();
    }
  }

  async submit(key) {
    const composer = this.view.composers.get(key);
    if (!composer || composer.pending || composer.remoteDeleted || !this.view.canView) return false;
    if (composer.remoteChanged) {
      composer.error = 'This comment changed while you were editing. Copy your draft, cancel, and reopen the editor to review the latest version.';
      this.emit();
      return false;
    }
    const body = composer.body.trim();
    if (!body) return false;
    composer.pending = true;
    composer.error = '';
    this.emit();
    try {
      return await this.mutate(() => this.api.save({ ...composer, body }), comment => {
        this.data.comments.set(comment.id, comment);
        if (this.view.composers.get(key) === composer) {
          this.view.composers.delete(key);
          this.view.selectedThreadId = comment.threadId;
          if (key === 'attached') { this.view.selecting = false; this.view.drag = null; }
        }
      }, composer.threadId || key);
    } catch (error) {
      composer.error = error.message;
      return false;
    } finally {
      composer.pending = false;
      this.emit();
    }
  }

  async resolve(threadId, resolved) {
    if (this.view.resolutions.has(threadId) || !this.view.canManage) return false;
    this.view.resolutions.add(threadId);
    this.emit();
    try {
      return await this.mutate(() => this.api.resolve(threadId, resolved), state => {
        for (const [id, comment] of this.data.comments) {
          if (comment.threadId === threadId) this.data.comments.set(id, { ...comment, resolvedAt: state.resolvedAt });
        }
      }, threadId);
    } catch (error) {
      this.view.error = error.message;
      return false;
    } finally {
      this.view.resolutions.delete(threadId);
      this.emit();
    }
  }

  async react(commentId, emoji) {
    const comment = this.data.comments.get(commentId);
    if (!comment || !this.view.canManage || this.view.reacting.has(commentId)) return false;
    const reacted = !comment.reactions?.find(reaction => reaction.emoji === emoji)?.reacted;
    this.view.reactionPicker = null;
    this.view.reacting.add(commentId);
    this.emit();
    try {
      return await this.mutate(() => this.api.react(commentId, emoji, reacted), result => {
        const current = this.data.comments.get(commentId);
        if (current) this.data.comments.set(commentId, { ...current, reactions: result.reactions });
      }, comment.threadId);
    } catch (error) {
      this.view.error = error.message;
      return false;
    } finally {
      this.view.reacting.delete(commentId);
      this.emit();
    }
  }

  requestDelete(commentId) {
    const comment = this.data.comments.get(commentId);
    if (!comment?.canEdit) return;
    const thread = this.threads().find(item => item.id === comment.threadId);
    this.change({ deletion: { commentId, threadId: comment.threadId, root: thread.comments[0].id === commentId, pending: false, error: '' } });
  }

  cancelDelete() {
    if (!this.view.deletion?.pending) this.change({ deletion: null });
  }

  async delete() {
    const deletion = this.view.deletion;
    if (!deletion || deletion.pending || !this.view.canView) return false;
    deletion.pending = true;
    deletion.error = '';
    this.emit();
    try {
      return await this.mutate(() => this.api.remove(deletion.commentId), () => {
        for (const [id, comment] of this.data.comments) {
          if (id === deletion.commentId || (deletion.root && comment.threadId === deletion.threadId)) this.data.comments.delete(id);
        }
        if (deletion.root) {
          for (const [key, composer] of this.view.composers) {
            if (composer.threadId === deletion.threadId) this.view.composers.delete(key);
          }
        }
        if (deletion.root && this.view.selectedThreadId === deletion.threadId) this.view.selectedThreadId = null;
        this.view.deletion = null;
      }, deletion.threadId);
    } catch (error) {
      deletion.error = error.message;
      return false;
    } finally {
      deletion.pending = false;
      this.emit();
    }
  }

  threads() {
    const records = new Map(this.data.comments);
    // Keep a remotely deleted conversation visible while its local draft is open.
    for (const composer of this.view.composers.values()) {
      const threadExists = [...this.data.comments.values()].some(comment => comment.threadId === composer.threadId);
      const context = composer.context.filter((comment, index) => comment.id === composer.commentId || (!threadExists && index === 0));
      for (const comment of context) if (!records.has(comment.id)) records.set(comment.id, comment);
    }
    const groups = new Map();
    for (const comment of records.values()) {
      if (!groups.has(comment.threadId)) groups.set(comment.threadId, { id: comment.threadId, comments: [], resolvedAt: comment.resolvedAt });
      const thread = groups.get(comment.threadId);
      thread.comments.push(comment);
    }
    const threads = [...groups.values()];
    for (const thread of threads) {
      thread.comments.sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt) || a.id - b.id);
      thread.hasDraft = [...this.view.composers.values()].some(composer => composer.threadId === thread.id);
      const draft = [...this.view.composers.values()].find(composer => composer.threadId === thread.id);
      thread.displayResolved = draft ? draft.context[0]?.resolvedAt : thread.resolvedAt;
    }
    return threads.sort((a, b) => b.id - a.id);
  }

  visibleThreads() {
    return this.threads().filter(thread => this.view.showResolved || !thread.resolvedAt || thread.hasDraft);
  }

  emit() {
    const threads = this.threads();
    if (this.view.deletion && !this.data.comments.has(this.view.deletion.commentId) && !this.view.deletion.pending) this.view.deletion = null;
    if (!this.data.comments.has(this.view.menuId)) this.view.menuId = null;
    if (!this.view.open || !this.view.canManage || !this.data.comments.has(this.view.reactionPicker)) this.view.reactionPicker = null;
    if (!threads.some(thread => thread.id === this.view.selectedThreadId)) this.view.selectedThreadId = null;
    for (const listener of this.listeners) listener();
  }
}
