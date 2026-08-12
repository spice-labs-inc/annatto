# Phase 7/8 Claims & Status

Phase: 7a (RED) → 7b (GREEN) → **Phase 8 (GREEN) COMPLETE**.
Full suite: **7939 run, 0 failures, 0 errors**.

## Phase 8 claim → test → status (invariant 12)
| Claim | Test |
|---|---|
| Go/RubyGems/Packagist/CocoaPods/Hex/LuaRocks never read the package whole into memory | `Phase8StreamingTest.wholeFileOversizeFailsClosedPhase8` |
| RubyGems nested `metadata.gz` decompression is bounded | `Phase8StreamingTest.nestedGzipMetadataBounded` |
| `go.mod`/`composer.json` per-entry metadata caps | `Phase8StreamingTest.goModEntryCapped`, `composerJsonEntryCapped` |
| podspec.json / rockspec metadata caps | `Phase8StreamingTest.podspecJsonEntryBounded`, `rockspecEntryBounded` |
| Hex metadata scan entry-count cap; entry-stream honors injected limits | `hexMetadataScanEntryCountBounded`, `hexEntryStreamRespectsInjectedEntryCap` |
| Exactly-at-cap archives iterate to completion | `Phase8StreamingTest.exactEntryCountIterationCompletes` |
| Stream-read handoff (owned spool) works for all 11 ecosystems | contract/integration suites green; `LanguagePackageReaderIntegrationTest` |

## Phase 7/8 claim → test → status (GREEN)

Every claim below has a named test (verified by running `mvn test`). Status: GREEN.

### Misclassification (Bug 1)
| Claim | Test |
|---|---|
| A generic `.tgz` is not routed to NPM | `TgzNotNpmRegressionTest.tgzWithGenericContentIsNotRoutedToNpm` |
| `read(Path)` refuses a generic `.tgz` with `UnknownFormatException` | `TgzNotNpmRegressionTest.readPathWithGenericTgzThrowsUnknownFormat` |
| `.tgz`/-named route is ambiguous; content required | `TgzNotNpmRegressionTest.routeFromFilenameWithTgzMimeIsAmbiguous` |
| `repo_ea`-shaped bundle (nested `node_modules/.../package/package.json`) is not npm | `TgzNotNpmRegressionTest.repoEaLikeTgzNotNpm`, `nestedPackageJsonNotTreatedAsNpmMarker` |
| Directory entries are not markers; nested `PKG-INFO`/`META.yml`/`Cargo.toml` are not markers | `directoryEntryNamedPackageJsonIsNotMarker`, `nestedPkgInfoNotRoutedToPypi`, `nestedMetaYmlNotRoutedToCpan`, `nestedCargoTomlNotRoutedToCrates` |
| Generic `.crate` fails closed (content-required) | `crateGenericContentNotRoutedToCrates` |
| Router NPM classification agrees with strict top-level marker semantics | `markerBoundaryParameterized`, `MarkerAgreementPropertyTest` (deterministic, seeded) |
| Filter does not claim `.tgz` by name | `AnnattoProcessFilterTest.detectEcosystem_tgz_isNotClaimedByName` |
| Valid npm layouts still route NPM; multi-member gzip routing agrees with extraction | `realNpmLayoutStillRoutesToNpm`, `multiMemberGzipRoutingAgreesWithExtraction` |

### Streaming / bomb hardening (Bug 2)
| Claim | Test |
|---|---|
| `fromStream` stops at the spool bound; never buffers the whole package | `NpmMemorySafetyTest.fromStreamNeverBuffersWholePackage`, `largeStreamFailsClosedNoOom` |
| Metadata scan bounded (gzip layer); enumeration bounded per pass (tar skip = decompression) | `metadataExtractionScansBounded`, `enumerationDecompressionBudgetEnforced` |
| Mid-entry budget trip fails fast on subsequent iteration | `openStreamMidEntryBudgetTripHasNextSemantics` |
| Limits are per-instance; failed reader construction never locks a package | `noRegisteredStateLeakBetweenPackages`, `constructorFailureResetsStreamFlag` |
| Aggregate spool budget reserved at admission, fail-closed | `aggregateBudgetAdjoint` |
| PyPI sdist / Crates / CPAN / Conda v1 / Conda v2 metadata decompression bounded | `EcosystemDecompressionBombTest.{sdist,crate,cpan}DecompressionIsBounded`, `condaV1Bzip2DecompressionIsBounded`, `condaV2ZstdDecompressionIsBounded` |
| Oversized whole-file fails closed for all five ecosystems | `wholeFileOversizeFailsClosed` |
| PyPI per-entry metadata caps (wheel METADATA / sdist PKG-INFO) | `wheelMetadataEntryCapped`, `sdistPkgInfoEntryCapped` |
| Metadata-scan entry-count cap (CPU-DoS guard); ZIP per-pass inflated budget | `metadataScanEntryCountBounded`, `streamPassBudgetTripsOnZipEntryStreams` |
| Streamed content round-trips byte-for-byte (guard) | `StreamedContentPropertyTest.streamedEntryContentMatchesDeclaredSize` |
| Reader stream path parses npm/crate/sdist; refuses generic tgz | `readStream_validNpmTgzStillParses`, `readStream_validCrateStillParses`, `readStream_validSdistStillParses`, `readStream_genericTgzNotNpm` |

### S-5 close + security hardening
| Claim | Test |
|---|---|
| `close()` closes the package; `streamEntries()` afterwards throws | `LanguagePackageContractTest.streamEntries_afterCloseThrows` (all 11 contracts), `closeMarksPackageClosedAndDeletesSpool` |
| No temp residue after failures/workloads (private spool dirs included) | `spoolOverrunCleansUpTempFile`, `missingPackageJsonCleansUpTempFile`, `tempResidueNoneOutsidePrivateDir` |
| Budget/malformed messages never leak caller/temp paths (basename-only) | `spoolScanMessagesDoNotLeakPaths` |
| Entry names with CR/LF rejected; backslash-based traversal rejected | `entryNameCarriageReturnRejected`, `backslashTraversalRejected` |
| Unsafe symlink targets refused at `openStream()` | `unsafeSymlinkOpenStreamRejected` |

## Regression guard (must stay green)
`SecurityLimitsTest`, `ThreadSafetyTest`, `StreamingResourceManagementTest`, `MimeTypeFuzzTest`,
`EcosystemRouterDisambiguationTest` (19, incl. renamed `routerDetectsNpmFromTgzContent`), all 11
corpus `*PackageContractTest`, `SourceOfTruthIntegrationTest`, `NpmMetadataExtractorTest` (420),
handlers/extractors, `LanguagePackageReaderIntegrationTest` body.

## Full-suite baselines
- 7a (RED): 7928 run, 50 failures + 1 error = the intended red set, zero stray.
- 7b (GREEN): **7930 run, Failures: 0, Errors: 0** (was 7928; +2 deferred tests).

## Known tempered surfaces / documented discipline
- Per-`openStream()` content is buffered per-entry (≤10 MiB default) - bounded, by design.
- Routing scan has no per-entry size cap (name-only classification; count+inflate caps bound work).
- Callers must close entry streams before `pkg.close()` (zip-backed streams hold their fd).
- `fromPath` reads the caller's file until `close()` (documented lifetime contract).

*Date: 2026-08-11 (7a red verification) / 2026-08-11 (7b green verification)*