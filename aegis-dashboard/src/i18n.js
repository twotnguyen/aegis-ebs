// ---------------------------------------------------------------------------
// Aegis EBS — VI/EN interface translations.
//
// Headless-safe: no top-level document/window/localStorage access, so pure
// logic modules (and node --test) can import t() without a DOM. DICT.vi and
// DICT.en are built from ONE entry list — key parity is structural.
//
// Wire identifiers (status/1, INCIDENT_EVENT, EBSP/TLS, zoneCode, severity
// values, command names, API field names) are intentionally NOT translated.
// ---------------------------------------------------------------------------

const STORAGE_KEY = 'aegis.lang';
export const langList = ['vi', 'en'];

// [key, tiếng Việt, English]. Keep the third column free of DOM unless the key
// is consumed through data-i18n-html (those values are trusted static markup).
const ENTRIES = [
  ['title', 'Aegis EBS — Bảng điều khiển bản sao số', 'Aegis EBS — Digital Twin Dashboard'],
  ['brand', 'AEGIS EBS', 'AEGIS EBS'],
  ['header.subtitle', 'Bản sao số · Tòa AEGIS B01 · 10 tầng · 4 khu/tầng', 'Digital Twin · AEGIS Tower B01 · 10 floors · 4 zones/floor'],
  ['nav.aria', 'Chế độ xem bảng điều khiển', 'Dashboard views'],
  ['lang.toggleAria', 'Đổi ngôn ngữ giao diện', 'Switch interface language'],

  // tabs
  ['tab.twin', 'Twin', 'Twin'],
  ['tab.incidents', 'Sự cố', 'Incidents'],
  ['tab.chaos', 'Chaos', 'Chaos'],
  ['tab.reports', 'Báo cáo', 'Reports'],
  ['tab.funnel', 'Phễu', 'Funnel'],
  ['tab.operator', 'Vận hành', 'Operator'],
  ['tab.twin.title', 'Bản sao số 3D (phím 1)', '3D digital twin (key 1)'],
  ['tab.incidents.title', 'Dòng thời gian & phát lại sự cố (phím 2)', 'Incident timeline & replay (key 2)'],
  ['tab.chaos.title', 'Bảng điều khiển chaos (phím 3)', 'Chaos control panel (key 3)'],
  ['tab.reports.title', 'Báo cáo độ trễ & xuất file (phím 4)', 'Latency reports & export (key 4)'],
  ['tab.funnel.title', 'Phễu phân phối theo cảnh báo (phím 5)', 'Per-alert delivery funnel (key 5)'],
  ['tab.operator.title', 'Phát/hủy thật qua bridge (phím 6)', 'Real publish/cancel via bridge (key 6)'],

  // top badges + feed states
  ['badge.replay', 'PHÁT LẠI', 'REPLAY'],
  ['badge.connecting', 'ĐANG KẾT NỐI…', 'CONNECTING…'],
  ['badge.live', 'TRỰC TIẾP', 'LIVE'],
  ['badge.reconnecting', 'ĐANG KẾT NỐI LẠI…', 'RECONNECTING…'],
  ['badge.mock', 'NGUỒN MÔ PHỎNG', 'MOCK FEED'],
  ['badge.closed', 'ĐÃ ĐÓNG', 'CLOSED'],

  // side HUD
  ['metrics.title', 'Chỉ số', 'Metrics'],
  ['metrics.connected', 'Đã kết nối', 'Connected'],
  ['metrics.p50', 'p50 (ms)', 'p50 (ms)'],
  ['metrics.p95', 'p95 (ms)', 'p95 (ms)'],
  ['metrics.p99', 'p99 (ms)', 'p99 (ms)'],
  ['metrics.retries', 'Số lần thử lại', 'Retries'],
  ['alerts.title', 'Cảnh báo hoạt động', 'Active alerts'],
  ['alerts.none', 'Không có cảnh báo hoạt động', 'No active alerts'],
  ['alerts.openFunnel', 'Mở phễu phân phối cho cảnh báo này', 'Open delivery funnel for this alert'],
  ['legend.title', 'Chú giải', 'Legend'],
  ['legend.online', 'Trực tuyến / đã ACK', 'Online / acked'],
  ['legend.warning', 'Cảnh báo', 'Warning'],
  ['legend.critical', 'Nghiêm trọng', 'Critical'],
  ['legend.offline', 'Ngoại tuyến', 'Offline'],

  // shared controls
  ['common.enable', 'Bật', 'Enable'],
  ['common.disable', 'Tắt', 'Disable'],
  ['common.idle', 'IDLE', 'IDLE'],
  ['common.active', 'ACTIVE', 'ACTIVE'],
  ['common.exportCsv', 'Xuất CSV', 'Export CSV'],
  ['common.exportJson', 'Xuất JSON', 'Export JSON'],

  // incidents / replay
  ['incidents.title', 'Dòng thời gian sự cố', 'Incident timeline'],
  ['incidents.loadDemo', 'Tải sự cố demo', 'Load demo incident'],
  ['incidents.clear', 'Xóa', 'Clear'],
  ['incidents.exportJson', 'Xuất JSON', 'Export JSON'],
  ['incidents.exportCsv', 'Xuất CSV', 'Export CSV'],
  ['incidents.empty', 'Chưa có sự cố — chờ nguồn dữ liệu, tải sự cố demo hoặc bật chaos.', 'No events yet — wait for the feed, load the demo incident, or enable chaos.'],
  ['incidents.showingLast', 'hiển thị {count} sự kiện cuối trong tổng {total}', 'showing last {count} of {total} events'],
  ['incidents.clickRow', '{count} sự kiện · bấm một dòng để xem/phát lại', '{count} events · click a row to inspect/replay'],
  ['incidents.replayHint', 'Bấm ▶ để phát lại dòng thời gian trên bản sao 3D, hoặc bấm một dòng sự kiện.', 'Press ▶ to replay the timeline on the 3D twin, or click an event row.'],
  ['replay.back', 'Về đầu', 'Back to start'],
  ['replay.stepBack', 'Lùi một bước', 'Step back'],
  ['replay.playPause', 'Phát / tạm dừng', 'Play / pause'],
  ['replay.stepForward', 'Tiến một bước', 'Step forward'],
  ['replay.speed', 'Tốc độ phát lại', 'Replay speed'],
  ['replay.positionAria', 'Vị trí phát lại', 'Replay position'],
  ['replay.atStart', 'Ở đầu dòng thời gian — bấm ▶ để phát lại.', 'At start of timeline — press ▶ to replay.'],
  ['replay.actor', 'người thực hiện', 'actor'],
  ['replay.corr', 'corr', 'corr'],

  // chaos panel
  ['chaos.title', 'Điều khiển chaos', 'Chaos control'],
  ['chaos.mockCommands', 'lệnh mô phỏng', 'mock commands'],
  ['chaos.note.mock', 'Lệnh chạy trên bản mô phỏng cục bộ (status/1 không có opcode chaos). Hiệu ứng xuất hiện ở bản sao, chỉ số và dòng thời gian.', 'Commands execute on the local mock simulation (status/1 has no chaos opcode). Effects appear in the twin, metrics and timeline.'],
  ['chaos.note.live', 'Nguồn đang LIVE — lệnh chaos chỉ là mô phỏng. Bật kịch bản sẽ chuyển sang nguồn MOCK; “Return to live” tiếp tục dò máy chủ.', 'Feed is LIVE — chaos commands are mock-only. Enabling a scenario switches to the MOCK simulation; “Return to live” resumes probing the server.'],
  ['chaos.c01', 'C01 · Bỏ ACK', 'C01 · Drop ACK'],
  ['chaos.c01.hint', 'Áp chế ACK → unacked tăng → giao hàng THẤT BẠI sau 3 lần thử lại.', 'Suppress ACKs → unacked grows → deliveries FAIL after 3 retries.'],
  ['chaos.c02', 'C02 · Trễ ACK', 'C02 · Delay ACK'],
  ['chaos.c02.hint', 'Đẩy cao các phân vị độ trễ đầu-cuối (p50/p95/p99).', 'Inflate end-to-end latency percentiles (p50/p95/p99).'],
  ['chaos.c04', 'C04 · Ngắt kết nối', 'C04 · Disconnect'],
  ['chaos.c04.hint', 'Rớt một tỷ lệ thiết bị đang kết nối ở mỗi nhịp mô phỏng.', 'Drop a share of connected devices on every mock tick.'],
  ['chaos.extraDelay', 'độ trễ thêm', 'extra delay'],
  ['chaos.share', 'tỷ lệ', 'share'],
  ['chaos.allOff', 'Tắt tất cả', 'All off'],
  ['chaos.returnLive', 'Về nguồn trực tiếp', 'Return to live feed'],
  ['chaos.feed', 'nguồn', 'feed'],
  ['chaos.feedPill', 'nguồn: –', 'feed: –'],
  ['chaos.forcedMock', 'bắt buộc mock', 'forced mock'],
  ['chaos.logTitle', 'Nhật ký lệnh', 'Command log'],
  ['chaos.col.ts', 'thời gian', 'ts'],
  ['chaos.col.id', 'id', 'id'],
  ['chaos.col.command', 'lệnh', 'command'],
  ['chaos.col.params', 'tham số', 'params'],
  ['chaos.col.status', 'trạng thái', 'status'],
  ['chaos.noCommands', 'Chưa có lệnh nào.', 'No commands yet.'],

  // reports
  ['reports.title', 'Báo cáo độ trễ phân phối', 'Delivery latency report'],
  ['reports.samples', '{count} mẫu', '{count} samples'],
  ['reports.samplesInitial', '0 mẫu', '0 samples'],
  ['reports.window', 'cửa sổ: {from} → {to} · lấy mẫu từ mọi snapshot/delta', 'window: {from} → {to} · sampled from every snapshot/delta'],
  ['reports.windowInitial', 'cửa sổ: –', 'window: –'],
  ['reports.noSamples', 'cửa sổ: chưa có mẫu', 'window: no samples yet'],
  ['reports.col.metric', 'chỉ số', 'metric'],
  ['reports.col.current', 'hiện tại', 'current'],
  ['reports.col.min', 'nhỏ nhất', 'min'],
  ['reports.col.avg', 'trung bình', 'avg'],
  ['reports.col.max', 'lớn nhất', 'max'],
  ['reports.exportCsv', 'Xuất chỉ số CSV', 'Export metrics CSV'],
  ['reports.exportJson', 'Xuất chỉ số JSON', 'Export metrics JSON'],
  ['reports.resetWindow', 'Đặt lại cửa sổ', 'Reset window'],
  ['reports.sparklineAria', 'đồ thị xu hướng', 'sparkline'],

  // funnel
  ['funnel.title', 'Phễu phân phối', 'Delivery funnel'],
  ['funnel.hint', 'Mỗi cảnh báo được suy ra từ bộ đếm khu vực kể từ lúc cảnh báo mở: targeted = thiết bị được cấp phát trong khu; received/acknowledged/failed = phần tăng (giới hạn bởi targeted). Xem <code>src/funnel.js</code>.', 'Per alert, derived from zone counters since the alert opened: targeted = provisioned devices in zone; received/acknowledged/failed = increments (clamped to targeted). See <code>src/funnel.js</code>.'],
  ['funnel.countInitial', '0 cảnh báo', '0 alerts'],
  ['funnel.count', '{active} hoạt động / {total} đang theo dõi', '{active} active / {total} tracked'],
  ['funnel.selectAria', 'Chọn cảnh báo', 'Select alert'],
  ['funnel.stage.targeted', 'Mục tiêu', 'Targeted'],
  ['funnel.stage.received', 'Đã nhận', 'Received'],
  ['funnel.stage.acknowledged', 'Đã xác nhận', 'Acknowledged'],
  ['funnel.stage.failed', 'Thất bại', 'Failed'],
  ['funnel.noAlerts', 'Chưa theo dõi cảnh báo nào', 'No alerts tracked yet'],
  ['funnel.noFunnels', 'Phễu xuất hiện khi có cảnh báo hoạt động (hoặc đã hoạt động trong phiên này).', 'Funnels appear when alerts are active (or were active this session).'],
  ['funnel.chip.pending', 'chờ {count}', 'pending {count}'],
  ['funnel.chip.unacked', 'chưa ACK {count}', 'unacked {count}'],
  ['funnel.chip.ackRate', 'tỷ lệ ACK {value}', 'ack rate {value}'],
  ['funnel.chip.failRate', 'tỷ lệ lỗi {value}', 'fail rate {value}'],
  ['funnel.detail.headline', 'tiêu đề (tổng khu): {message}', 'headline (zone totals): {message}'],
  ['funnel.detail.zoneTotals', ' — nhận {received} · ACK {acknowledged} · lỗi {failed}', ' — recv {received} · ack {acknowledged} · failed {failed}'],
  ['funnel.detail.since', ' · phễu đếm giao hàng từ lúc cảnh báo mở {time}', ' · funnel counts deliveries since alert opened {time}'],
  ['funnel.detail.closed', ' · đã đóng {time}', ' · closed {time}'],
  ['funnel.detail.zone', ' · khu {code} (tầng {floor})', ' · zone {code} (floor {floor})'],
  ['funnel.allAlerts', 'Tất cả cảnh báo', 'All alerts'],
  ['funnel.col.alert', 'cảnh báo', 'alert'],
  ['funnel.col.zone', 'khu', 'zone'],
  ['funnel.col.state', 'trạng thái', 'state'],
  ['funnel.col.targeted', 'mục tiêu', 'targeted'],
  ['funnel.col.received', 'đã nhận', 'received'],
  ['funnel.col.acked', 'đã ACK', 'acked'],
  ['funnel.col.failed', 'lỗi', 'failed'],
  ['funnel.col.ackPct', 'tỷ lệ ACK %', 'ack %'],
  ['funnel.active', 'hoạt động', 'active'],
  ['funnel.closed', 'đã đóng', 'closed'],
  ['funnel.exportNote', 'suy ra từ bộ đếm khu vực kể từ lúc cảnh báo mở (xem src/funnel.js)', 'derived from zone counters since alert opened (see src/funnel.js)'],

  // operator bridge
  ['operator.title', 'Bridge vận hành', 'Operator bridge'],
  ['operator.note', 'Lệnh thật qua EBSP/TLS — KHÔNG phải mock; tab Chaos vẫn là mock.', 'Real commands via EBSP/TLS — NOT mock; the Chaos tab stays mock-only.'],
  ['operator.user', 'Người dùng', 'User'],
  ['operator.password', 'Mật khẩu', 'Password'],
  ['operator.floor', 'Tầng', 'Floor'],
  ['operator.zone', 'Khu', 'Zone'],
  ['operator.priority', 'Mức ưu tiên', 'Priority'],
  ['operator.category', 'Danh mục', 'Category'],
  ['operator.titleField', 'Tiêu đề', 'Title'],
  ['operator.instruction', 'Hướng dẫn', 'Instruction'],
  ['operator.silent', 'silent — 1 thiết bị KHÔNG tự ACK (thử lại → FAILED)', 'silent — 1 device does NOT auto-ACK (retry → FAILED)'],
  ['operator.count', 'Số lượng', 'Count'],
  ['operator.alertId', 'Alert ID (publish gần nhất / để trống để cancel)', 'Alert ID (latest publish / leave empty to cancel)'],
  ['operator.alertIdPlaceholder', 'tự động điền sau publish', 'filled automatically after publish'],
  ['operator.ping', 'Ping health + stats', 'Ping health + stats'],
  ['operator.fleetStart', 'Khởi động đội thiết bị', 'Fleet start'],
  ['operator.fleetStop', 'Dừng đội thiết bị', 'Fleet stop'],
  ['operator.publish', 'Phát cảnh báo', 'Publish'],
  ['operator.retryDemo', 'Demo thử lại (F01-A + silent)', 'Retry demo (F01-A + silent)'],
  ['operator.cancel', 'Hủy cảnh báo', 'Cancel alert'],
  ['operator.hint', 'Fleet start dùng <code>count</code> từ ô Count và <code>silentFloor=1</code> (1 device im lặng). Override bridge: <code>?bridge=http://host:port</code>.', 'Fleet start uses <code>count</code> from the Count field and <code>silentFloor=1</code> (1 silent device). Bridge override: <code>?bridge=http://host:port</code>.'],
  ['operator.logTitle', 'Nhật ký bridge', 'Bridge log'],
  ['operator.logPill', 'thời gian · alertId · deliveryCount', 'time · alertId · deliveryCount'],
  ['operator.logEmpty', 'Chưa có lệnh bridge. Bấm “Ping health + stats” để kiểm tra kết nối.', 'No bridge commands yet. Click “Ping health + stats” to check the connection.'],
  ['operator.bridge', 'bridge: {bridge}', 'bridge: {bridge}'],
  ['operator.fleet', 'đội thiết bị: {count}', 'fleet: {count}'],
  ['operator.fleetNone', 'đội thiết bị: –', 'fleet: –'],
  ['operator.fleetOnline', ' · trực tuyến {count}', ' · online {count}'],
  ['operator.fleetSilent', ' · im lặng {count}', ' · silent {count}'],
  ['operator.busy', 'đang bận', 'busy'],
  ['operator.ok', 'ổn', 'ok'],
  ['operator.error', 'lỗi', 'error'],
  ['operator.errorShort', 'LỖI', 'ERROR'],
  ['operator.sending', '{label} · đang gửi lệnh…', '{label} · sending command…'],
  ['operator.timeout', '{path} → hết giờ sau 15s', '{path} → timeout after 15s'],
  ['operator.emptyResponse', 'phản hồi rỗng', 'empty response'],
  ['operator.fleetStats', 'thống kê đội thiết bị', 'fleet stats'],
  ['operator.noAlertId', 'chưa có alertId — hãy publish trước hoặc điền ô Alert ID', 'no alertId yet — publish first or fill in the Alert ID field'],
  ['operator.idle', 'nhàn rỗi', 'idle'],
  ['operator.logOk', 'OK · alertId={id} · deliveryCount={dc}{state}', 'OK · alertId={id} · deliveryCount={dc}{state}'],
  ['operator.logState', ' · trạng thái={state}', ' · state={state}'],
  ['operator.logHealthOk', 'health OK · {data}', 'health OK · {data}'],
  ['operator.logFleetStartOk', 'fleet start OK · count={count} · silentFloor=1{data}', 'fleet start OK · count={count} · silentFloor=1{data}'],
  ['operator.logFleetStopOk', 'fleet stop OK{data}', 'fleet stop OK{data}'],
  ['operator.logRetryHint', 'retry-demo · F01-A · silent=true → 11 ACK + 1 retry→FAILED', 'retry-demo · F01-A · silent=true → 11 ACK + 1 retry→FAILED'],
  ['event.mock.summary', 'FIRE_DRILL tầng 5 — zone B + lân cận', 'FIRE_DRILL on floor 5 — zone B and adjacent zones'],
  ['event.fallback.kind', 'sự cố', 'incident'],
  ['event.fallback.state', 'không rõ', 'unknown'],
  ['event.fallback.alertId', 'cảnh báo', 'alert'],
  ['event.fallback.severity', '?', '?'],

  // footer / misc
  ['feed.live', 'nguồn trực tiếp', 'live feed'],
  ['feed.mock', 'nguồn mô phỏng', 'mock feed'],
  ['feed.liveWithUrl', 'nguồn: trực tiếp {url}', 'feed: live {url}'],
  ['feed.mockOffline', 'nguồn: mô phỏng (máy chủ ngoại tuyến)', 'feed: mock (server offline)'],
  ['feed.generic', 'nguồn: {mode}', 'feed: {mode}'],
  ['footer.feed', 'nguồn: –', 'feed: –'],
  ['footer.lastUpdate', 'cập nhật lần cuối: {time}', 'last update: {time}'],
  ['footer.lastUpdateInitial', 'cập nhật lần cuối: –', 'last update: –'],
  ['footer.hint', 'kéo để xoay · cuộn để phóng to · trỏ vào khu để xem chi tiết · phím 1–6 đổi chế độ xem', 'drag to orbit · scroll to zoom · hover a zone for details · keys 1–6 switch views'],
  ['noscript', 'Bảng điều khiển này cần JavaScript (WebGL).', 'This dashboard requires JavaScript (WebGL).'],
  ['webgl.notice', 'WebGL không khả dụng trong trình duyệt này ({message}). Bản sao 3D bị vô hiệu hóa; các bảng dòng thời gian, chaos, báo cáo và phễu vẫn hoạt động.', 'WebGL is not available in this browser ({message}). The 3D twin is disabled; timeline, chaos, reports and funnel panels still work.'],

  // legacy fallback tooltip in main.js
  ['tooltip.devices', 'Thiết bị', 'Devices'],
  ['tooltip.online', 'Trực tuyến', 'Online'],
  ['tooltip.received', 'Đã nhận', 'Received'],
  ['tooltip.acknowledged', 'Đã xác nhận', 'Acknowledged'],
  ['tooltip.failed', 'Thất bại', 'Failed'],
  ['tooltip.unacked', 'Chưa ACK', 'Unacked'],
  ['tooltip.body', 'thiết bị {devices}/{total} trực tuyến · nhận {received} · ACK {acknowledged} · lỗi {failed}', 'devices {devices}/{total} online · recv {received} · ack {acknowledged} · failed {failed}'],

  // 3D HUD (src/three/ui.js)
  ['hud.title', '{name} — BẢN SAO SỐ', '{name} — DIGITAL TWIN'],
  ['hud.simulation', 'MÔ PHỎNG', 'SIMULATION'],
  ['hud.subtitle', '{floors} tầng · {zones} khu/tầng · {sectors} khu vực', '{floors} floors · {zones} zones/floor · {sectors} sectors'],
  ['hud.floor', 'Tầng', 'Floor'],
  ['hud.floorSelector', 'Chọn tầng', 'Floor selector'],
  ['hud.showAllFloors', 'Hiện tất cả tầng', 'Show all floors'],
  ['hud.floorN', 'Tầng {n}', 'Floor {n}'],
  ['hud.all', 'TẤT CẢ', 'ALL'],
  ['hud.zoneStatus', 'Trạng thái khu vực', 'Zone status'],
  ['hud.viewMode', 'Chế độ xem bản sao', 'Twin view mode'],
  ['hud.viewTitle', 'Chế độ xem {view}', '{view} view'],
  ['hud.allWorst', 'Tất cả tầng — mức xấu nhất: {sev}', 'All floors — worst: {sev}'],
  ['hud.floorWorst', 'Tầng {n} — mức xấu nhất: {sev}', 'Floor {n} — worst: {sev}'],
  ['sev.online', 'Trực tuyến', 'Online'],
  ['sev.warning', 'Cảnh báo', 'Warning'],
  ['sev.critical', 'Nghiêm trọng', 'Critical'],
  ['sev.offline', 'Ngoại tuyến', 'Offline'],
  ['view.overview', 'Tổng quan', 'Overview'],
  ['view.cutaway', 'Cắt bỏ', 'Cutaway'],
  ['view.explode', 'Tách tầng', 'Explode'],
  ['view.night', 'Ban đêm', 'Night'],

  // event summaries (server payload values stay verbatim)
  ['event.opened', '{kind} đã mở{summary}', '{kind} opened{summary}'],
  ['event.state', 'trạng thái → {state}{reason}', 'state → {state}{reason}'],
  ['event.published', '{id} đã phát ({severity}){message}', '{id} published ({severity}){message}'],
  ['event.updated', '{id} đã cập nhật → {severity}', '{id} updated → {severity}'],
  ['event.cancelled', '{id} đã hủy (tombstone honored)', '{id} cancelled (tombstone honored)'],
  ['event.deliverySent', '+{count} giao hàng đã gửi{zone}', '+{count} delivery sent{zone}'],
  ['event.ackReceived', '+{count} ACK_RECEIVED{zone}', '+{count} ACK_RECEIVED{zone}'],
  ['event.ackUser', '+{count} ACK_USER{zone}', '+{count} ACK_USER{zone}'],
  ['event.retriesScheduled', '{count} lần thử lại đã lên lịch{zone}', '{count} retries scheduled{zone}'],
  ['event.deliveryFailed', '{count} giao hàng THẤT BẠI sau 3 lần thử lại{zone}', '{count} delivery FAILED after 3 retries{zone}'],
  ['event.deviceOffline', '{count} thiết bị ngoại tuyến{zone}', '{count} device(s) offline{zone}'],
  ['event.deviceOnline', '{count} thiết bị kết nối lại{zone}', '{count} device(s) back online{zone}'],
  ['event.enabled', 'ĐÃ BẬT', 'ENABLED'],
  ['event.disabled', 'ĐÃ TẮT', 'DISABLED'],
];

export const DICT = {
  vi: Object.fromEntries(ENTRIES.map(([key, vi]) => [key, vi])),
  en: Object.fromEntries(ENTRIES.map(([key, , en]) => [key, en])),
};

// --- current language -----------------------------------------------------------

function readStoredLang() {
  try {
    const stored = globalThis.localStorage?.getItem(STORAGE_KEY);
    return langList.includes(stored) ? stored : null;
  } catch {
    return null; // storage disabled / security error — stay headless-safe
  }
}

let current = readStoredLang() ?? 'vi';
const listeners = new Set();

export function getLang() {
  return current;
}

export function setLang(lang) {
  const next = langList.includes(lang) ? lang : 'vi';
  const changed = next !== current;
  current = next;
  try {
    globalThis.localStorage?.setItem(STORAGE_KEY, current);
  } catch { /* storage unavailable — language still applies in memory */ }
  try {
    if (typeof document !== 'undefined' && document.documentElement) {
      document.documentElement.lang = current;
    }
  } catch { /* no DOM */ }
  if (changed) for (const fn of [...listeners]) {
    try { fn(current); } catch (err) { console.warn('[i18n] listener failed:', err); }
  }
  return current;
}

/** Translate a key, substituting {vars}. Missing keys fall back to English. */
export function t(key, vars) {
  const table = DICT[current] ?? DICT.en;
  let text = table[key];
  if (text === undefined) text = DICT.en[key];
  if (text === undefined) return String(key);
  if (vars && typeof vars === 'object') {
    text = text.replace(/\{(\w+)\}/g, (match, name) => (
      Object.hasOwn(vars, name) && vars[name] != null ? String(vars[name]) : match
    ));
  }
  return text;
}

/** Subscribe to language changes; returns an unsubscribe function. */
export function subscribe(fn) {
  if (typeof fn !== 'function') return () => {};
  listeners.add(fn);
  return () => listeners.delete(fn);
}

const I18N_ATTRS = [
  ['data-i18n', 'text'],
  ['data-i18n-html', 'html'],
  ['data-i18n-title', 'title'],
  ['data-i18n-placeholder', 'placeholder'],
  ['data-i18n-aria-label', 'aria-label'],
];

const SELECTOR = I18N_ATTRS.map(([attr]) => `[${attr}]`).join(',');

/**
 * Translate every annotated node under `root` (defaults to document).
 * Returns the number of attributes applied. Safe to call again on switch.
 */
export function applyStatic(root) {
  const scope = root ?? (typeof document !== 'undefined' ? document : null);
  if (!scope || typeof scope.querySelectorAll !== 'function') return 0;
  const nodes = [...scope.querySelectorAll(SELECTOR)];
  if (typeof scope.matches === 'function' && scope.matches(SELECTOR)) nodes.unshift(scope);
  let applied = 0;
  for (const el of nodes) {
    for (const [attr, target] of I18N_ATTRS) {
      const key = el.getAttribute?.(attr);
      if (!key) continue;
      const value = t(key);
      if (target === 'text') el.textContent = value;
      else if (target === 'html') el.innerHTML = value;
      else el.setAttribute(target, value);
      applied += 1;
    }
  }
  return applied;
}
