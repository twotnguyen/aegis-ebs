Language: English | Vietnamese original: README.md

# English documentation index — AEGIS-EBS

This is the entry point for the English parallel documentation of the CMP180 capstone project **AEGIS-EBS — Emergency Broadcast System**. Every file below mirrors a Vietnamese original; figures, runIds, commit SHAs, ports, commands, identifiers and paths are preserved from that original. The language-switch guide is [`docs/I18N.md`](../I18N.md).

> ⚠️ SIMULATION / TRAINING ONLY. This is an academic prototype; it does not replace certified fire alarm, evacuation or life-safety systems.

## Start here

| English | Vietnamese original | Contents |
|---|---|---|
| [`README.en.md`](../../README.en.md) | [`README.md`](../../README.md) | Root overview: features, 8 modules, quickstart, ports, evidence |
| [`en/USER-GUIDE.md`](USER-GUIDE.md) | [`../USER-GUIDE.md`](../USER-GUIDE.md) | Operation guide: web-only demo, server/client/simulator, troubleshooting |
| [`en/BUILD.md`](BUILD.md) | [`../BUILD.md`](../BUILD.md) | Reproducible build, fresh install < 30 minutes, per-module tests |

## Protocol, architecture & data

| English | Vietnamese original | Contents |
|---|---|---|
| [`en/PROTOCOL.md`](PROTOCOL.md) | [`../PROTOCOL.md`](../PROTOCOL.md) | EBSP/2.0 shared wire contract (immutable) |
| [`en/API.md`](API.md) | [`../API.md`](../API.md) | Message catalog, envelope, JSON examples, error codes |
| [`en/ARCHITECTURE.md`](ARCHITECTURE.md) | [`../ARCHITECTURE.md`](../ARCHITECTURE.md) | 4 planes, deployment, concurrency, publish→fanout→ACK→recovery |
| [`en/ERD.md`](ERD.md) | [`../ERD.md`](../ERD.md) | ERD: 26 tables, 36 FK relationships, CHECK/UNIQUE/index specs |
| [`en/ADR.md`](ADR.md) | [`../ADR.md`](../ADR.md) | 8 architecture decision records (Context/Decision/Consequences/Alternatives) |

## Security

| English | Vietnamese original | Contents |
|---|---|---|
| [`en/SECURITY.md`](SECURITY.md) | [`../SECURITY.md`](../SECURITY.md) | Threat model, controls, RBAC matrix, truststore guide, OWASP checklist |
| [`en/SECURITY-ASSESSMENT.md`](SECURITY-ASSESSMENT.md) | [`../SECURITY-ASSESSMENT.md`](../SECURITY-ASSESSMENT.md) | Independent assessment: implemented ✅ / demo 🟡 / designed 🔧 |

## Operations, testing & defense

| English | Vietnamese original | Contents |
|---|---|---|
| [`en/DEMO-RUNBOOK.md`](DEMO-RUNBOOK.md) | [`../DEMO-RUNBOOK.md`](../DEMO-RUNBOOK.md) | 12-minute demo runbook (blueprint §14) |
| [`en/TEST-PLAN.md`](TEST-PLAN.md) | [`../TEST-PLAN.md`](../TEST-PLAN.md) | 30 acceptance tests + chaos C01–C16 mapped to modules/commands |
| [`en/CHAOS-DESIGN.md`](CHAOS-DESIGN.md) | [`../CHAOS-DESIGN.md`](../CHAOS-DESIGN.md) | Extended chaos design C17–C28 (design, not measured) |
| [`en/DEFENSE-QA.md`](DEFENSE-QA.md) | [`../DEFENSE-QA.md`](../DEFENSE-QA.md) | 20 grounded defense questions and answers |
| [`en/SLIDES.md`](SLIDES.md) | [`../SLIDES.md`](../SLIDES.md) | 16-slide / 12-minute defense outline + Q&A + backup slides |
| [`en/PERFORMANCE.md`](PERFORMANCE.md) | [`../PERFORMANCE.md`](../PERFORMANCE.md) | Static performance analysis, proposals B1–B12/F1–F7, benchmark plan |

## Report

| English | Vietnamese original | Contents |
|---|---|---|
| [`en/BAO-CAO-SUMMARY.md`](BAO-CAO-SUMMARY.md) | [`../BAO-CAO.md`](../BAO-CAO.md) | **Condensed English summary, not a full translation** — 4-chapter structure, conclusions, limitations and key figures; the Vietnamese report is authoritative |

## Language & navigation

- UI language switch: **VI | EN** button in the dashboard header; preference stored in `localStorage` as `aegis.lang` — see [`docs/I18N.md`](../I18N.md).
- Vietnamese-only material without an English parallel: `docs/DEMO-VIDEO-SCRIPT.md`, `docs/reviews/**`, `docs/evidence/**`, `Agents.md`, `aegis-network-lab/src/main/resources/lab-guide.md`.
- Every English file starts with `Language: English | Vietnamese original: <path>`; when a Vietnamese source changes, update its English parallel together.
