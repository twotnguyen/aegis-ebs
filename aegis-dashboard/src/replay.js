// ---------------------------------------------------------------------------
// Aegis EBS — incident replay engine.
//
// Replays INCIDENT_EVENT frames over a base STATUS_SNAPSHOT: events carrying
// payload.counters repaint the 3D twin deterministically (see events.js).
// Pure helpers are exported for unit tests; the controller owns play/pause/
// step/seek/speed. Callbacks are public properties so main.js can wire the
// twin painter after construction.
// ---------------------------------------------------------------------------

import { deriveSeverity } from './data.js';

const COUNTER_FIELDS = ['online', 'received', 'acknowledged', 'failed'];

/** Pure: apply one event's zone counters to a state (returns a new state). */
export function applyEventToState(state, evt) {
  if (!state || !evt || evt.type !== 'INCIDENT_EVENT') return state;
  const { zoneCode, counters } = evt.payload ?? {};
  if (!zoneCode || !counters || typeof counters !== 'object') return state;
  const next = structuredClone(state);
  for (const f of next.floors) {
    const zone = f.zones.find((z) => z.zoneCode === zoneCode);
    if (!zone) continue;
    for (const key of COUNTER_FIELDS) {
      if (typeof counters[key] === 'number') zone[key] = counters[key];
    }
    zone.severity = deriveSeverity(zone);
  }
  if (evt.ts) next.ts = evt.ts;
  return next;
}

/**
 * @param {object} [opts]
 * @param {number} [opts.tickMs] base interval between events at 1× speed
 */
export function createReplay({ tickMs = 800 } = {}) {
  let events = [];
  let base = null;
  let current = null;
  let idx = -1;
  let playing = false;
  let speed = 1;
  let timer = null;

  const api = {
    /** Called with (replayState|null, event|null). null ⇒ restore live state. */
    onPaint: null,
    /** Called with {index,total,event,playing,speed}. */
    onCursor: null,
    /** Called when playback reaches the last event. */
    onEnd: null,
  };

  function rebuildTo(i) {
    current = structuredClone(base);
    for (let k = 0; k <= i; k++) current = applyEventToState(current, events[k]);
  }
  function emitCursor() {
    api.onCursor?.({ index: idx, total: events.length, event: idx >= 0 ? events[idx] : null, playing, speed });
  }
  function clearTimer() {
    if (timer !== null) {
      clearInterval(timer);
      timer = null;
    }
  }
  function step() {
    if (!events.length) return;
    if (idx >= events.length - 1) {
      api.pause();
      api.onEnd?.();
      return;
    }
    idx += 1;
    rebuildTo(idx);
    api.onPaint?.(current, events[idx]);
    emitCursor();
  }

  api.load = (evts, baseState) => {
    clearTimer();
    playing = false;
    events = [...(evts ?? [])];
    base = baseState ? structuredClone(baseState) : null;
    idx = -1;
    current = base ? structuredClone(base) : null;
    emitCursor();
  };
  api.play = () => {
    if (!events.length || !base) return;
    if (idx >= events.length - 1) {
      idx = -1;
      current = structuredClone(base);
    }
    playing = true;
    clearTimer();
    timer = setInterval(step, tickMs / speed);
    emitCursor();
    step(); // immediate first frame
  };
  api.pause = () => {
    playing = false;
    clearTimer();
    emitCursor();
  };
  api.stop = () => {
    clearTimer();
    playing = false;
    idx = -1;
    current = null;
    api.onPaint?.(null, null);
    emitCursor();
  };
  api.seek = (i) => {
    if (!events.length || !base) return;
    api.pause();
    idx = Math.max(-1, Math.min(i, events.length - 1));
    if (idx < 0) {
      current = structuredClone(base);
      api.onPaint?.(current, null);
    } else {
      rebuildTo(idx);
      api.onPaint?.(current, events[idx]);
    }
    emitCursor();
  };
  api.stepForward = () => {
    api.pause();
    step();
  };
  api.stepBack = () => api.seek(idx - 1);
  api.setSpeed = (s) => {
    speed = s > 0 ? s : 1;
    if (playing) {
      clearTimer();
      timer = setInterval(step, tickMs / speed);
    }
    emitCursor();
  };

  Object.defineProperties(api, {
    playing: { get: () => playing },
    index: { get: () => idx },
    speed: { get: () => speed },
    events: { get: () => events },
    state: { get: () => current },
    loaded: { get: () => events.length > 0 && base !== null },
  });
  return api;
}
