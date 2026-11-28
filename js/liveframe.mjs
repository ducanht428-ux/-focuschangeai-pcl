// One measurement engine, two windows.
//
// The dashboard used to send the teacher to ../index.html to measure. This module lets the dashboard
// KEEP that page as its camera: the measurement screen runs in a same-origin frame, draws its own
// picture and overlays, and posts its numbers up. The engine is not copied - the loop, the identity
// vote, the habit layer and the SQLite writer stay exactly where they were tested, so the two surfaces
// cannot drift apart. The frame is the only writer; the dashboard around it stays read-only.
//
// Three things travel over postMessage: a command down, a state snapshot up (4 Hz), and events up
// (a closed session, an alert, a log line). Every message carries PROTO and is accepted only from the
// one window it is meant for, so a stray frame or an extension cannot drive the camera.
//
// The lease is the other half. Both surfaces can open the same database file, and the writer holds the
// whole file in memory and saves it on every closed session - two writers would overwrite each other's
// runs. Only the surface holding the lease may start a camera; the other one says who has it.

export const PROTO = 'focuschange-live/1';
export const STATE_MS = 250;

const LEASE_KEY = 'fc_writer_lease';
const LEASE_CHANNEL = 'focuschangeai-writer';
// A heartbeat every 2 s against a 6 s life: a tab that is killed mid-sitting stops renewing, and three
// missed beats later the other surface may take over. Shorter would steal the lease from a machine
// that is merely busy compiling a YOLO session; longer would leave a dead tab holding the camera.
const LEASE_BEAT_MS = 2000;
const LEASE_TTL_MS = 6000;
const MY_ID = 'w' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);

function readLease() {
  try {
    const l = JSON.parse(localStorage.getItem(LEASE_KEY) || 'null');
    return l && typeof l.id === 'string' && typeof l.at === 'number' ? l : null;
  } catch (e) { return null; }
}

/** The live holder, or null when nobody is measuring. An expired stamp is not a holder. */
export function leaseHolder() {
  const l = readLease();
  if (!l || Date.now() - l.at > LEASE_TTL_MS) return null;
  return l;
}

let beat = 0;
let chan = null;
function channel() {
  if (chan || typeof BroadcastChannel === 'undefined') return chan;
  try { chan = new BroadcastChannel(LEASE_CHANNEL); } catch (e) { chan = null; }
  return chan;
}
function announce() {
  const c = channel();
  if (c) { try { c.postMessage({ id: MY_ID }); } catch (e) { /* storage event still carries it */ } }
}
function writeLease(surface) {
  try {
    localStorage.setItem(LEASE_KEY, JSON.stringify({ id: MY_ID, surface: surface || 'web', at: Date.now() }));
  } catch (e) { /* private mode: the lease cannot be shared, so this window simply owns its own run */ }
  announce();
}

/**
 * Take the single-writer lease. Returns {ok:true} or {ok:false, holder} naming the surface that has it,
 * so the refusal can be a sentence about the other window rather than a dead button.
 */
export function acquireLease(surface) {
  const h = leaseHolder();
  if (h && h.id !== MY_ID) return { ok: false, holder: h };
  writeLease(surface);
  if (!beat) {
    beat = setInterval(() => {
      const cur = readLease();
      // Somebody else took it while this window was asleep: stop renewing a lease that is not ours.
      if (cur && cur.id !== MY_ID) { clearInterval(beat); beat = 0; return; }
      writeLease(surface);
    }, LEASE_BEAT_MS);
  }
  return { ok: true, id: MY_ID };
}

export function releaseLease() {
  if (beat) { clearInterval(beat); beat = 0; }
  const cur = readLease();
  if (cur && cur.id === MY_ID) {
    try { localStorage.removeItem(LEASE_KEY); } catch (e) { /* nothing to release */ }
    announce();
  }
}

export function leaseIsMine() {
  const l = readLease();
  return !!l && l.id === MY_ID;
}

/** Call cb(holderOrNull) whenever another window takes or drops the lease. Returns an unsubscribe. */
export function watchLease(cb) {
  const fire = () => cb(leaseHolder());
  const c = channel();
  if (c) c.addEventListener('message', fire);
  const onStorage = (e) => { if (e.key === LEASE_KEY) fire(); };
  addEventListener('storage', onStorage);
  return () => {
    if (c) c.removeEventListener('message', fire);
    removeEventListener('storage', onStorage);
  };
}

// ---------------------------------------------------------------------------
// The dashboard side
// ---------------------------------------------------------------------------
/**
 * Wire a frame that is already in the document. handlers: onReady(state), onState(state),
 * onEvent(kind, data), onLog(line), onReply(cmd, ok, resultOrError).
 */
export function connectHost(iframe, handlers) {
  const h = handlers || {};
  // Under file:// the origin serialises to "null" and an exact target would throw; the source check
  // below is what actually authenticates the message, and it holds in both shapes.
  const origin = location.origin === 'null' ? '*' : location.origin;
  let ready = false, closed = false;
  const onMsg = (e) => {
    if (closed || e.source !== iframe.contentWindow) return;
    const d = e.data;
    if (!d || d.proto !== PROTO) return;
    if (d.type === 'hello') { ready = true; if (h.onReady) h.onReady(d.state); }
    else if (d.type === 'state') { if (h.onState) h.onState(d.state); }
    else if (d.type === 'event') { if (h.onEvent) h.onEvent(d.kind, d.data); }
    else if (d.type === 'log') { if (h.onLog) h.onLog(d.line); }
    else if (d.type === 'reply') { if (h.onReply) h.onReply(d.cmd, d.ok, d.ok ? d.result : d.error); }
  };
  addEventListener('message', onMsg);
  return {
    get ready() { return ready; },
    send(cmd, payload) {
      if (closed || !iframe.contentWindow) return false;
      iframe.contentWindow.postMessage({ proto: PROTO, type: 'cmd', cmd: cmd, payload: payload }, origin);
      return true;
    },
    close() { closed = true; removeEventListener('message', onMsg); },
  };
}

// ---------------------------------------------------------------------------
// The measurement side
// ---------------------------------------------------------------------------
/**
 * api.snapshot() is read 4 times a second, api.command(cmd, payload) runs one control action and may
 * return a promise. Returns {publish, event, log, stop}.
 */
export function installGuest(api) {
  const origin = location.origin === 'null' ? '*' : location.origin;
  const post = (msg) => {
    try { window.parent.postMessage(Object.assign({ proto: PROTO }, msg), origin); }
    catch (e) { /* the parent went away: the next pagehide ends this frame anyway */ }
  };
  const onMsg = (e) => {
    if (e.source !== window.parent) return;
    const d = e.data;
    if (!d || d.proto !== PROTO || d.type !== 'cmd') return;
    Promise.resolve().then(() => api.command(d.cmd, d.payload)).then(
      (r) => post({ type: 'reply', cmd: d.cmd, ok: true, result: r === undefined ? null : r }),
      (err) => post({ type: 'reply', cmd: d.cmd, ok: false, error: String((err && err.message) || err) }));
  };
  addEventListener('message', onMsg);
  const publish = () => post({ type: 'state', state: api.snapshot() });
  post({ type: 'hello', state: api.snapshot() });
  const timer = setInterval(publish, STATE_MS);
  addEventListener('pagehide', () => clearInterval(timer));
  return {
    publish,
    event(kind, data) { post({ type: 'event', kind: kind, data: data }); },
    log(line) { post({ type: 'log', line: line }); },
    stop() { clearInterval(timer); removeEventListener('message', onMsg); },
  };
}