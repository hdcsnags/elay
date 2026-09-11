Michael here, via Fable (my concierge agent). You are the FINAL VERIFICATION seat for Gate 0a of ELAY. This is not a blind argument round — you see everything the council produced, and your job is to attack it before it becomes foundation.

CONTEXT. You are in a read-only copy of the project folder. Michael has delegated build decisions to the concierge (2026-09-11): "only stall the build when you think you need me… a DB choice etc, you're good to choose the build." The app deliberately will not ship — this is an orchestration test — but the standard is that the work WOULD be shippable. Read, in order:
1. `ELAY-SPEC.md` — the product brief (§ numbers below refer to it).
2. `council/gate-0a-record/DECISIONS.md` — the eight decisions as adopted, with who decided and why.
3. `council/gate-0a-record/matrix.md`, `sol.md`, `gemini.md` — the argument round (Sol + Gemini, blind) and the concierge's verification notes.
4. `research/toolchain-2026-09.md` and `research/supabase-kt-and-ios-2026-09.md` — dated research; UNVERIFIED flags are real.

THE ASK — adversarial verification, in this order:
1. **Attack each of the eight decisions** in DECISIONS.md. For each: CONFIRM (sound, claims check out against the spec/research files) or CHALLENGE (name the specific claim that is wrong/unsupported, cite the file and line/section that contradicts it, and say what should change). Do not challenge for sport — a challenge must carry evidence or a concrete failure scenario. Michael's two personal rulings (KMP stack; Room 3) are settled unless you find a hard technical contradiction, in which case say so plainly.
2. **Find what the council missed.** The seats agreed on a lot, fast. Name the 3–5 most consequential risks or contradictions NOBODY raised — internal contradictions between the eight decisions, spec requirements (§4 recurrence/DST, §6 offline strategy, §7 schema, §8 security controls, §9 calendar levels, §10 AI gateway) that the chosen architecture makes hard, or Windows/agent-workflow realities the record glosses over. For each: why it bites, in which phase, and the cheapest guardrail.
3. **Stress the Phase 0 exit gate** at the bottom of DECISIONS.md: are these the RIGHT commands for a KMP + CMP + Room 3 + supabase-kt project (module names, task names, pgTAP invocation)? Correct anything wrong; add anything missing that Phase 0 must prove. Note you cannot run Gradle here — this is a review of the commands' shape, not their execution.
4. **Rule on sequencing:** the concierge intends: git init + scaffold (concierge-owned wiring) → CI including the macOS iOS-simulator job → local Supabase (Docker) migrations + RLS pgTAP harness → then Phase 1 coder-seat modules on written contracts. Is anything out of order or missing before coders touch the repo?

Read-only: do not modify any file. 900–1,400 words. Begin with the model you are running as. End with a JSON block between the exact lines BEGIN VERDICT and END VERDICT:
{"decisions":[{"n":1,"verdict":"CONFIRM|CHALLENGE","reason":"..."}],
 "missed":[{"risk":"...","phase":"...","guardrail":"..."}],
 "gate_corrections":["..."],"sequencing":"ok|reorder: ...","confidence":0.0-1.0}
