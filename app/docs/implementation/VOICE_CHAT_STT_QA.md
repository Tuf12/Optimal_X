# Voice, STT & Chat — manual QA matrix

**Plan:** [VOICE_CHAT_STT_COMPLETION_PLAN.md](VOICE_CHAT_STT_COMPLETION_PLAN.md)  
**Run when:** after Phases 1–6; re-run after Phase 9 regression pass.

Record: device model, build, date, tester. Log surface, STT engine (Whisper vs Google), and conversation id when debugging voice.

---

## Directory & scope (Phases 3–6)

| # | Steps | Expected | Pass |
|---|--------|----------|------|
| M1 | Parent A Chats → open conv → Move Here from Parent B | Conv in B list only; absent from A after back | ☐ |
| M2 | Subfolder X → Move Here to General | Gone from X list | ☐ |
| M3 | Eidos from DumpEdit → send → back/rotate | Same DumpEdit thread, not general | ☐ |
| M4 | Widget Chat → rotate | Messages + scroll OK; voice survives rotate if mic was open | ☐ |
| M5 | Read aloud on in widget chat; home widget Quick Ask | Widget loop independent unless **Widget hands-free** on | ☐ |
| M6 | Web tab → Eidos sheet → rotate | Sheet state recoverable | ☐ |

---

## Voice & read-aloud (Phase 1)

| # | Steps | Expected | Pass |
|---|--------|----------|------|
| V1 | Read aloud **on**, mic pass-back **on** → assistant replies | TTS → mic opens → speak → **Send** transcribes + sends | ☐ |
| V2 | Read aloud **on**, mic pass-back **off** → assistant replies | TTS only; mic does **not** open | ☐ |
| V3 | Read aloud **off** | No auto TTS; pass-back control hidden/disabled | ☐ |
| V4 | Widget hands-free **on**, mic pass-back **off**, Quick Ask | TTS may play; mic does **not** auto-reopen | ☐ |
| V5 | Widget hands-free **off**, Quick Ask | No post-reply TTS loop on home widget | ☐ |
| M7 | Same as V2 | TTS only, no mic reopen | ☐ |
| M8 | Read aloud + pass-back on, Whisper mic | TTS → mic → speak → **Send** → message sent | ☐ |

---

## STT (Phase 2)

| # | Steps | Expected | Pass |
|---|--------|----------|------|
| S1 | Chat mic, Whisper on, airplane mode → commit | Snackbar; Google retry or clear error message | ☐ |
| S2 | Chat mic, Whisper on, valid key | Record → Transcribing → input filled | ☐ |
| M9 | Same as S1 | Error shown; Google fallback or clear message | ☐ |
| M10 | WebPanel + widget web search mic | Google inline STT only; never Whisper | ☐ |

---

## Widget Quick Ask / Chat (baseline)

| # | Steps | Expected | Pass |
|---|--------|----------|------|
| W1 | Widget Ask Eidos → Send | Google STT; reply saved to Quick Ask log; **Chat** opens separate thread | ☐ |
| W2 | Widget Quick Notes | Day-folder behavior unchanged | ☐ |
| W3 | Widget Chat mic, Whisper on + key | Record → transcribe → input; messages only from in-activity actions | ☐ |
| W4 | Chat mic, Whisper off | Google partials; async commit on Send | ☐ |
| W5 | Notes editor mic | Appends dictated text to note body | ☐ |

---

## Sign-off

| Phase complete | Date | Tester | Notes |
|----------------|------|--------|-------|
| Phase 1 | | | |
| Phases 3–6 | | | |
| Phase 9 (full matrix) | | | |
