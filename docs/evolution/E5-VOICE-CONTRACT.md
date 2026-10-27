# E5 — The Arkadia voice contract, as measured

Observation date: 2026-10-27.
Origin: the external product diagnostic requested a written contract mapping TTS, STT,
MIE and N-ATLAS to real endpoints, auth, payloads, limits and errors, with unavailable
capabilities marked unavailable rather than mocked.

Every claim below was measured against the live gateway on the date above. Where a
capability is not available, it is marked **UNAVAILABLE** and no Sonata UI should exist
for it. Nothing here is inferred from documentation.

## 1. Gateway identity and reachability

Base: `https://arkadia-qzu4.onrender.com` (the client default in `BackendConfig`).

`GET /openapi.json` advertises **288 paths**. That number is not a capability count — the
overwhelming majority require authentication and have no published contract. Only the
paths below were verified reachable without credentials.

## 2. Text-to-speech — ACTIVE

### `POST /api/tts`

Request `{text, voice, speed}` → `audio/mpeg`.

Honoured parameters, established acoustically (not from docs):

| Parameter | Status | Evidence |
|---|---|---|
| `text` | Honoured | audio duration scales with length |
| `voice` | Honoured | distinct voices differ in measured f0 |
| `speed` | Honoured | duration changes measurably |
| `pitch` | **Ignored** | no measurable change in f0 |
| `emotion` / SSML | **Ignored** | no measurable change |

Sending SSML does not error; it is silently discarded. A client therefore cannot detect
that its expressiveness request was dropped — which is exactly why Sonata exposes no
pitch or emotion control.

### Limits — two distinct ceilings, easy to conflate

| Ceiling | Value | Where it lives |
|---|---|---|
| Per-request | 5000 chars | `kernel/tts.py::synthesize` does `text.strip()[:5000]` |
| Accepted input | 100 000 chars | Sonata's own `MAX_INPUT_CHARS` |

The server-side 5000-character truncation is **silent** — it returns 200 with audio for
the first 5000 characters. A client that sends more than 5000 characters in one request
loses the remainder with no error. Sonata therefore splits before sending and never
issues a request above `MAX_TTS_CHARS` (5000).

100 000 characters is a Sonata product decision, not a provider limit: past that the
passage is truncated client-side with a visible notice naming the ceiling.

### Latency

Synthesis latency scales with request length: roughly **1.8 s for 340 chars** and
**29 s for 5000 chars**. This is the measured reason long passages felt broken, and the
reason the read is segmented rather than sent whole.

### `GET /api/tts/status`

```json
{"engine":"edge_tts","elevenlabs_active":false,"edge_tts_active":true,
 "ready":true,"default":"aria","key_pool":{"total":0,"available":0,"quota_hit":0},
 "preferred_engine":"edge_tts"}
```

The active engine is **Edge TTS**. `elevenlabs_active` is `false` and the key pool is
empty. Sonata must never imply ElevenLabs or MiniMax support.

### `GET /api/tts/voices` — the advertised set is not the synthesizable set

Seven voices are advertised. Only some synthesise:

| Advertised id | Synthesises? | Note |
|---|---|---|
| `aria` | yes | `en-US-AriaNeural` |
| `jenny` | yes | `en-US-JennyNeural` |
| `sonia` | yes | `en-GB-SoniaNeural` |
| `christopher` | yes | `en-US-ChristopherNeural` |
| `george` | **no** | reproducible 502 "No audio was received" |
| `ryan` | **no** | reproducible 502 "No audio was received" |
| `aetheria` | maps to Aria | duplicate; `requires_elevenlabs: true` |

`aetheria` is the clearest case of label-versus-capability divergence. The gateway
describes it as "Aetheric Oracle — deep emotional resonance, calming, alive" and
advertises a distinct identity, while returning `id: "en-US-AriaNeural"` — the same
underlying voice as `aria`. Independent acoustic measurement found the two identical in
fundamental frequency, duration and byte size. The gateway's own
`requires_elevenlabs: true` field corroborates this: the expressiveness it promises is
gated behind a provider that is not active.

**Consequence:** `BackendConfig.VOICES` offers four voices, each verified to synthesise.
`resolveVoiceId` degrades a stored `george`, `ryan` or `aetheria` preference to a working
voice rather than producing a 502 the user reads as "Sonata is broken".

## 3. Speech-to-text — UNAVAILABLE

`/api/stt` and `/api/transcribe` → **404**. No STT route exists in the published API.

There is no transcription capability to integrate. Sonata must not present a
transcription UI. Microphone capture without a transcription contract produces audio the
app cannot use, which is worse than not offering it.

## 4. The Solspire voice-event lifecycle — EXISTS BUT UNAUTHENTICATED

The gateway publishes a complete voice-event lifecycle:

```
/solspire/voice/events/{id}/understand → propose → decision → revise
                                     → authorize → execute → verify
```

plus `/solspire/voice/events/{id}/chain` and `/audio`.

Every route returns **401 `{"detail":"Authentication required"}`** without credentials,
including `/solspire/voice/status`, `/solspire/voice/providers` and the
`/api/lab/engineering/voice` family.

This is architecturally significant and directly relevant to the diagnostic's Layer 4.
The lifecycle it describes is the *correct* shape for a voice agent: `understand` and
`propose` are separated from `authorize` and `execute`, and `verify` is distinct from
`execute`. That separation is precisely the distinction the diagnostic insists on — that
a response being spoken is not evidence an action occurred.

**But it is not usable by Sonata today**, and the reason is not the 401. Even with a
credential, Sonata has no contract: no request schema, no response schema, no error
taxonomy, no permission model, no defined behaviour when `authorize` is withheld. A
credential alone would not make this integrable.

**Do not build against it.** Any UI here would be a mock wearing a real endpoint's name.

## 5. MIE — IMPLEMENTED, BUT IN A DIFFERENT REPOSITORY

This is the finding with the largest consequences, and it is a repository problem rather
than an API problem. See `E6-CANONICAL-SOURCE.md`.

The Musical Intention Engine exists as working Kotlin in
`Arkadia/sonata-android`: `MieAudioRecorder`, `MieAudioTransformer`,
`MieMusicalInterpreter`, `MieMusicalObject`, `MieSessionStore`, plus `MieActivity`,
`OracleFragment`, `KnowledgeActivity`, `TimelineFragment`, `SearchFragment`.

The dedicated `Arkadia-Oversoul-Prism/sonata` repository — the one PR #4 is on —
contains **none of it**. Its 19 main sources are TTS, playback and text handoff only.

So MIE is not "not established as integrated"; it is *implemented in a repository that
stopped receiving commits on 2026-10-04*. Those are different problems with different
fixes: the first would need engineering, the second needs a migration decision.

## 6. What Sonata may expose today

| Capability | Endpoint | Auth | Sonata UI |
|---|---|---|---|
| Read text aloud | `POST /api/tts` | none | yes — shipped |
| Voice catalogue | `GET /api/tts/voices` | none | yes — four verified voices |
| Engine status | `GET /api/tts/status` | none | diagnostics only |
| Speech to text | — | — | **no — no route exists** |
| Voice agent | `/solspire/voice/events/*` | 401 | **no — no contract exists** |
| MIE analysis | — | — | **no — lives in another repository** |

The rule this table enforces: a control exists only where a verified endpoint backs it.
