// Evaluate in the existing localhost fixture tab. Production UI, picker, CSP and HTTP client;
// the fixture server replaces only persistence/authentication. Backend integration tests use PostgreSQL.
(async () => {
  const $ = id => document.getElementById(id);
  const assert = (value, message) => { if (!value) throw new Error(message); };
  const byTestId = (id, root = document) => root.querySelector('[data-testid="' + id + '"]');
  const wait = (condition, message, root = document.body) => {
    if (condition()) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const observer = new MutationObserver(() => {
        if (condition()) { observer.disconnect(); clearTimeout(timer); resolve(); }
      });
      observer.observe(root, { childList: true, subtree: true, attributes: true, characterData: true });
      const timer = setTimeout(() => { observer.disconnect(); reject(new Error(message)); }, 15000);
    });
  };
  const request = async (path, method, body) => {
    const response = await fetch('/p/' + document.body.dataset.id + path, {
      method, headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': document.body.dataset.csrf },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    assert(response.ok, 'HTTP ' + response.status);
    return response.status === 204 ? null : response.json();
  };
  const comment = await request('/comments', 'POST', { body: 'Emoji reaction regression\nSecond line of feedback', version: Number(document.body.dataset.version) });
  const popup = $('reaction-picker');
  const ids = popup.dataset;
  const row = () => document.querySelector('[data-testid="' + document.body.dataset.commentTestid + '"][data-comment-id="' + comment.id + '"]');
  const toggle = byTestId($('comments-toggle').dataset.testid);
  assert(toggle.getAttribute('aria-expanded') === 'false', 'Start this test with discussions closed');
  toggle.click();
  await wait(row, 'Comment was not loaded');
  const originalRow = row();
  const add = () => byTestId(ids.addTestid, row());
  const actionBounds = add().getBoundingClientRect();
  const iconBounds = add().querySelector('svg').getBoundingClientRect();
  assert(Math.abs((actionBounds.left + actionBounds.right) / 2 - (iconBounds.left + iconBounds.right) / 2) <= 1 &&
    Math.abs((actionBounds.top + actionBounds.bottom) / 2 - (iconBounds.top + iconBounds.bottom) / 2) <= 1,
    'Reaction icon is not centered inside its button');
  assert(Math.abs(add().getBoundingClientRect().bottom - row().querySelector('.comment-body').getBoundingClientRect().bottom) <= 4,
    'Reaction action is not aligned to the bottom of the comment text');
  add().click();
  await wait(() => popup.querySelector('em-emoji-picker')?.shadowRoot.querySelector('input'), 'Picker failed to load');
  let shadow = popup.querySelector('em-emoji-picker').shadowRoot;
  await wait(() => byTestId(ids.categoryTestid, shadow), 'Picker controls were not tagged', shadow);
  const scroll = shadow.querySelector('.scroll');
  assert(scroll.scrollHeight > scroll.clientHeight && scroll.clientHeight < 435, 'Emoji list is not constrained to its scroll container');
  const nature = shadow.querySelector('[data-testid="' + ids.categoryTestid + '"][aria-label="Animals & Nature"]');
  nature.click();
  await wait(() => nature.getAttribute('aria-selected') === 'true' && scroll.scrollTop > 0, 'Category selection did not navigate the emoji grid', shadow);
  assert(!shadow.querySelector('[aria-label*="skin" i]'), 'Skin-tone control was exposed');
  const search = byTestId(ids.searchTestid, shadow);
  search.value = 'thumb';
  search.dispatchEvent(new Event('input', { bubbles: true }));
  await wait(() => shadow.querySelector('[data-testid="' + ids.emojiTestid + '"][aria-label="👍"]'), 'Emoji search did not return thumbs up', shadow);
  shadow.querySelector('[data-testid="' + ids.emojiTestid + '"][aria-label="👍"]').click();
  const pill = emoji => row().querySelector('[data-testid="' + ids.pillTestid + '"][data-emoji="' + emoji + '"]');
  await wait(() => pill('👍')?.getAttribute('aria-pressed') === 'true', 'Selected emoji was not reflected in canonical data');
  assert(!popup.matches(':popover-open'), 'Selecting an emoji did not close the picker');
  assert(pill('👍').textContent === '👍 1', 'Reaction count is incorrect');
  assert(pill('👍').title.includes('Browser tester'), 'Reaction tooltip does not name the member');
  assert(row() === originalRow, 'Reaction replaced the comment card');
  assert((await request('/comments', 'GET')).find(item => item.id === comment.id).reactions[0].emoji === '👍', 'Reaction was not saved');
  add().click();
  await wait(() => popup.querySelector('.reaction-quick'), 'Quick reactions did not load');
  popup.querySelector('[data-testid="' + ids.quickTestid + '"][data-emoji="🎉"]').click();
  await wait(() => pill('🎉'), 'Second distinct reaction did not appear');
  pill('👍').click();
  await wait(() => !pill('👍') && !pill('🎉').disabled, 'Removing one emoji removed the wrong reaction');
  add().click();
  await wait(() => popup.querySelector('em-emoji-picker')?.shadowRoot.querySelector('input'), 'Picker did not reopen');
  shadow = popup.querySelector('em-emoji-picker').shadowRoot;
  shadow.querySelector('input').dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true }));
  assert(!popup.matches(':popover-open') && !$('comments-panel').hidden, 'Escape closed discussions instead of just the picker');
  assert(document.activeElement === add(), 'Escape did not return focus to the reaction action');
  add().click();
  byTestId($('comments-close').dataset.testid).click();
  assert(!popup.matches(':popover-open'), 'Closing discussions left the picker open');
  await request('/comments/' + comment.id, 'DELETE');
  return { passed: ['bounded picker layout', 'category navigation', 'search', 'default variants only', 'save and remove reactions', 'multiple emojis', 'count and tooltip', 'keyed card identity', 'Escape and close dismissal'] };
})();
