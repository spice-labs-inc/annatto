# ADR-007 — Budget Policy & Checked Exception Boundary (Fresh Scent)

**Status:** Accepted (2026-08-28, Fresh Scent Phases 1–3; updated 2026-08-28: public exceptions
converted to checked)

## Human-readable summary

- **Budget policy (catalog §0):** no single internal read materializes more than a capped
  metadata member (10 MiB); entry content streams lazily (`EntryContentStream`); router
  scans are bounded (compressed 1 GiB / inflated 500 MiB / 1M entries). Lua/Erlang/JSON
  parsers are depth/element/length capped (catalog §8 analog).
- **Checked exception boundary (catalog §7):** ALL corruption/limit exceptions are now
  CHECKED. `MalformedPackageException` and `SecurityException` extend
  `java.io.IOException` (so existing `throws IOException` contracts cover them);
  internal parser exceptions (`LuaParseException`, `LuaLimitException`,
  `ErlangTermException`) are checked; limit violations propagate loudly through
  `LuaRockspecEvaluator` (skip-with-progress only for genuinely unsupported constructs).
  No unchecked corruption type escapes any public method.

## LLM-friendly section

- Decision (updated): user instruction 2026-08-28 — "Convert the Annatto exceptions to
  checked" — implemented across 253 main + 67 test usage sites; methods already declaring
  `IOException` needed no signature change; private helpers (`checkBudget`, `checkLimit`,
  `account`, `failExceeded`, `checkDepth`, `validateEntryName`, tokenizer/parser methods)
  now declare throws.
- Rationale: catalog §7; unchecked exceptions are a code smell (user feedback).
- Tests pinning: `LuaTableBuilderSecurityBombTest`, `LuaRockspecEvaluatorSecurityBombTest`,
  `LuaTokenSoupPropertyBombTest`, `FreshScentEntryStreamTest`, `EntryContentStreamTest`,
  `FreshScentRouterTest`, `FreshScentJsonDepthTest`, `FreshScentExtractorCapTest`,
  `BoundedInputStreamContractTest`, `ErlangTermTokenizerSecurityTest`,
  `RouterFuzzBombTest`, plus the full suite (6978 tests, 0 failures/errors/skips).
- No re-check date needed: the flagged follow-up is now DONE.
