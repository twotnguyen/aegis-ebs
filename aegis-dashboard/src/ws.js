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
//  * Routes INCIDENT_EVENT frames to onEvent (incident timeline, status/1).
//  * mockTransform(prevState, delta) may rewrite mock deltas (chaos engine).
//  * forceMock()/releaseMock() pin the feed to the mock simulation (chaos
//    panel) and resume live probing afterwards.
// ---------------------------------------------------------------------------

import { WS_URL, makeMockSnapshot, makeMockDelta, applyDelta } from './data.js';

const BASE_DELAY_MS = 1000;
const MAX_DELAY_MS = 15000;
const MOCK_AFTER_FAILURES = 3; // mirrors EBSP "max 3 retries" demo rule
const MOCK_TICK_MS = 2000;

/** A socket opening is not LIVE data: only a parsed status/1 snapshot is. */
export function isStatusSnapshot(msg) {
  return !!msg && msg.type === 'STATUS_SNAPSHOT'
    && !!msg.payload && typeof msg.payload === 'object';
}

/**
 * @param {object} opts
 * @param {string} [opts.url]            WebSocket endpoint (default ws://localhost:9444)
 * @param {(snapshot:object, meta:{source:'live'|'mock'})=>void} [opts.onSnapshot]
 * @param {(delta:object, meta:{source:'live'|'mock'})=>void}    [opts.onDelta]
 * @param {(event:object, meta:{source:'live'})=>void}           [opts.onEvent] INCIDENT_EVENT frames
 * @param {(mode:'connecting'|'live'|'reconnecting'|'mock'|'closed')=>void} [opts.onMode]
 * @param {(prev:object, delta:object)=>object} [opts.mockTransform] mock-only delta rewrite (chaos)
 * @returns {{close:()=>void, forceMock:()=>void, releaseMock:()=>void, mode:string, forcedMock:boolean, url:string}}
 */
export function createStatusFeed({ url = WS_URL, onSnapshot, onDelta, onEvent, onMode, mockTransform } = {}) {
  let ws = null;
  let failures = 0;
  let retryTimer = null;
  let mockTimer = null;
  let mockState = null;
  let mode = 'connecting';
  let closed = false;
  let forcedMock = false;

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
      let delta = makeMockDelta(mockState);
      if (mockTransform) delta = mockTransform(mockState, delta) ?? delta;
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
    if (closed || forcedMock || retryTimer !== null) return;
    const delay =
      Math.min(BASE_DELAY_MS * 2 ** Math.max(failures - 1, 0), MAX_DELAY_MS) + Math.random() * 250;
    retryTimer = setTimeout(() => {
      retryTimer = null;
      connect();
    }, delay);
  }

  function handleFailure(socket) {
    if (socket.__counted || forcedMock) return; // error+close must count as ONE failure
    socket.__counted = true;
    failures += 1;
    if (failures >= MOCK_AFTER_FAILURES) startMock();
    else setMode('reconnecting');
    scheduleReconnect();
  }

  function connect() {
    if (closed || forcedMock) return;
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
      // Server is expected to push a STATUS_SNAPSHOT right after connect.
      // Stay "connecting" until it actually arrives.
    };
    socket.onmessage = (ev) => {
      let msg;
      try {
        msg = JSON.parse(ev.data);
      } catch {
        return; // ignore malformed frames
      }
      if (!msg || typeof msg !== 'object') return;
      if (isStatusSnapshot(msg)) {
        setMode('live');
        onSnapshot?.(msg, { source: 'live' });
      } else if (msg.type === 'STATUS_DELTA') onDelta?.(msg, { source: 'live' });
      else if (msg.type === 'INCIDENT_EVENT') onEvent?.(msg, { source: 'live' });
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

  /** Pin the feed to the local mock simulation (used by the chaos panel). */
  function forceMock() {
    if (closed || forcedMock) return;
    forcedMock = true;
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
    failures = 0;
    startMock();
  }

  /** Resume probing the real endpoint; live data takes over on success.
   *  Works both for forced mock (chaos) and automatic mock fallback. */
  function releaseMock() {
    if (closed) return;
    if (!forcedMock && mode !== 'mock') return;
    forcedMock = false;
    stopMock();
    mockState = null;
    failures = 0;
    setMode('connecting');
    connect();
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
    forceMock,
    releaseMock,
    get mode() {
      return mode;
    },
    get forcedMock() {
      return forcedMock;
    },
    get url() {
      return url;
    },
  };
}
