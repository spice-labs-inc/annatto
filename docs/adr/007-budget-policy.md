# ADR-007 — Budget Policy & Checked Exception Boundary (Fresh Scent)

**Status:** Accepted (2026-08-28, Fresh Scent Phases 1–3)

## Human-readable summary

- **Budget policy (catalog §0):** no single internal read materializes more than a capped
  metadata member (10 MiB); entry content streams lazily (`EntryContentStream`); router
  scans are bounded (compressed 1 GiB / inflated 500 MiB / 1M entries). Lua/Erlang/JSON
  parsers are depth/element/length capped (catalog §8 analog).
- **Checked exception boundary (catalog §7):** internal parser exceptions
  (`LuaParseException`, `LuaLimitException`) are now CHECKED; limit violations propagate
  loudly through `LuaRockspecEvaluator` (skip-with-progress only for genuinely
  unsupported constructs). The PUBLIC `MalformedPackageException`/`SecurityException`
  remain unchecked per D1(i) with javadoc-documented contracts at every entry point
  (converting them to checked is a flagged follow-up, API-breaking).

## LLM-friendly section

- Decision: internal exceptions checked (user feedback: unchecked exceptions are a smell);
  public types documented-unchecked for now; budgets as per `Limits` + `JsonSecurity` +
  `EntryContentStream`.
- Rationale: catalog §0/§2/§7/§8; red-team findings A1/A7/A8/A9.
- Tests pinning: `LuaTableBuilderSecurityBombTest`, `LuaRockspecEvaluatorSecurityBombTest`,
  `LuaTokenSoupPropertyBombTest`, `FreshScentEntryStreamTest`, `EntryContentStreamTest`,
  `FreshScentRouterTest`, `FreshScentJsonDepthTest`, `FreshScentExtractorCapTest`,
  `BoundedInputStreamContractTest`, `ErlangTermTokenizerSecurityTest`,
  `RouterFuzzBombTest`.
- Re-check date for the public-type conversion decision: next API-breaking release window.
