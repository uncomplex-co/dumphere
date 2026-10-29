const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const State = vm.runInThisContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/viewer/discussion-state.js'), 'utf8') + '\nDiscussionState;');
const comment = (id, threadId = id, extra = {}) => ({
  id, threadId, threadNumber: threadId, body: 'Comment ' + id, author: 'Team member',
  createdAt: '2026-10-29T12:00:' + String(id).padStart(2, '0') + 'Z',
  editedAt: null, resolvedAt: null, canEdit: true, xpath: null, ...extra
});
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const setup = async records => {
  let snapshot = records;
  const api = { list: async () => structuredClone(snapshot) };
  const state = new State(api);
  state.access({ canView: true, canManage: true, version: 1 });
  await state.refresh();
  return { state, api, snapshot: value => { snapshot = value; } };
};

test('remote threads, replies, edits, resolution and deletion merge without touching a local draft', async () => {
  const { state, snapshot } = await setup([comment(1), comment(2)]);
  state.compose('reply:1', { mode: 'reply', threadId: 1 });
  state.draft('reply:1', 'Unfinished local reply');
  state.change({ selectedThreadId: 1 });
  snapshot([comment(1, 1, { body: 'Edited remotely', editedAt: '2026-10-29T12:01:00Z', resolvedAt: '2026-10-29T12:02:00Z' }), comment(3, 1), comment(4)]);
  await state.refresh();
  assert.equal(state.data.comments.get(1).body, 'Edited remotely');
  assert.equal(state.data.comments.get(1).resolvedAt, '2026-10-29T12:02:00Z');
  assert.equal(state.data.comments.has(2), false);
  assert.equal(state.data.comments.get(3).threadId, 1);
  assert.equal(state.data.comments.get(4).body, 'Comment 4');
  assert.equal(state.view.composers.get('reply:1').body, 'Unfinished local reply');
  assert.equal(state.view.selectedThreadId, 1);
  assert.ok(state.visibleThreads().some(thread => thread.id === 1), 'A remotely resolved thread stays visible while its draft is open');
});

test('unchanged records keep identity while changed records are replaced', async () => {
  const { state, snapshot } = await setup([comment(1), comment(2)]);
  const original = state.data.comments.get(1);
  const changed = state.data.comments.get(2);
  snapshot([comment(1), comment(2, 2, { author: 'New display name' })]);
  await state.refresh();
  assert.equal(state.data.comments.get(1), original);
  assert.notEqual(state.data.comments.get(2), changed);
  assert.equal(state.data.comments.get(2).author, 'New display name');
});

test('a poll started before an edit cannot undo the successful edit', async () => {
  const { state, api } = await setup([comment(1)]);
  const poll = deferred();
  api.list = () => poll.promise;
  const reading = state.refresh();
  api.save = async () => comment(1, 1, { body: 'Saved edit', editedAt: '2026-10-29T12:02:00Z' });
  state.compose('edit:1', { mode: 'edit', threadId: 1, commentId: 1 });
  state.draft('edit:1', 'Saved edit');
  assert.equal(await state.submit('edit:1'), true);
  poll.resolve([comment(1)]);
  await reading;
  assert.equal(state.data.comments.get(1).body, 'Saved edit');
  assert.equal(state.data.comments.get(1).editedAt, '2026-10-29T12:02:00Z');
  assert.equal(state.view.composers.has('edit:1'), false);
});

test('remote edit preserves the draft, marks a conflict, and prevents silently overwriting it', async () => {
  const { state, snapshot, api } = await setup([comment(1)]);
  state.compose('edit:1', { mode: 'edit', threadId: 1, commentId: 1 });
  state.draft('edit:1', 'My own text');
  snapshot([comment(1, 1, { body: 'Their text', editedAt: '2026-10-29T12:03:00Z' })]);
  await state.refresh();
  api.save = () => { throw new Error('Must not send a conflicting edit'); };
  assert.equal(state.view.composers.get('edit:1').remoteChanged, true);
  assert.equal(state.view.composers.get('edit:1').body, 'My own text');
  assert.equal(await state.submit('edit:1'), false);
  assert.equal(state.data.comments.get(1).body, 'Their text');
});

test('remote deletion retains an editor or reply draft until cancellation, without resurrecting data', async () => {
  for (const mode of ['edit', 'reply']) {
    const { state, snapshot } = await setup([comment(1), comment(2, 1)]);
    state.compose('draft', { mode, threadId: 1, commentId: mode === 'edit' ? 2 : undefined });
    state.draft('draft', 'Do not lose this');
    snapshot([]);
    await state.refresh();
    assert.equal(state.data.comments.size, 0);
    assert.equal(state.view.composers.get('draft').body, 'Do not lose this');
    assert.equal(state.view.composers.get('draft').remoteDeleted, true);
    assert.ok(state.visibleThreads().some(thread => thread.id === 1));
    if (mode === 'edit') assert.ok(state.threads()[0].comments.some(item => item.id === 2));
    assert.equal(await state.submit('draft'), false);
    state.cancel('draft');
    assert.equal(state.visibleThreads().length, 0);
  }
});

test('local create, reply, resolve, reopen, and delete update data without a follow-up fetch', async () => {
  const { state, api } = await setup([]);
  api.list = () => { throw new Error('No follow-up read required'); };
  api.save = async composer => comment(composer.mode === 'reply' ? 2 : 1, 1, { body: composer.body });
  state.compose('new', { mode: 'new' });
  state.draft('new', 'Root');
  assert.equal(await state.submit('new'), true);
  assert.equal(state.data.comments.get(1).body, 'Root');
  state.compose('reply:1', { mode: 'reply', threadId: 1 });
  state.draft('reply:1', 'Reply');
  assert.equal(await state.submit('reply:1'), true);
  assert.equal(state.data.comments.get(2).body, 'Reply');
  api.resolve = async (threadId, resolved) => ({ threadId, resolvedAt: resolved ? '2026-10-29T12:03:00Z' : null });
  assert.equal(await state.resolve(1, true), true);
  assert.equal(state.visibleThreads().length, 0);
  state.change({ showResolved: true });
  assert.equal(state.visibleThreads().length, 1);
  assert.equal(await state.resolve(1, false), true);
  assert.equal(state.data.comments.get(2).resolvedAt, null);
  api.remove = async () => null;
  state.requestDelete(2);
  assert.equal(state.view.deletion.root, false);
  assert.equal(await state.delete(), true);
  assert.equal(state.data.comments.has(1), true);
  assert.equal(state.data.comments.has(2), false);
  state.requestDelete(1);
  assert.equal(state.view.deletion.root, true);
  assert.equal(await state.delete(), true);
  assert.equal(state.data.comments.size, 0);
});

test('failed writes retain drafts and reset pending state', async () => {
  const { state, api } = await setup([comment(1)]);
  api.save = async () => { throw new Error('Network unavailable'); };
  state.compose('reply:1', { mode: 'reply', threadId: 1 });
  state.draft('reply:1', 'Keep me');
  assert.equal(await state.submit('reply:1'), false);
  const draft = state.view.composers.get('reply:1');
  assert.equal(draft.body, 'Keep me');
  assert.equal(draft.pending, false);
  assert.equal(draft.error, 'Network unavailable');
  assert.equal(state.data.comments.size, 1);
});

test('late polls cannot repopulate discussions after access is revoked', async () => {
  const { state, api } = await setup([comment(1)]);
  const poll = deferred();
  api.list = () => poll.promise;
  const reading = state.refresh();
  state.compose('edit:1', { mode: 'edit', threadId: 1, commentId: 1 });
  state.draft('edit:1', 'Sensitive draft');
  state.access({ canView: false, canManage: false, version: 1 });
  poll.resolve([comment(1)]);
  await reading;
  assert.equal(state.data.comments.size, 0);
  assert.equal(state.view.composers.size, 0);
  assert.equal(state.view.open, false);
});

test('thread-local writes are ordered so a late reply cannot resurrect a deleted thread', async () => {
  const { state, api } = await setup([comment(1)]);
  const reply = deferred();
  api.save = () => reply.promise;
  api.remove = async () => null;
  state.compose('reply:1', { mode: 'reply', threadId: 1 });
  state.draft('reply:1', 'Reply before deletion');
  const writing = state.submit('reply:1');
  state.requestDelete(1);
  const deleting = state.delete();
  reply.resolve(comment(2, 1));
  assert.equal(await writing, true);
  assert.equal(await deleting, true);
  assert.equal(state.data.comments.size, 0);
  assert.equal(state.view.deletion, null);
});

test('background refresh preserves existing thread order during composition, then reorders after cancellation', async () => {
  const { state, snapshot } = await setup([comment(1), comment(2)]);
  assert.deepEqual(state.view.order, [2, 1]);
  state.compose('reply:2', { mode: 'reply', threadId: 2 });
  snapshot([comment(1), comment(2), comment(3, 1)]);
  await state.refresh();
  assert.deepEqual(state.view.order, [2, 1]);
  state.cancel('reply:2');
  assert.deepEqual(state.view.order, [1, 2]);
});

test('the first snapshot establishes a baseline and unchanged snapshots do not produce unread notifications', async () => {
  const { state } = await setup([comment(1)]);
  assert.equal(state.data.loaded, true);
  assert.equal(state.view.open, false);
  assert.equal(state.view.unread, false);
  await state.refresh();
  assert.equal(state.view.unread, false);
});

test('remote additions, edits, deletions and resolution changes notify while closed, and opening acknowledges them', async () => {
  for (const updated of [
    [comment(1), comment(2, 1)],
    [comment(1, 1, { body: 'Edited remotely', editedAt: '2026-10-29T12:01:00Z' })],
    [],
    [comment(1, 1, { resolvedAt: '2026-10-29T12:01:00Z' })]
  ]) {
    const { state, snapshot } = await setup([comment(1)]);
    snapshot(updated);
    await state.refresh();
    assert.equal(state.view.unread, true);
    state.change({ open: true });
    assert.equal(state.view.unread, false);
    state.change({ open: false });
    await state.refresh();
    assert.equal(state.view.unread, false, 'Acknowledged updates must not reappear as unread');
  }
});

test('updates received while the panel is open do not produce unread notifications', async () => {
  const { state, snapshot } = await setup([comment(1)]);
  state.change({ open: true });
  snapshot([comment(1), comment(2)]);
  await state.refresh();
  assert.equal(state.data.comments.size, 2);
  assert.equal(state.view.unread, false);
  state.change({ open: false });
  await state.refresh();
  assert.equal(state.view.unread, false);
});

test('a local write and subsequent timestamp normalization do not trigger unread notifications', async () => {
  const { state, snapshot, api } = await setup([]);
  const created = comment(1, 1, { body: 'Mine', createdAt: '2026-10-29T12:00:01.123456789Z' });
  api.save = async () => created;
  state.compose('new', { mode: 'new' });
  state.draft('new', 'Mine');
  assert.equal(await state.submit('new'), true);
  assert.equal(state.view.unread, false);
  snapshot([{ ...created, createdAt: '2026-10-29T12:00:01.123457Z' }]);
  await state.refresh();
  assert.equal(state.view.unread, false);
});

test('moving an attached composer preserves its anchor and draft across polling, and resets for a new draft', async () => {
  const { state, snapshot } = await setup([comment(1)]);
  const anchor = { xpath: '/html/body/p', relativeX: 0.25, relativeY: 0.75 };
  state.compose('attached', { mode: 'new', anchor });
  state.draft('attached', 'Feedback on this exact element');
  state.moveAttached({ x: 140, y: 180 });
  snapshot([comment(1), comment(2)]);
  await state.refresh();
  const draft = state.view.composers.get('attached');
  assert.deepEqual(draft.position, { x: 140, y: 180 });
  assert.deepEqual(draft.anchor, anchor);
  assert.equal(draft.body, 'Feedback on this exact element');
  state.moveAttached(null);
  assert.equal(draft.position, null);
  assert.deepEqual(draft.anchor, anchor);
  state.cancel('attached');
  state.compose('attached', { mode: 'new', anchor: { xpath: '/html/body/h1', relativeX: 0.5, relativeY: 0.5 } });
  assert.equal(state.view.composers.get('attached').position, undefined);
  assert.equal(state.view.drag, null);
});

test('closing the panel discards all creation drafts and their popup position', async () => {
  const { state } = await setup([comment(1)]);
  state.change({ open: true });
  state.compose('new', { mode: 'new' });
  state.compose('reply:1', { mode: 'reply', threadId: 1 });
  state.compose('attached', { mode: 'new', anchor: { xpath: '/html/body/p', relativeX: 0.5, relativeY: 0.5 } });
  for (const key of ['new', 'reply:1', 'attached']) state.draft(key, 'Discard on close');
  state.moveAttached({ x: 140, y: 180 });
  state.close();
  assert.equal(state.view.open, false);
  assert.equal(state.view.composers.size, 0);
  assert.equal(state.view.drag, null);
  assert.equal(state.view.selecting, false);
  state.change({ open: true });
  assert.equal(state.view.composers.size, 0);
});

test('an already submitted comment may finish after closing without clearing a newly opened draft', async () => {
  const { state, api } = await setup([]);
  const saved = deferred();
  api.save = () => saved.promise;
  state.change({ open: true });
  state.compose('new', { mode: 'new' });
  state.draft('new', 'Already submitted');
  const writing = state.submit('new');
  state.close();
  state.change({ open: true });
  state.compose('new', { mode: 'new' });
  state.draft('new', 'A different draft');
  saved.resolve(comment(1, 1, { body: 'Already submitted' }));
  assert.equal(await writing, true);
  assert.equal(state.data.comments.get(1).body, 'Already submitted');
  assert.equal(state.view.composers.get('new').body, 'A different draft');
});
