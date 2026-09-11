---
name: council
description: Dispatch the model council (Sol, Astra, Gemini, Kimi, Fable 5, Sonnet) through MaestroClaw for a read-only design/architecture study or a write-capable coding round, using the generic council-dispatch.ts script — brief template, env, seats, verification, matrix.
---

# Council dispatch

## The script
`C:\New folder\MaestroOrchestra\project\Maestro\packages\maestroclaw\scripts\council-dispatch.ts`. Run from `packages/maestroclaw` in Git Bash. One process per model env (adapters read `CLAW_*_MODEL` at import), so Sol and Astra run as separate invocations.

```
cd "/c/New folder/MaestroOrchestra/project/Maestro/packages/maestroclaw"
SOURCE="C:\New folder\Elay" NO_GIT=1 PROJECT=elay LABEL=stack \
PROMPT_FILE="C:\New folder\Elay\council\brief-00-stack-decision.md" \
SEATS=sol CLAW_CODEX_MODEL=gpt-5.6-sol PYTHONIOENCODING=utf-8 npx tsx scripts/council-dispatch.ts
```
- `SEATS`: `astra`, `sol` (codex), `gemini`, `gemini36` (agy: `CLAW_AGY_MODEL=gemini-3.8-flash-high` / `gemini-3.6-flash`), `kimi` (`CLAW_KIMI_MODEL=kimi-code/k3`), `fable5`/`sonnet` (claude_code: `CLAW_CLAUDE_MODEL=claude-fable-5` / `claude-sonnet-5`, `CLAUDE_CONFIG_DIR="$HOME\.claude-thamos"`).
- `NO_GIT=1` copies a plain folder; drop it once the repo exists (then `BRANCH=` applies).
- `MODE=code` for coder seats: Codex gets workspace-write, and the script emits `<seat>.patch` from the clone for `git apply --3way`.
- agy receives the prompt as `COUNCIL_PROMPT.md` in its copy (argv limit); keep agy payloads ≤ 18 items.
- Outputs: Maestro `docs/audits/<PROJECT>/<RUN_ID>/<seat>.md` (+ `-verdict.json`, `.patch`).

## Brief template (file under `council/`)
1. Who is asking and why (Michael's words verbatim). 2. What to read (paths, sections). 3. The exact questions, numbered, with word budgets. 4. "Ground claims in the files; read-only; begin with the model you are running as." 5. Output contract: `BEGIN VERDICT` … JSON … `END VERDICT` with named fields. Rubrics yes, personas no ("if you disagree, say so").

## After a run
- Check `read-only verification: clean` in each seat file (or the patch, in code mode).
- Build the matrix: one row per question/item, one column per seat, agreement count. Reuse `C:\New folder\Elianas RhythmMigrate\Michael\votd-cohesion\matrix.py` as the pattern.
- Verify the ≥2-seat items yourself before they become decisions. Grounding predicts quality more than generation (09-06 finding).
- Write `SYNTHESIS.md` beside the outputs; log in `STATE.md`; hand Michael the decision list with defaults.

## Known seat behaviour
Astra: sharpest, expensive, usage windows on non-work accounts. Sol: reliable default. Gemini 3.8: fast, reads images, permissive toward the material under review; 3.6 faster and more permissive. Kimi: monthly quota. Fable 5: best framing, may lack tool access in sandboxes — ask it to mark unverified claims.
