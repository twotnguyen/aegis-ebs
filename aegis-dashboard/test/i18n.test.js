// i18n regression tests (node --test) — headless: no DOM, no network.
// Run: npm test (logic.test.js + i18n.test.js)

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { DICT, langList, getLang, setLang, t, applyStatic, subscribe } from '../src/i18n.js';

/** Restore the process language after each test that changes it. */
function withLang(fn) {
  const original = getLang();
  try {
    return fn();
  } finally {
    setLang(original);
  }
}

test('DICT.vi and DICT.en have exactly the same key set', () => {
  const vi = Object.keys(DICT.vi).sort();
  const en = Object.keys(DICT.en).sort();
  assert.deepEqual(vi, en);
  assert.ok(vi.length > 150, `expected a complete dictionary, got ${vi.length} keys`);
  for (const key of vi) {
    assert.equal(typeof DICT.vi[key], 'string', `vi.${key}`);
    assert.equal(typeof DICT.en[key], 'string', `en.${key}`);
    assert.ok(DICT.vi[key].trim(), `empty vi value for ${key}`);
    assert.ok(DICT.en[key].trim(), `empty en value for ${key}`);
  }
});

test('interpolation substitutes every {var} and survives missing vars', () => {
  withLang(() => {
    setLang('en');
    assert.equal(t('reports.samples', { count: 7 }), '7 samples');
    assert.equal(t('incidents.showingLast', { count: 5, total: 20 }), 'showing last 5 of 20 events');
    assert.equal(t('funnel.chip.ackRate', { value: '80%' }), 'ack rate 80%');
    assert.equal(t('operator.bridge', { bridge: 'http://localhost:9445' }), 'bridge: http://localhost:9445');
    // a token without a supplied value stays verbatim (visible, not silently dropped)
    assert.equal(t('reports.samples', {}), '{count} samples');
    setLang('vi');
    assert.equal(t('reports.samples', { count: 7 }), '7 mẫu');
    assert.equal(t('funnel.chip.ackRate', { value: '80%' }), 'tỷ lệ ACK 80%');
  });
});

test('missing keys fall back to English, then to the key itself', () => {
  withLang(() => {
    setLang('vi');
    assert.equal(t('__not_a_real_key__'), '__not_a_real_key__');
    // structurally guarantee fallback behaviour with a temporary en-only key
    DICT.en.__fallback_probe__ = 'EN FALLBACK';
    try {
      assert.equal(t('__fallback_probe__'), 'EN FALLBACK');
    } finally {
      delete DICT.en.__fallback_probe__;
    }
  });
});

test('getLang/setLang work headlessly, default to vi and persist the choice', () => {
  withLang(() => {
    assert.deepEqual(langList, ['vi', 'en']);
    assert.ok(langList.includes(getLang()));
    assert.equal(setLang('en'), 'en');
    assert.equal(getLang(), 'en');
    assert.equal(t('metrics.title'), 'Metrics');
    assert.equal(setLang('vi'), 'vi');
    assert.equal(getLang(), 'vi');
    assert.equal(t('metrics.title'), 'Chỉ số');
    // unknown input normalises to the documented default instead of throwing
    assert.equal(setLang('fr'), 'vi');
    assert.equal(getLang(), 'vi');
    // headless DOM helpers are inert but callable
    assert.equal(applyStatic(null), 0);
    assert.equal(applyStatic(), 0);
  });
});

test('subscribe notifies on language change and can be detached', () => {
  withLang(() => {
    const seen = [];
    const off = subscribe((lang) => seen.push(lang));
    setLang('en');
    setLang('vi');
    off();
    setLang('en');
    assert.deepEqual(seen, ['en', 'vi']);
    assert.equal(getLang(), 'en');
  });
});

test('every data-i18n key in index.html exists in the dictionary', () => {
  const here = new URL('.', import.meta.url);
  const html = readFileSync(fileURLToPath(new URL('../index.html', here)), 'utf8');
  const attrs = [...html.matchAll(/data-i18n(?:-(?:html|title|placeholder|aria-label))?="([^"]+)"/g)].map((m) => m[1]);
  assert.ok(attrs.length >= 100, `expected annotated static UI, got ${attrs.length} attributes`);
  const known = new Set([...Object.keys(DICT.vi), ...Object.keys(DICT.en)]);
  const unknown = [...new Set(attrs.filter((key) => !known.has(key)))];
  assert.deepEqual(unknown, []);
});
