/**
 * npm ecosystem support for Annatto.
 * Handles .tgz packages containing package/package.json.
 * (tested by {@code NpmMetadataExtractorTest.isPackageJson_*},
 * {@code NpmMetadataExtractorTest.fullExtraction_lodash_fromTgz},
 * {@code AnnattoProcessFilterTest.detectEcosystem_tgz_isNpm})
 *
 * <p>PURL format: {@code pkg:npm/[@scope/]name@version}
 * (tested by {@code PurlBuilderTest.forNpm_scopedPackage},
 * {@code PurlBuilderTest.forNpm_unscopedPackage})</p>
 */
package io.spicelabs.annatto.npm;
