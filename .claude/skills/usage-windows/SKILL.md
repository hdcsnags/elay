---
name: usage-windows
description: What to do when a council or coder seat hits a usage window or quota mid-build — recognise the failure, record the reset, keep working, and schedule a wake-up (ScheduleWakeup in /loop, or CronCreate) to re-dispatch the same brief file.
---

# Usage windows and quotas

## Recognise
- OpenAI (codex): adapter output names a reset time ("usage limit … resets at HH:MM"). Personal/Plus accounts hit this in minutes on Astra; the work account rarely does. `codex login status` says only "Logged in using ChatGPT" either way.
- Kimi: `403 access_terminated` = monthly quota; nothing to wait for this month.
- agy: "timeout waiting for response" at ~5 min = payload too large; split, don't wait.
- Claude Code adapter: set `CLAW_CLAUDE_FALLBACK_MODEL` so a rate limit falls to a smaller model instead of failing.

## Respond
1. Write the reset time and the brief file path into `STATE.md` and the run folder (`NOTE-window.md`).
2. Continue: your own build, the emulator pass, another seat that has budget — but never spend Astra to cover Sol.
3. Schedule the retry. In a `/loop` you have `ScheduleWakeup(delaySeconds, prompt, reason)` — pass the same instruction ("re-dispatch council/brief-XX.md with SEATS=sol") and a delay to the reset time (clamped to 1 h; chain wake-ups if longer). Outside a loop, `CronCreate` a one-shot. Say in `reason` what you are waiting for ("Sol window resets 19:49").
4. On wake: re-run the SAME `PROMPT_FILE` with the same `RUN_ID` so outputs land beside the partial ones; note "rerun after window" in the seat file header.

Prompts live in files precisely so nothing is lost across a window.
