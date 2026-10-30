class CommentComposer extends HTMLElement {
  connectedCallback() {
    if (this.form) return;
    this.innerHTML = `
      <form class="comment-form">
        <label><span class="composer-label"></span>
          <textarea rows="4" maxlength="10000" required placeholder="Comment..."></textarea>
        </label>
        <p class="error" role="alert"></p>
        <div class="comment-form-actions">
          <button class="button" type="button">Cancel</button>
          <button class="button primary" type="submit">Comment</button>
        </div>
      </form>`;
    this.form = this.querySelector('form');
    this.input = this.querySelector('textarea');
    this.error = this.querySelector('[role="alert"]');
    this.cancelButton = this.querySelector('[type="button"]');
    this.postButton = this.querySelector('[type="submit"]');
    this.querySelector('.composer-label').textContent = this.getAttribute('label') || 'New comment';
    this.input.placeholder = this.getAttribute('placeholder') || 'Comment...';
    this.postLabel = this.getAttribute('submit-label') || 'Comment';
    this.postButton.textContent = this.postLabel;
    if (this.hasAttribute('compact')) this.input.rows = 1;
    this.input.dataset.testid = this.dataset.bodyTestid;
    this.cancelButton.dataset.testid = this.dataset.cancelTestid;
    this.postButton.dataset.testid = this.dataset.postTestid;
    this.model = null;

    this.input.addEventListener('input', () => {
      const body = this.input.value;
      this.activate();
      this.dispatchEvent(new CustomEvent('comment-input', { bubbles: true, detail: { body } }));
      this.resizeInput();
    });
    this.input.addEventListener('focus', () => this.activate());

    this.input.addEventListener('keydown', event => {
      if (event.key === 'Enter' && event.ctrlKey && !event.isComposing) {
        event.preventDefault();
        if (!this.submitting && this.input.value.trim()) this.form.requestSubmit();
      }
    });

    this.cancelButton.addEventListener('click', () => {
      if (this.submitting) return;
      this.dispatchEvent(new CustomEvent('comment-cancel', { bubbles: true }));
    });
    this.form.addEventListener('submit', event => {
      event.preventDefault();
      if (this.submitting) return;
      const body = this.input.value.trim();
      if (!body) {
        this.input.focus();
        return;
      }
      this.dispatchEvent(new CustomEvent('comment-submit', { bubbles: true, detail: { body } }));
    });
  }

  render(model) {
    if (!this.form) return;
    const signature = JSON.stringify([!!model?.open, model?.body || '', !!model?.pending,
      model?.error || '', !!model?.remoteDeleted, !!model?.remoteChanged]);
    if (this.model === signature) return;
    this.model = signature;
    const wasOpen = !this.hidden;
    const oldPending = this.submitting;
    this.activeDraft = !!model?.open;
    this.hidden = !model?.open && !this.hasAttribute('persistent');
    this.querySelector('.comment-form-actions').hidden = !model?.open;
    this.submitting = !!model?.pending;
    const body = model?.body || '';
    if (this.input.value !== body) this.input.value = body;
    const conflict = model?.remoteDeleted ? 'This comment or thread was deleted. Your draft is preserved; copy it before cancelling.'
      : model?.remoteChanged ? 'This comment was edited by someone else. Your draft is preserved. Copy it, cancel, and reopen to review their changes.' : '';
    this.error.textContent = model?.error || conflict;
    this.postButton.disabled = this.submitting || !body.trim() || !!model?.remoteDeleted || !!model?.remoteChanged;
    this.cancelButton.disabled = this.submitting;
    this.input.readOnly = this.submitting;
    this.postButton.textContent = this.submitting ? (this.getAttribute('pending-label') || 'Posting...') : this.postLabel;
    if (!this.hidden) this.resizeInput();
    if (wasOpen !== !this.hidden || oldPending !== this.submitting) this.dispatchEvent(new Event('composer-statechange', { bubbles: true }));
  }

  activate() {
    if (this.hasAttribute('persistent') && !this.activeDraft && !this.input.disabled) {
      this.dispatchEvent(new CustomEvent('comment-activate', { bubbles: true }));
    }
  }

  resizeInput() {
    if (!this.hasAttribute('compact')) return;
    this.input.style.height = 'auto';
    this.input.style.height = Math.min(180, this.input.scrollHeight + 2) + 'px';
  }
}

customElements.define('comment-composer', CommentComposer);
