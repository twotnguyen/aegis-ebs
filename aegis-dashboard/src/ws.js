// ---------------------------------------------------------------------------
// Aegis EBS — WebSocket status client (status/1 contract, see src/data.js).
//
// Behavior:
//  * Connects to ws://localhost:9444 (override: createStatusFeed({url}) or ?ws=).
//  * Exponential reconnect backoff: 1s, 2s, 4s, 8s, capped 15s, + jitter.
//  * After 3 consecutive failures → MOCK mode: local snapshot + deltas every 2s
//    so the dashboard is usable before the Java server exists.
//  * Keeps probing the real server while in mock mode; on a successful live
//    connection the mock feed stops and live data takes over seamlessly.
// ---------------------------------------------------------------------------

import { WS_URL, makeMockSnapshot, makeMockDelta, applyDelta } from './data.js';

const BASE_DELAY_MS = 1000;
const MAX_DELAY_MS = 15000;
const MOCK_AFTER_FAILURES = 3; // mirrors EBSP "max 3 retries" demo rule
const MOCK_TICK_MS = 2000;

/**
 * @param {object} opts
 * @param {string} [opts.url]            WebSocket endpoint (default ws://localhost:9444)
 * @param {(snapshot:object, meta:{source:'live'|'mock'})=>void} [opts.onSnapshot]
 * @param {(delta:object, meta:{source:'live'|'mock'})=>void}    [opts.onDelta]
 * @param {(mode:'connecting'|'live'|'reconnecting'|'mock'|'closed')=>void} [opts.onMode]
 * @returns {{close:()=>void, mode:string, url:string}}
 */
export function createStatusFeed({ url = WS_URL, onSnapshot, onDelta, onMode } = {}) {
  let ws = null;
  let failures = 0;
  let retryTimer = null;
  let mockTimer = null;
  let mockState = null;
  let mode = 'connecting';
  let closed = false;

  const setMode = (m) => {
    if (mode !== m) {
      mode = m;
      onMode?.(m);
    }
  };

  function startMock() {
    if (mockTimer !== null) return;
    mockState = makeMockSnapshot();
    setMode('mock');
    onSnapshot?.(mockState, { source: 'mock' });
    mockTimer = setInterval(() => {
      const delta = makeMockDelta(mockState);
      mockState = applyDelta(mockState, delta); // keep mock world coherent
      onDelta?.(delta, { source: 'mock' });
    }, MOCK_TICK_MS);
  }

  function stopMock() {
    if (mockTimer !== null) {
      clearInterval(mockTimer);
      mockTimer = null;
    }
  }

  function scheduleReconnect() {
    if (closed || retryTimer !== null) return;
    const delay =
      Math.min(BASE_DELAY_MS * 2 ** Math.max(failures - 1, 0), MAX_DELAY_MS) + Math.random() * 250;
    retryTimer = setTimeout(() => {
      retryTimer = null;
      connect();
    }, delay);
  }

  function handleFailure(socket) {
    if (socket.__counted) return; // error+close must count as ONE failure
    socket.__counted = true;
    failures += 1;
    if (failures >= MOCK_AFTER_FAILURES) startMock();
    else setMode('reconnecting');
    scheduleReconnect();
  }

  function connect() {
    if (closed) return;
    let socket;
    try {
      socket = new WebSocket(url);
    } catch {
      handleFailure({});
      return;
    }
    ws = socket;

    socket.onopen = () => {
      failures = 0;
      stopMock();
      setMode('live');
      // Server is expected to push a STATUS_SNAPSHOT right after connect.
    };

    socket.onmessage = (ev) => {
      let msg;
      try {
        msg = JSON.parse(ev.data);
      } catch {
        return; // ignore malformed frames
      }
      if (!msg || typeof msg !== 'object') return;
      if (msg.type === 'STATUS_SNAPSHOT') onSnapshot?.(msg, { source: 'live' });
      else if (msg.type === 'STATUS_DELTA') onDelta?.(msg, { source: 'live' });
    };

    socket.onerror = () => {
      try {
        socket.close();
      } catch {
        /* already closed */
      }
    };
    socket.onclose = () => {
      if (ws === socket) handleFailure(socket);
    };
  }

  function close() {
    closed = true;
    stopMock();
    if (retryTimer !== null) {
      clearTimeout(retryTimer);
      retryTimer = null;
    }
    if (ws) {
      ws.onclose = null;
      ws.onerror = null;
      try {
        ws.close();
      } catch {
        /* noop */
      }
      ws = null;
    }
    setMode('closed');
  }

  setMode('connecting');
  connect();

  return {
    close,
    get mode() {
      return mode;
    },
    get url() {
      return url;
    },
  };
}
