// ---------------------------------------------------------------------------
// Aegis EBS — reports: metrics history (p50/p95/p99 window stats) + CSV/JSON
// export helpers. Samples are taken from every STATUS_SNAPSHOT/STATUS_DELTA
// already flowing through the feed — no extra data source.
// ---------------------------------------------------------------------------

import { t } from './i18n.js';

export const METRIC_KEYS = ['connected', 'p50', 'p95', 'p99', 'retryCount'];

export function createMetricsHistory({ limit = 900 } = {}) {
  const samples = [];

  function sample(state) {
    const m = state?.metrics;
    if (!m) return;
    samples.push({
      ts: state.ts,
      connected: m.connected,
      p50: m.p50,
      p95: m.p95,
      p99: m.p99,
      retryCount: m.retryCount,
    });
    if (samples.length > limit) samples.splice(0, samples.length - limit);
  }

  function stats() {
    const out = {
      samples: samples.length,
      from: samples[0]?.ts ?? null,
      to: samples[samples.length - 1]?.ts ?? null,
      metrics: {},
    };
    for (const key of METRIC_KEYS) {
      const vals = samples.map((s) => s[key]).filter((v) => typeof v === 'number');
      if (!vals.length) {
        out.metrics[key] = null;
        continue;
      }
      out.metrics[key] = {
        current: vals[vals.length - 1],
        min: Math.min(...vals),
        max: Math.max(...vals),
        avg: Math.round(vals.reduce((a, b) => a + b, 0) / vals.length),
      };
    }
    return out;
  }

  function series(key) {
    return samples.map((s) => ({ ts: s.ts, value: s[key] })).filter((p) => typeof p.value === 'number');
  }

  return {
    sample,
    stats,
    series,
    clear() {
      samples.length = 0;
    },
    get samples() {
      return samples;
    },
  };
}

// --- export helpers -----------------------------------------------------------

/** rows: array of objects; columns: [{key, label?}] → RFC4180-ish CSV. */
export function toCSV(rows, columns) {
  const esc = (v) => {
    const s = v == null ? '' : String(v);
    return /[",\n\r]/.test(s) ? `"${s.replaceAll('"', '""')}"` : s;
  };
  const lines = [columns.map((c) => esc(c.label ?? c.key)).join(',')];
  for (const row of rows ?? []) lines.push(columns.map((c) => esc(row[c.key])).join(','));
  return lines.join('\r\n');
}

/** Trigger a browser download (no-op outside a DOM, e.g. unit tests). */
export function downloadFile(filename, mime, text) {
  if (typeof document === 'undefined' || typeof URL.createObjectURL !== 'function') return false;
  const blob = new Blob([text], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  return true;
}

const stamp = () => new Date().toISOString().replaceAll(':', '-').replace(/\.\d+Z$/, 'Z');

export function exportMetricsCSV(history, buildingId) {
  const csv = toCSV(history.samples, [
    { key: 'ts', label: 'ts' },
    { key: 'connected', label: 'connected' },
    { key: 'p50', label: 'p50_ms' },
    { key: 'p95', label: 'p95_ms' },
    { key: 'p99', label: 'p99_ms' },
    { key: 'retryCount', label: 'retryCount' },
  ]);
  return downloadFile(`aegis-metrics-${buildingId}-${stamp()}.csv`, 'text/csv;charset=utf-8', csv);
}

export function exportMetricsJSON(history, buildingId) {
  const payload = {
    feed: 'status/1',
    buildingId,
    exportedAt: new Date().toISOString(),
    window: { from: history.stats().from, to: history.stats().to, samples: history.samples.length },
    stats: history.stats().metrics,
    samples: history.samples,
  };
  return downloadFile(
    `aegis-metrics-${buildingId}-${stamp()}.json`,
    'application/json;charset=utf-8',
    JSON.stringify(payload, null, 2),
  );
}

/** Tiny inline SVG sparkline for the reports panel. */
export function sparklineSVG(values, { width = 240, height = 42, stroke = '#38bdf8' } = {}) {
  if (!values.length) return '';
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const n = values.length;
  const pts = values
    .map((v, i) => `${((i / (n - 1 || 1)) * (width - 4) + 2).toFixed(1)},${(height - 3 - ((v - min) / span) * (height - 6)).toFixed(1)}`)
    .join(' ');
  return (
    `<svg viewBox="0 0 ${width} ${height}" width="${width}" height="${height}" role="img" aria-label="${t('reports.sparklineAria')}">` +
    `<polyline points="${pts}" fill="none" stroke="${stroke}" stroke-width="1.5" stroke-linejoin="round"/></svg>`
  );
}
