// ---------------------------------------------------------------------------
// Aegis EBS — tabbed panels: Incident timeline/replay · Chaos control ·
// Reports (p50/p95/p99 + CSV/JSON export) · Delivery funnel per alert ·
// Operator bridge (REAL publish/cancel/fleet commands via HTTP bridge).
// The 3D twin keeps rendering behind the panels; the "Twin" tab restores the
// classic HUD-only view. All DOM lives in index.html; this module wires it.
// ---------------------------------------------------------------------------

import { KIND_CATEGORY, summarizeEvent } from './events.js';
import { downloadFile, exportMetricsCSV, exportMetricsJSON, sparklineSVG, toCSV } from './reports.js';

const $ = (id) => document.getElementById(id);
const timeOf = (ts) => (ts ? new Date(ts).toLocaleTimeString('en-GB', { hour12: false }) : '–');
const pct = (x) => `${Math.round(x * 100)}%`;
const LIST_CAP = 200; // timeline rows rendered (store keeps up to 1000 events)

export function createPanels(deps) {
  const { store, chaos, funnel, history, replay, getState, buildingId } = deps;
  let feed = deps.feed ?? null; // main.js assigns it via setFeed() once the feed exists
  let activeTab = 'twin';
  let selectedAlertId = null;
  let lastRenderedSeq = 0;

  function setFeed(f) {
    feed = f;
    refreshIfActive('chaos');
  }

  /** Jump to the funnel view with one alert preselected (HUD alert click). */
  function selectAlert(alertId) {
    selectedAlertId = alertId;
    setTab('funnel');
  }

  // --- tabs -------------------------------------------------------------------

  const TABS = ['twin', 'incidents', 'chaos', 'reports', 'funnel', 'operator'];

  function setTab(name) {
    if (!TABS.includes(name)) return;
    if (activeTab === 'incidents' && name !== 'incidents' && replay.loaded) replay.stop();
    activeTab = name;
    document.body.dataset.tab = name;
    for (const t of TABS) {
      const view = $(`view-${t}`);
      if (view) view.hidden = t !== name;
      const btn = document.querySelector(`#tabs .tab[data-tab="${t}"]`);
      if (btn) btn.classList.toggle('active', t === name);
    }
    $('panel-host').hidden = name === 'twin';
    $('hud-side').classList.toggle('dimmed', name !== 'twin');
    refresh(name);
  }

  function refresh(tab) {
    if (tab === 'incidents') renderIncidents();
    else if (tab === 'chaos') renderChaos();
    else if (tab === 'reports') renderReports();
    else if (tab === 'funnel') renderFunnel();
    else if (tab === 'operator') renderOperator();
  }
  const refreshIfActive = (tab) => {
    if (activeTab === tab) refresh(tab);
  };

  document.querySelectorAll('#tabs .tab').forEach((btn) => {
    btn.addEventListener('click', () => setTab(btn.dataset.tab));
  });
  window.addEventListener('keydown', (ev) => {
    const tag = document.activeElement?.tagName;
    if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return;
    const i = Number(ev.key);
    if (i >= 1 && i <= TABS.length) setTab(TABS[i - 1]);
  });

  // --- incident timeline + replay ----------------------------------------------

  function ensureLoaded() {
    if (!replay.loaded && store.size > 0 && getState()) replay.load(store.all, getState());
    return replay.loaded;
  }
  function seekToSeq(seq) {
    if (!ensureLoaded()) return;
    const i = replay.events.findIndex((e) => e.seq === seq);
    if (i >= 0) replay.seek(i);
  }

  function renderIncidents() {
    $('ev-count').textContent = String(store.size);
    $('ev-source').textContent = feed.mode === 'live' ? 'live feed' : 'mock feed';
    const list = $('ev-list');
    const events = store.all;
    const shown = events.slice(-LIST_CAP);
    list.replaceChildren(
      ...shown.map((e) => {
        const li = document.createElement('li');
        li.className = `ev ev-${KIND_CATEGORY[e.eventKind] ?? 'device'}`;
        li.dataset.seq = String(e.seq);
        const t = document.createElement('span');
        t.className = 'ev-time';
        t.textContent = timeOf(e.ts);
        const kind = document.createElement('span');
        kind.className = 'ev-kind';
        kind.textContent = e.eventKind;
        const inc = document.createElement('span');
        inc.className = 'ev-inc';
        inc.textContent = e.incidentId;
        const sum = document.createElement('span');
        sum.className = 'ev-sum';
        sum.textContent = summarizeEvent(e);
        li.append(t, kind, inc, sum);
        li.addEventListener('click', () => seekToSeq(e.seq));
        return li;
      }),
    );
    if (!shown.length) {
      const li = document.createElement('li');
      li.className = 'ev-empty';
      li.textContent = 'No events yet — wait for the feed, load the demo incident, or enable chaos.';
      list.replaceChildren(li);
    }
    $('ev-note').textContent =
      events.length > LIST_CAP ? `showing last ${LIST_CAP} of ${events.length} events` : `${events.length} events · click a row to inspect/replay`;
    // follow the live tail unless the user is replaying/inspecting
    const lastSeq = events[events.length - 1]?.seq ?? 0;
    if (!replay.loaded && lastSeq !== lastRenderedSeq) list.scrollTop = list.scrollHeight;
    lastRenderedSeq = lastSeq;
    if (replay.loaded) markCurrentRow(replay.index >= 0 ? replay.events[replay.index] : null);
    syncCursor();
  }

  function markCurrentRow(event) {
    const list = $('ev-list');
    list.querySelector('.ev-current')?.classList.remove('ev-current');
    if (!event) return;
    const row = list.querySelector(`[data-seq="${event.seq}"]`);
    if (row) {
      row.classList.add('ev-current');
      if (replay.playing) {
        // scroll ONLY the event list — scrollIntoView would scroll the whole
        // panel host and push the replay controls out of view.
        const lr = list.getBoundingClientRect();
        const rr = row.getBoundingClientRect();
        if (rr.bottom > lr.bottom) list.scrollTop += rr.bottom - lr.bottom;
        else if (rr.top < lr.top) list.scrollTop -= lr.top - rr.top;
      }
    }
  }

  function syncCursor() {
    const total = replay.loaded ? replay.events.length : store.size;
    const index = replay.loaded ? replay.index : -1;
    $('rp-pos').textContent = `${Math.max(0, index + 1)} / ${total}`;
    const scrub = $('rp-scrub');
    scrub.max = String(Math.max(0, total - 1));
    scrub.value = String(Math.max(0, index));
    $('rp-play').textContent = replay.playing ? '⏸' : '▶';
    $('rp-speed').value = String(replay.speed);
  }

  function updateCursor({ index, total, event, playing }) {
    $('rp-pos').textContent = `${Math.max(0, index + 1)} / ${total}`;
    const scrub = $('rp-scrub');
    scrub.max = String(Math.max(0, total - 1));
    scrub.value = String(Math.max(0, index));
    $('rp-play').textContent = playing ? '⏸' : '▶';
    const detail = $('rp-detail');
    if (!event) {
      detail.replaceChildren(Object.assign(document.createElement('span'), {
        className: 'hint',
        textContent: replay.loaded ? 'At start of timeline — press ▶ to replay.' : 'Press ▶ to replay the timeline on the 3D twin, or click an event row.',
      }));
    } else {
      const head = document.createElement('div');
      head.className = 'rp-head';
      const kind = document.createElement('span');
      kind.className = `ev-kind ev-${KIND_CATEGORY[event.eventKind] ?? 'device'}`;
      kind.textContent = event.eventKind;
      const meta = document.createElement('span');
      meta.className = 'hint';
      meta.textContent = `${event.incidentId} · ${timeOf(event.ts)} · actor ${event.actorId ?? 'system'}${event.correlationId ? ` · corr ${event.correlationId}` : ''}`;
      head.append(kind, meta);
      const sum = document.createElement('div');
      sum.textContent = summarizeEvent(event);
      const pre = document.createElement('pre');
      pre.className = 'rp-payload';
      pre.textContent = JSON.stringify(event.payload ?? {}, null, 2);
      detail.replaceChildren(head, sum, pre);
    }
    markCurrentRow(event);
    if (activeTab !== 'incidents') return;
  }

  $('rp-play').addEventListener('click', () => {
    if (!ensureLoaded()) return;
    if (replay.playing) replay.pause();
    else replay.play();
  });
  $('rp-reset').addEventListener('click', () => {
    if (!ensureLoaded()) return;
    replay.seek(-1);
  });
  $('rp-back').addEventListener('click', () => {
    if (ensureLoaded()) replay.stepBack();
  });
  $('rp-fwd').addEventListener('click', () => {
    if (ensureLoaded()) replay.stepForward();
  });
  $('rp-speed').addEventListener('change', (ev) => replay.setSpeed(Number(ev.target.value)));
  $('rp-scrub').addEventListener('input', (ev) => {
    if (ensureLoaded()) replay.seek(Number(ev.target.value));
  });
  $('ev-demo').addEventListener('click', () => {
    deps.appendDemo();
    renderIncidents();
  });
  $('ev-clear').addEventListener('click', () => {
    replay.stop();
    store.clear();
    renderIncidents();
  });
  $('ev-export-json').addEventListener('click', () => {
    downloadFile(
      `aegis-incident-events-${buildingId}.json`,
      'application/json;charset=utf-8',
      JSON.stringify({ feed: 'status/1', buildingId, exportedAt: new Date().toISOString(), events: store.all }, null, 2),
    );
  });
  $('ev-export-csv').addEventListener('click', () => {
    const rows = store.all.map((e) => ({ ...e, payload: JSON.stringify(e.payload ?? {}) }));
    downloadFile(
      `aegis-incident-events-${buildingId}.csv`,
      'text/csv;charset=utf-8',
      toCSV(rows, [
        { key: 'seq' }, { key: 'ts' }, { key: 'incidentId' }, { key: 'eventKind' },
        { key: 'actorId' }, { key: 'correlationId' }, { key: 'payload' },
      ]),
    );
  });

  // --- chaos control -------------------------------------------------------------

  function afterChaosCommand() {
    // Chaos runs against the mock simulation; switch the feed if needed.
    if (chaos.anyEnabled && feed.mode !== 'mock') feed.forceMock();
    renderChaos();
  }

  function renderChaos() {
    const st = chaos.state;
    const cards = [
      ['ch-drop', st.dropAck, 'ch-drop-btn', 'ch-drop-state'],
      ['ch-delay', st.delayEnabled, 'ch-delay-btn', 'ch-delay-state'],
      ['ch-disc', st.disconnectEnabled, 'ch-disc-btn', 'ch-disc-state'],
    ];
    for (const [card, on, btn, stateEl] of cards) {
      $(card).classList.toggle('chaos-on', on);
      $(btn).textContent = on ? 'Disable' : 'Enable';
      $(stateEl).textContent = on ? 'ACTIVE' : 'IDLE';
      $(stateEl).className = `chaos-state ${on ? 'on' : ''}`;
    }
    $('ch-delay-ms').value = String(st.delayMs);
    $('ch-delay-ms-val').textContent = `${st.delayMs} ms`;
    $('ch-disc-pct').value = String(st.disconnectPct);
    $('ch-disc-pct-val').textContent = `${st.disconnectPct}%`;
    $('ch-feed').textContent = `feed: ${feed.mode}${feed.forcedMock ? ' (forced mock)' : ''}`;
    $('ch-live').disabled = feed.mode !== 'mock';
    $('ch-note').textContent =
      feed.mode === 'mock'
        ? 'Commands execute on the local mock simulation (status/1 has no chaos opcode). Effects appear in the twin, metrics and timeline.'
        : 'Feed is LIVE — chaos commands are mock-only. Enabling a scenario switches to the MOCK simulation; “Return to live” resumes probing the server.';
    const tbody = $('ch-log').querySelector('tbody');
    tbody.replaceChildren(
      ...chaos.log.map((r) => {
        const tr = document.createElement('tr');
        for (const text of [timeOf(r.ts), r.id, r.command, JSON.stringify(r.params), `${r.status}${r.result ? ` · ${r.result}` : ''}`]) {
          const td = document.createElement('td');
          td.textContent = text;
          tr.append(td);
        }
        tr.className = r.status === 'acknowledged' ? 'log-ok' : 'log-sent';
        return tr;
      }),
    );
    if (!chaos.log.length) {
      const tr = document.createElement('tr');
      const td = document.createElement('td');
      td.colSpan = 5;
      td.className = 'hint';
      td.textContent = 'No commands yet.';
      tr.append(td);
      tbody.replaceChildren(tr);
    }
  }

  $('ch-drop-btn').addEventListener('click', () => {
    chaos.command('DROP_ACK', { enabled: !chaos.state.dropAck });
    afterChaosCommand();
  });
  $('ch-delay-btn').addEventListener('click', () => {
    chaos.command('DELAY_ACK', { enabled: !chaos.state.delayEnabled, ms: Number($('ch-delay-ms').value) });
    afterChaosCommand();
  });
  $('ch-disc-btn').addEventListener('click', () => {
    chaos.command('DISCONNECT', { enabled: !chaos.state.disconnectEnabled, pct: Number($('ch-disc-pct').value) });
    afterChaosCommand();
  });
  $('ch-delay-ms').addEventListener('input', (ev) => {
    $('ch-delay-ms-val').textContent = `${ev.target.value} ms`;
  });
  $('ch-delay-ms').addEventListener('change', (ev) => {
    if (chaos.state.delayEnabled) {
      chaos.command('DELAY_ACK', { enabled: true, ms: Number(ev.target.value) });
      afterChaosCommand();
    }
  });
  $('ch-disc-pct').addEventListener('input', (ev) => {
    $('ch-disc-pct-val').textContent = `${ev.target.value}%`;
  });
  $('ch-disc-pct').addEventListener('change', (ev) => {
    if (chaos.state.disconnectEnabled) {
      chaos.command('DISCONNECT', { enabled: true, pct: Number(ev.target.value) });
      afterChaosCommand();
    }
  });
  $('ch-alloff').addEventListener('click', () => {
    chaos.allOff();
    renderChaos();
  });
  $('ch-live').addEventListener('click', () => {
    chaos.allOff();
    feed.releaseMock();
    renderChaos();
  });

  // --- reports ---------------------------------------------------------------------

  function renderReports() {
    const s = history.stats();
    $('rp-samples').textContent = `${s.samples} samples`;
    $('rp-window').textContent = s.from
      ? `window: ${timeOf(s.from)} → ${timeOf(s.to)} · sampled from every snapshot/delta`
      : 'window: no samples yet';
    const tbody = $('rp-stats').querySelector('tbody');
    const LABELS = { connected: 'connected', p50: 'p50 (ms)', p95: 'p95 (ms)', p99: 'p99 (ms)', retryCount: 'retryCount' };
    tbody.replaceChildren(
      ...Object.keys(LABELS).map((key) => {
        const m = s.metrics[key];
        const tr = document.createElement('tr');
        for (const text of [LABELS[key], m ? m.current : '–', m ? m.min : '–', m ? m.avg : '–', m ? m.max : '–']) {
          const td = document.createElement('td');
          td.textContent = String(text);
          tr.append(td);
        }
        return tr;
      }),
    );
    const sparks = $('rp-sparks');
    sparks.replaceChildren(
      ...['p50', 'p95', 'p99'].map((key) => {
        const row = document.createElement('div');
        row.className = 'spark-row';
        const label = document.createElement('span');
        label.className = 'spark-label';
        label.textContent = key;
        const values = history.series(key).map((p) => p.value);
        const svg = document.createElement('span');
        svg.className = 'spark-svg';
        svg.innerHTML = sparklineSVG(values, { stroke: key === 'p99' ? '#ef4444' : key === 'p95' ? '#facc15' : '#22c55e' });
        const cur = document.createElement('span');
        cur.className = 'spark-cur';
        cur.textContent = values.length ? `${values[values.length - 1]} ms` : '–';
        row.append(label, svg, cur);
        return row;
      }),
    );
  }

  $('rp-csv').addEventListener('click', () => exportMetricsCSV(history, buildingId));
  $('rp-json').addEventListener('click', () => exportMetricsJSON(history, buildingId));
  $('rp-clear').addEventListener('click', () => {
    history.clear();
    renderReports();
  });

  // --- delivery funnel ----------------------------------------------------------------

  function renderFunnel() {
    const rows = funnel.all(getState());
    const activeCount = rows.filter((r) => r.active).length;
    $('fn-count').textContent = `${activeCount} active / ${rows.length} tracked`;
    const select = $('fn-select');
    if (!rows.some((r) => r.alertId === selectedAlertId)) selectedAlertId = rows[0]?.alertId ?? null;
    select.replaceChildren(
      ...rows.map((r) => {
        const opt = document.createElement('option');
        opt.value = r.alertId;
        opt.textContent = `${r.active ? '●' : '○'} ${r.alertId} · ${r.zoneCode} · ${r.severity}${r.active ? '' : ' (closed)'}`;
        opt.selected = r.alertId === selectedAlertId;
        return opt;
      }),
    );
    if (!rows.length) {
      const opt = document.createElement('option');
      opt.textContent = 'No alerts tracked yet';
      select.replaceChildren(opt);
    }
    const f = rows.find((r) => r.alertId === selectedAlertId) ?? null;
    const bars = $('fn-bars');
    const detail = $('fn-detail');
    if (!f) {
      bars.replaceChildren(Object.assign(document.createElement('p'), { className: 'hint', textContent: 'Funnels appear when alerts are active (or were active this session).' }));
      detail.textContent = '';
    } else {
      const stages = [
        ['Targeted', f.targeted, 'fn-targeted'],
        ['Received', f.received, 'fn-received'],
        ['Acknowledged', f.acknowledged, 'fn-acked'],
        ['Failed', f.failed, 'fn-failed'],
      ];
      bars.replaceChildren(
        ...stages.map(([label, value, cls]) => {
          const row = document.createElement('div');
          row.className = 'fn-row';
          const lab = document.createElement('span');
          lab.className = 'fn-label';
          lab.textContent = label;
          const track = document.createElement('div');
          track.className = 'fn-track';
          const fill = document.createElement('div');
          fill.className = `fn-fill ${cls}`;
          fill.style.width = `${Math.max(f.targeted ? (value / f.targeted) * 100 : 0, value > 0 ? 2 : 0)}%`;
          track.append(fill);
          const num = document.createElement('span');
          num.className = 'fn-num';
          num.textContent = `${value} · ${pct(f.targeted ? value / f.targeted : 0)}`;
          row.append(lab, track, num);
          return row;
        }),
      );
      const chips = document.createElement('div');
      chips.className = 'fn-chips';
      for (const text of [`pending ${f.pending}`, `unacked ${f.unacked}`, `ack rate ${pct(f.ackRate)}`, `fail rate ${pct(f.failRate)}`]) {
        const chip = document.createElement('span');
        chip.className = 'pill';
        chip.textContent = text;
        chips.append(chip);
      }
      bars.append(chips);
      detail.textContent =
        `headline (zone totals): ${f.message}` +
        (f.zoneTotals ? ` — recv ${f.zoneTotals.received} · ack ${f.zoneTotals.acknowledged} · failed ${f.zoneTotals.failed}` : '') +
        ` · funnel counts deliveries since alert opened ${timeOf(f.openedTs)}` +
        `${f.closedTs ? ` · closed ${timeOf(f.closedTs)}` : ''} · zone ${f.zoneCode} (floor ${f.floorNumber})`;
    }
    const tbody = $('fn-table').querySelector('tbody');
    tbody.replaceChildren(
      ...rows.map((r) => {
        const tr = document.createElement('tr');
        tr.className = r.active ? `fn-row-${r.severity}` : 'fn-row-closed';
        for (const text of [r.alertId, r.zoneCode, r.active ? 'active' : 'closed', r.targeted, r.received, r.acknowledged, r.failed, pct(r.ackRate)]) {
          const td = document.createElement('td');
          td.textContent = String(text);
          tr.append(td);
        }
        tr.addEventListener('click', () => {
          selectedAlertId = r.alertId;
          renderFunnel();
        });
        return tr;
      }),
    );
    if (!rows.length) {
      const tr = document.createElement('tr');
      const td = document.createElement('td');
      td.colSpan = 8;
      td.className = 'hint';
      td.textContent = 'No alerts tracked yet.';
      tr.append(td);
      tbody.replaceChildren(tr);
    }
  }

  $('fn-select').addEventListener('change', (ev) => {
    selectedAlertId = ev.target.value;
    renderFunnel();
  });
  $('fn-csv').addEventListener('click', () => {
    const rows = funnel.all(getState());
    downloadFile(
      `aegis-delivery-funnel-${buildingId}.csv`,
      'text/csv;charset=utf-8',
      toCSV(rows, [
        { key: 'alertId' }, { key: 'zoneCode' }, { key: 'floorNumber' }, { key: 'severity' },
        { key: 'active' }, { key: 'openedTs' }, { key: 'closedTs' }, { key: 'targeted' },
        { key: 'received' }, { key: 'acknowledged' }, { key: 'failed' }, { key: 'pending' },
        { key: 'unacked' }, { key: 'ackRate' }, { key: 'failRate' },
      ]),
    );
  });
  $('fn-json').addEventListener('click', () => {
    downloadFile(
      `aegis-delivery-funnel-${buildingId}.json`,
      'application/json;charset=utf-8',
      JSON.stringify(
        { feed: 'status/1', buildingId, exportedAt: new Date().toISOString(), note: 'derived from zone counters since alert opened (see src/funnel.js)', alerts: funnel.all(getState()) },
        null,
        2,
      ),
    );
  });

  // --- operator bridge (REAL commands over HTTP -> EBSP/TLS) --------------------
  // This tab does NOT touch the mock simulation. The Python bridge at
  // http://localhost:9445 forwards commands to the real client/server stack.
  // The Chaos tab stays mock-only.

  const OP_DEFAULT_HOST = (globalThis.location?.hostname === '127.0.0.1')
    ? '127.0.0.1' : 'localhost';
  const OP_DEFAULT_BRIDGE = `http://${OP_DEFAULT_HOST}:9445`;
  const OP_BUTTONS = ['op-ping', 'op-fleet-start', 'op-fleet-stop', 'op-publish', 'op-retry-demo', 'op-cancel'];
  const BRIDGE_BASE = (() => {
    let override = '';
    try {
      override = new URLSearchParams(globalThis.location?.search ?? '').get('bridge')?.trim() ?? '';
    } catch { /* keep default */ }
    return (override || OP_DEFAULT_BRIDGE).replace(/\/+$/, '');
  })();
  const opState = { busy: false, lastAlertId: null, stats: null };

  const opStamp = () => new Date().toLocaleTimeString('en-GB', { hour12: false });

  function opSetStatus(text, kind = '') {
    const el = $('op-status');
    if (!el) return;
    el.textContent = text;
    el.className = `pill${kind ? ` op-status-${kind}` : ''}`;
  }

  function opLogLine(text, kind = 'info') {
    const box = $('op-log');
    if (!box) return;
    box.querySelector('.op-log-empty')?.remove();
    const line = document.createElement('div');
    line.className = `op-log-line op-log-${kind}`;
    line.textContent = `[${opStamp()}] ${text}`;
    box.append(line);
    while (box.childElementCount > 200) box.firstElementChild.remove();
    box.scrollTop = box.scrollHeight;
  }

  /** Find the first non-empty value for any of `keys`, including nested objects. */
  function opPick(obj, keys) {
    if (!obj || typeof obj !== 'object') return undefined;
    for (const key of keys) {
      if (obj[key] !== undefined && obj[key] !== null) return obj[key];
    }
    for (const value of Object.values(obj)) {
      if (value && typeof value === 'object' && !Array.isArray(value)) {
        const found = opPick(value, keys);
        if (found !== undefined) return found;
      }
    }
    return undefined;
  }

  function opDeliveryCount(data) {
    const direct = opPick(data, ['deliveryCount', 'delivery_count', 'deliveredCount', 'delivered']);
    if (direct !== undefined) return direct;
    for (const key of ['deliveries', 'deliveryResults', 'deliveryTargets', 'targets']) {
      const arr = opPick(data, [key]);
      if (Array.isArray(arr)) return arr.length;
    }
    return undefined;
  }

  async function opFetch(path, body) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 15000);
    try {
      const init = { signal: controller.signal, headers: { Accept: 'application/json' } };
      if (body !== undefined) {
        init.method = 'POST';
        init.headers['Content-Type'] = 'application/json';
        init.body = JSON.stringify(body);
      }
      const res = await fetch(`${BRIDGE_BASE}${path}`, init);
      const raw = await res.text();
      let data = null;
      try { data = raw ? JSON.parse(raw) : null; } catch { /* non-JSON response */ }
      if (!res.ok || data?.ok === false) {
        const detail = data?.error ?? data?.message ?? raw?.slice(0, 200) ?? '';
        throw new Error(`${path} → HTTP ${res.status}${detail ? ` · ${detail}` : ''}`);
      }
      return data ?? { ok: true, note: 'empty response' };
    } catch (err) {
      if (err?.name === 'AbortError') throw new Error(`${path} → timeout sau 15s`);
      throw err;
    } finally {
      clearTimeout(timer);
    }
  }

  function opAuth() {
    return { username: $('op-user').value.trim(), password: $('op-pass').value };
  }

  function opPublishBody(overrides = {}) {
    return {
      ...opAuth(),
      floor: $('op-floor').value,
      zone: $('op-zone').value,
      category: $('op-category').value,
      priority: $('op-priority').value,
      title: $('op-title').value.trim(),
      instruction: $('op-instruction').value.trim(),
      silent: $('op-silent').checked,
      ...overrides,
    };
  }

  /** Log time + alertId + deliveryCount and remember the alert for Cancel. */
  function opRemember(data, body) {
    const alertId = opPick(data, ['alertId', 'alert_id', 'alertID']) ?? body?.alertId;
    if (alertId != null) {
      opState.lastAlertId = String(alertId);
      const field = $('op-alert-id');
      if (field) field.value = opState.lastAlertId;
    }
    const dc = opDeliveryCount(data);
    const extra = opPick(data, ['state', 'deliveryState']);
    opLogLine(
      `OK · alertId=${alertId ?? '–'} · deliveryCount=${dc ?? '–'}${extra ? ` · state=${extra}` : ''}`,
      'ok',
    );
  }

  async function opShowStats() {
    try {
      opState.stats = await opFetch('/api/fleet/stats');
      renderOperator();
      opLogLine(`fleet stats · ${JSON.stringify(opState.stats)}`);
    } catch (err) {
      opLogLine(`fleet stats ERROR · ${err.message}`, 'error');
    }
  }

  function renderOperator() {
    const bridge = $('op-bridge');
    if (bridge) bridge.textContent = `bridge: ${BRIDGE_BASE}`;
    const stats = opState.stats;
    const field = $('op-fleet-stats');
    if (field) {
      if (!stats) field.textContent = 'fleet: –';
      else {
        const count = opPick(stats, ['deviceCount', 'deviceTotal', 'count', 'total', 'fleetSize', 'devices']);
        const online = opPick(stats, ['online', 'onlineCount', 'connected']);
        const silent = opPick(stats, ['silent', 'silentCount', 'silentDevices']);
        field.textContent = `fleet: ${Array.isArray(count) ? count.length : (count ?? '–')}` +
          `${online != null ? ` · online ${online}` : ''}${silent != null ? ` · silent ${silent}` : ''}`;
      }
    }
  }

  async function opWithBusy(label, fn) {
    if (opState.busy) return;
    opState.busy = true;
    for (const id of OP_BUTTONS) {
      const btn = $(id);
      if (btn) btn.disabled = true;
    }
    opSetStatus('busy', 'busy');
    opLogLine(`${label} · gửi lệnh…`);
    try {
      await fn();
      opSetStatus('ok', 'ok');
    } catch (err) {
      opLogLine(`${label} · ERROR · ${err.message}`, 'error');
      opSetStatus('error', 'error');
    } finally {
      opState.busy = false;
      for (const id of OP_BUTTONS) {
        const btn = $(id);
        if (btn) btn.disabled = false;
      }
    }
  }

  $('op-ping').addEventListener('click', () => opWithBusy('health', async () => {
    const data = await opFetch('/api/health');
    opLogLine(`health OK · ${JSON.stringify(data)}`, 'ok');
    await opShowStats();
  }));

  $('op-fleet-start').addEventListener('click', () => opWithBusy('fleet start', async () => {
    const count = Math.max(1, Math.min(500, Number($('op-count').value) || 20));
    const body = { count, silentFloor: 1 };
    const data = await opFetch('/api/fleet/start', body);
    opLogLine(`fleet start OK · count=${count} · silentFloor=1${data ? ` · ${JSON.stringify(data)}` : ''}`, 'ok');
    await opShowStats();
  }));

  $('op-fleet-stop').addEventListener('click', () => opWithBusy('fleet stop', async () => {
    const data = await opFetch('/api/fleet/stop', {});
    opLogLine(`fleet stop OK${data ? ` · ${JSON.stringify(data)}` : ''}`, 'ok');
    await opShowStats();
  }));

  $('op-publish').addEventListener('click', () => opWithBusy('publish', async () => {
    const body = opPublishBody();
    opRemember(await opFetch('/api/publish', body), body);
  }));

  $('op-retry-demo').addEventListener('click', () => opWithBusy('retry-demo', async () => {
    // Scope to F01-A: the started fleet has 12 real devices there, one of which
    // is silent. Building-wide would mix in 460 offline seeded devices and make
    // the single retry→FAILED case hard to read.
    $('op-floor').value = '1';
    $('op-zone').value = 'A';
    $('op-silent').checked = true;
    const body = opPublishBody({
      floor: '1', zone: 'A', silent: true,
      category: 'FIRE_DRILL', priority: 'CRITICAL',
      title: 'RETRY DEMO — F01-A silent',
      instruction: '1 of 12 devices does not auto-ACK; expect retry then FAILED.',
    });
    opLogLine('retry-demo · F01-A · silent=true → 11 ACK + 1 retry→FAILED', 'warn');
    opRemember(await opFetch('/api/publish', body), body);
  }));

  $('op-cancel').addEventListener('click', () => opWithBusy('cancel', async () => {
    const alertId = $('op-alert-id').value.trim() || opState.lastAlertId;
    if (!alertId) throw new Error('chưa có alertId — hãy publish trước hoặc điền ô Alert ID');
    const body = { ...opAuth(), alertId, reason: 'Operator cancel via dashboard' };
    opRemember(await opFetch('/api/cancel', body), body);
  }));

  // --- init ------------------------------------------------------------------------

  setTab('twin');
  return {
    setTab,
    setFeed,
    selectAlert,
    refresh,
    refreshIfActive,
    updateCursor,
    get activeTab() {
      return activeTab;
    },
  };
}
