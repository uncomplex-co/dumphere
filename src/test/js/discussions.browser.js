// Run in an authenticated browser on a disposable HTML or Markdown artifact using DevTools evaluate_script (format: script).
// Exercises production rendering, timers and HTTP endpoints. Creates and soft-deletes only its own test threads.
(async () => {
  const $ = id => document.getElementById(id);
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  const wait = (condition, message, root = $('comments-panel')) => {
    if (condition()) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const observer = new MutationObserver(() => {
        if (condition()) { observer.disconnect(); clearTimeout(timer); resolve(); }
      });
      observer.observe(root, { subtree: true, childList: true, characterData: true, attributes: true });
      const timer = setTimeout(() => { observer.disconnect(); reject(new Error(message)); }, 15000);
    });
  };
  const id = document.body.dataset.id;
  assert(!$('element-selection').hidden, 'Rendered-document element selection is unavailable');
  const csrf = document.body.dataset.csrf;
  const request = async (path, method, body) => {
    const response = await fetch('/p/' + id + path, {
      method, credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    assert(response.ok, 'Test HTTP request failed: ' + response.status);
    return response.status === 204 ? null : response.json();
  };
  const version = Number(document.body.dataset.version);
  const root = await request('/comments', 'POST', { body: 'Browser regression root', version });
  const thread = () => document.querySelector('.comment-thread[data-thread-id="' + root.threadId + '"]');
  const rootNode = () => document.querySelector('.comment[data-comment-id="' + root.id + '"]');
  if ($('comments-panel').hidden) $('comments-toggle').click();
  if ($('discussion-filter').getAttribute('aria-pressed') === 'true') $('discussion-filter').click();
  await wait(() => rootNode(), 'Remote root did not arrive through polling');
  rootNode().click();
  const replyInput = thread().querySelector('[data-testid="' + document.body.dataset.replyTestid + '"]');
  assert(replyInput instanceof HTMLTextAreaElement && !replyInput.closest('comment-composer').hidden, 'Idle reply field is not a real visible textarea');
  assert(replyInput.closest('form').querySelector('.comment-form-actions').hidden, 'Idle reply field shows action buttons');
  const idleBorder = getComputedStyle(replyInput).borderTopColor;
  const replyPlaceholder = replyInput.placeholder;
  replyInput.focus();
  assert(thread().querySelector('[data-testid="' + document.body.dataset.replyTestid + '"]') === replyInput && replyInput.placeholder === replyPlaceholder,
    'Focusing the reply field replaced the textarea or changed its prompt');
  assert(replyInput.placeholder === 'Reply...' && replyInput.closest('form').querySelector('[type="submit"]').textContent === 'Reply', 'Reply composer copy is incorrect');
  assert(getComputedStyle(replyInput).borderTopColor !== idleBorder, 'Focused reply textarea has no focused border style');
  const write = async (input, value) => {
    // Let the browser finish focusing a newly revealed textarea before simulating typing/selection.
    await new Promise(requestAnimationFrame);
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    if (document.activeElement !== input) input.focus();
    await new Promise(requestAnimationFrame);
    input.setSelectionRange(2, 10);
    await new Promise(requestAnimationFrame);
    assert(input.selectionStart === 2 && input.selectionEnd === 10, 'Initial text selection did not stabilize before polling');
  };
  await write(replyInput, 'Unfinished reply stays here');
  const originalRoot = rootNode();
  const originalThread = thread();
  const scroll = $('comments-panel').scrollTop;
  const reply = await request('/threads/' + root.threadId + '/comments', 'POST', { body: 'Remote reply', version });
  await wait(() => document.querySelector('.comment[data-comment-id="' + reply.id + '"]'), 'Remote reply did not arrive');
  assert(rootNode() === originalRoot && thread() === originalThread, 'Existing card nodes were replaced');
  assert(thread().querySelector('.thread-reply textarea') === replyInput, 'Reply textarea was replaced');
  assert(replyInput.value === 'Unfinished reply stays here', 'Reply draft was lost');
  assert(document.activeElement === replyInput, 'Polling stole input focus');
  assert(replyInput.selectionStart === 2 && replyInput.selectionEnd === 10, 'Polling changed the text selection');
  assert(Math.abs($('comments-panel').scrollTop - scroll) <= 1, 'Polling moved the scroll anchor');
  $('discussion-filter').click();
  assert(thread().querySelector('.thread-reply textarea') === replyInput && replyInput.value === 'Unfinished reply stays here', 'Filtering replaced a composer or lost its draft');
  replyInput.closest('comment-composer').querySelector('[type="button"]').click();
  assert(thread().querySelector('[data-testid="' + document.body.dataset.replyTestid + '"]') === replyInput && !replyInput.closest('comment-composer').hidden,
    'Cancelling a reply removed or replaced its textarea');

  rootNode().querySelector('.comment-menu-trigger').click();
  rootNode().querySelector('[data-testid="' + document.body.dataset.commentEditTestid + '"]').click();
  const editInput = rootNode().querySelector('.comment-edit-composer textarea');
  await write(editInput, 'Preserved local edit');
  await request('/comments/' + root.id, 'PATCH', { body: 'Changed by another client' });
  await wait(() => rootNode().querySelector('.comment-edit-composer [role="alert"]').textContent.includes('someone else'), 'Remote edit conflict was not shown');
  assert(editInput.value === 'Preserved local edit', 'Remote edit replaced the local draft');
  assert(rootNode().querySelector('.comment-edit-composer [type="submit"]').disabled, 'Conflicting edit could silently overwrite remote text');
  editInput.closest('comment-composer').querySelector('[type="button"]').click();
  assert(rootNode().querySelector('.comment-body').textContent === 'Changed by another client', 'Cancelling did not reveal the remote text');

  rootNode().click();
  thread().querySelector('[data-testid="' + document.body.dataset.replyTestid + '"]').focus();
  const orphan = thread().querySelector('.thread-reply textarea');
  await write(orphan, 'Keep this even if the thread is deleted');
  await request('/comments/' + root.id, 'DELETE');
  await wait(() => thread()?.querySelector('.thread-reply [role="alert"]').textContent.includes('deleted'), 'Remote deletion did not preserve and flag the draft');
  assert(orphan.value === 'Keep this even if the thread is deleted', 'Remote deletion lost a draft');
  assert(orphan.closest('comment-composer').querySelector('[type="submit"]').disabled, 'Reply to a deleted thread stayed enabled');
  orphan.closest('comment-composer').querySelector('[type="button"]').click();
  assert(!thread(), 'Deleted thread remained after draft cancellation');

  const resolutionRoot = await request('/comments', 'POST', { body: 'Resolution regression root', version });
  const resolutionThread = () => document.querySelector('.comment-thread[data-thread-id="' + resolutionRoot.threadId + '"]');
  await wait(() => resolutionThread(), 'Resolution thread did not arrive');
  if ($('discussion-filter').getAttribute('aria-pressed') === 'true') $('discussion-filter').click();
  resolutionThread().querySelector('.thread-resolve-button').click();
  await wait(() => resolutionThread()?.hidden, 'Resolve did not update local state');
  $('discussion-filter').click();
  assert(!resolutionThread().hidden, 'Show resolved did not reveal resolved history');
  const card = resolutionThread().querySelector('.comment');
  card.querySelector('.comment-menu-trigger').click();
  card.querySelector('[data-testid="' + $('discussion-filter').dataset.reopenTestid + '"]').click();
  await wait(() => !card.querySelector('.thread-resolve-button').hidden, 'Reopen did not update local state');
  await request('/comments/' + resolutionRoot.id, 'DELETE');
  await wait(() => !resolutionThread(), 'Remote deletion left a non-draft card visible');
  const originalFetch = window.fetch;
  window.fetch = (url, options) => {
    if (String(url).endsWith('/comments') && (!options?.method || options.method === 'GET')) return Promise.reject(new Error('Read deliberately unavailable'));
    return originalFetch(url, options);
  };
  try {
    $('comment-add').click();
    const input = $('comment-composer').querySelector('textarea');
    assert(input.placeholder === 'Comment...' && input.closest('form').querySelector('[type="submit"]').textContent === 'Comment', 'New-thread composer copy is incorrect');
    await write(input, 'Local root without a follow-up fetch');
    input.closest('form').requestSubmit();
    const local = () => [...document.querySelectorAll('.comment')].find(node => node.querySelector('.comment-body').textContent === 'Local root without a follow-up fetch');
    await wait(local, 'Locally created root depended on a follow-up fetch');
    const localRoot = local();
    localRoot.querySelector('.comment-menu-trigger').click();
    localRoot.querySelector('[data-testid="' + document.body.dataset.commentEditTestid + '"]').click();
    const edit = localRoot.querySelector('.comment-edit-composer textarea');
    assert(edit.placeholder === 'Edit your comment...' && edit.closest('form').querySelector('[type="submit"]').textContent === 'Save', 'Edit composer copy is incorrect');
    await write(edit, 'Locally edited without a follow-up fetch');
    edit.closest('form').requestSubmit();
    await wait(() => localRoot.querySelector('.comment-body').textContent === 'Locally edited without a follow-up fetch', 'Local edit did not render from its response');
    assert(!localRoot.querySelector('.comment-edited').hidden, 'Edited timestamp indicator did not update');
    localRoot.click();
    const localThread = localRoot.closest('.comment-thread');
    localThread.querySelector('[data-testid="' + document.body.dataset.replyTestid + '"]').focus();
    const localReplyInput = localThread.querySelector('.thread-reply textarea');
    await write(localReplyInput, 'Local reply without a follow-up fetch');
    localReplyInput.closest('form').requestSubmit();
    const localReply = () => [...localThread.querySelectorAll('.comment')].find(node => node.querySelector('.comment-body').textContent === 'Local reply without a follow-up fetch');
    await wait(localReply, 'Local reply did not render from its response');
    const replyNode = localReply();
    const openDelete = node => {
      node.querySelector('.comment-menu-trigger').click();
      node.querySelector('[data-testid="' + $('comment-delete-dialog').dataset.actionTestid + '"]').click();
    };
    openDelete(replyNode);
    assert($('comment-delete-dialog').open && $('comment-delete-title').textContent === 'Delete this comment?', 'Reply deletion confirmation is incorrect');
    $('comment-delete-cancel').click();
    assert(replyNode.isConnected, 'Cancelling deletion removed the reply');
    openDelete(replyNode);
    $('comment-delete-confirm').click();
    await wait(() => !replyNode.isConnected, 'Local reply deletion depended on a follow-up fetch');
    assert(localRoot.isConnected, 'Deleting a reply also removed its root');
    openDelete(localRoot);
    assert($('comment-delete-title').textContent === 'Delete this comment thread?', 'Root deletion confirmation is incorrect');
    $('comment-delete-confirm').click();
    await wait(() => !localThread.isConnected, 'Local thread deletion depended on a follow-up fetch');
  } finally { window.fetch = originalFetch; }
  if ($('element-selection').getAttribute('aria-pressed') !== 'true') $('element-selection').click();
  await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  const wrapper = $('artifact-frame').contentDocument;
  const contentFrame = wrapper.getElementById('artifact-document');
  const paragraph = contentFrame.contentDocument.querySelector('p');
  const bounds = paragraph.getBoundingClientRect();
  paragraph.dispatchEvent(new contentFrame.contentWindow.MouseEvent('click', { bubbles: true, clientX: bounds.left + bounds.width / 2, clientY: bounds.top + bounds.height / 2 }));
  await wait(() => !$('attached-composer').hidden, 'Element selection did not open an attached composer', $('attached-composer'));
  const attached = $('attached-comment-composer').querySelector('textarea');
  await write(attached, 'Anchored comment from state');
  const popup = $('attached-composer');
  const handle = $('attached-comment-drag');
  const originalPosition = popup.getBoundingClientRect();
  const overlayBounds = $('artifact-annotations').getBoundingClientRect();
  const direction = originalPosition.right + 40 < overlayBounds.right ? 'ArrowRight' : 'ArrowLeft';
  handle.focus();
  handle.dispatchEvent(new KeyboardEvent('keydown', { key: direction, shiftKey: true, bubbles: true }));
  const movedPosition = popup.getBoundingClientRect();
  assert(Math.abs(movedPosition.left - originalPosition.left) >= 10, 'Drag handle did not provide keyboard movement');
  const whileMoving = await request('/comments', 'POST', { body: 'External update while popup is moved', version });
  await wait(() => document.querySelector('.comment[data-comment-id="' + whileMoving.id + '"]'), 'Background update did not arrive during popup composition');
  assert(Math.abs(popup.getBoundingClientRect().left - movedPosition.left) <= 1, 'Polling reset the manually placed popup');
  assert(attached.value === 'Anchored comment from state', 'Moving or polling lost the anchored draft');
  handle.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
  assert(Math.abs(popup.getBoundingClientRect().left - originalPosition.left) <= 1, 'Home did not restore automatic popup placement');
  handle.dispatchEvent(new KeyboardEvent('keydown', { key: direction, shiftKey: true, bubbles: true }));
  attached.closest('form').requestSubmit();
  const anchored = () => [...document.querySelectorAll('.comment')].find(node => node.querySelector('.comment-body').textContent === 'Anchored comment from state');
  await wait(anchored, 'Anchored comment was not added to local data');
  const anchoredId = Number(anchored().dataset.commentId);
  const anchoredThread = anchored().dataset.threadId;
  const saved = (await request('/comments', 'GET')).find(comment => comment.id === anchoredId);
  assert(saved.xpath.endsWith('/p[1]') && Math.abs(saved.relativeX - 0.5) < 0.01, 'Moving the popup changed the comment anchor');
  await wait(() => wrapper.querySelector('[data-thread-id="' + anchoredThread + '"]'), 'Pin projection did not follow local comment data', wrapper.documentElement);
  assert(!anchored().querySelector('.comment-thread-number').hidden, 'Anchored comment lost its server-assigned number');
  const pinTestid = wrapper.getElementById('artifact-annotations').dataset.pinTestid;
  const pin = () => wrapper.querySelector('[data-testid="' + pinTestid + '"][data-thread-id="' + anchoredThread + '"]');
  pin().click();
  assert(anchored().closest('.comment-thread').classList.contains('is-selected'), 'Pin did not select its discussion thread');
  $('comments-close').click();
  $('comments-toggle').click();
  await wait(() => pin() && !pin().hidden, 'Reopening did not restore the persisted pin', wrapper.documentElement);
  await request('/comments/' + anchoredId, 'DELETE');
  await request('/comments/' + whileMoving.id, 'DELETE');
  await wait(() => !anchored(), 'Deleted anchored comment did not disappear');
  $('comments-close').click();
  assert(!$('comments-toggle').classList.contains('has-unread'), 'Closing an up-to-date panel created an unread dot');
  const notificationRoot = await request('/comments', 'POST', { body: 'Closed-panel notification', version });
  await wait(() => $('comments-toggle').classList.contains('has-unread'), 'Closed panel stopped syncing or did not show an unread dot', document.body);
  assert($('comments-panel').hidden, 'A background update opened the panel');
  assert($('comments-toggle').getAttribute('aria-label').includes('unread'), 'Notification dot has no accessible description');
  $('comments-toggle').click();
  assert(!$('comments-toggle').classList.contains('has-unread'), 'Opening the panel did not acknowledge unread updates');
  assert(!$('comments-toggle').getAttribute('aria-label').includes('unread'), 'Accessible unread description was not cleared');
  const notificationCard = () => document.querySelector('.comment[data-comment-id="' + notificationRoot.id + '"]');
  await wait(notificationCard, 'Closed-panel update was not available when opened');
  $('comments-close').click();
  await request('/comments/' + notificationRoot.id, 'PATCH', { body: 'Edited while closed' });
  await wait(() => $('comments-toggle').classList.contains('has-unread'), 'Remote edits did not notify while closed', document.body);
  $('comments-toggle').click();
  assert(notificationCard().querySelector('.comment-body').textContent === 'Edited while closed', 'Closed-panel sync did not merge the edit');
  $('comments-close').click();
  await request('/comments/' + notificationRoot.id, 'DELETE');
  await wait(() => $('comments-toggle').classList.contains('has-unread'), 'Remote deletion did not notify while closed', document.body);
  $('comments-toggle').click();
  assert(!notificationCard(), 'Closed-panel sync did not merge deletion');
  assert(!$('comments-toggle').classList.contains('has-unread'), 'Opening after deletion did not clear notification');
  return { passed: ['live root and reply', 'keyed DOM identity', 'draft, focus, selection and scroll', 'local filtering', 'remote edit conflict', 'remote deletion with draft retention', 'resolve and reopen', 'create/edit/reply/delete with reads unavailable', 'anchored composer and pin projection', 'closed-panel sync and accessible unread acknowledgement'] };
})();
