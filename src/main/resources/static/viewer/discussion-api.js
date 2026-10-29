class DiscussionApi {
  constructor(id, csrf) {
    this.base = '/p/' + encodeURIComponent(id);
    this.csrf = csrf;
  }

  async request(path, method = 'GET', body) {
    let response;
    try {
      response = await fetch(this.base + path, {
        method, credentials: 'same-origin', cache: 'no-store',
        signal: AbortSignal.timeout(15000),
        headers: method === 'GET' ? {} : { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': this.csrf },
        body: body === undefined ? undefined : JSON.stringify(body)
      });
    } catch {
      throw new Error('Could not connect. Your draft is safe. Check your connection and try again.');
    }
    if (!response.ok) throw new Error([401, 403].includes(response.status)
      ? 'Your access may have changed. Reload the page to continue.'
      : response.status === 404 ? 'This comment or thread is no longer available.' : 'Could not update discussions. Try again.');
    return response.status === 204 ? null : response.json();
  }

  async list() {
    const comments = await this.request('/comments');
    if (!Array.isArray(comments)) throw new Error('Could not load discussions. Try again.');
    return comments;
  }

  save(composer) {
    if (composer.mode === 'edit') return this.request('/comments/' + composer.commentId, 'PATCH', { body: composer.body });
    if (composer.mode === 'reply') return this.request('/threads/' + composer.threadId + '/comments', 'POST', { body: composer.body, version: composer.version });
    return this.request('/comments', 'POST', { body: composer.body, version: composer.version, ...composer.anchor });
  }

  resolve(threadId, resolved) { return this.request('/threads/' + threadId + '/resolution', 'PATCH', { resolved }); }
  remove(commentId) { return this.request('/comments/' + commentId, 'DELETE'); }
}
