#!/usr/bin/env php
<?php
/**
 * Extracts metadata from a Packagist .zip containing composer.json.
 * Outputs Annatto source-of-truth JSON schema.
 *
 * Usage: php extract.php <zipfile> [<zipfile> ...]
 *
 * Platform dependencies (php, ext-*, lib-*, composer-plugin-api) are excluded
 * from the dependencies list.
 */

if ($argc < 2) {
    fwrite(STDERR, "Usage: php extract.php <zipfile> [<zipfile> ...]\n");
    exit(1);
}

for ($argIdx = 1; $argIdx < $argc; $argIdx++) {
    $zipFile = $argv[$argIdx];
    $baseName = basename($zipFile, '.zip');
    $outFile = "/work/out/{$baseName}-expected.json";

    $zip = new ZipArchive();
    $res = $zip->open($zipFile);
    if ($res !== true) {
        fwrite(STDERR, "ERROR: Cannot open $zipFile\n");
        continue;
    }

    $composerJson = null;
    for ($i = 0; $i < $zip->numFiles; $i++) {
        $name = $zip->getNameIndex($i);
        if (basename($name) === 'composer.json') {
            // Accept root or one-level-deep composer.json
            $depth = substr_count(trim($name, '/'), '/');
            if ($depth <= 1) {
                $composerJson = json_decode($zip->getFromIndex($i), true);
                break;
            }
        }
    }
    $zip->close();

    if ($composerJson === null) {
        fwrite(STDERR, "ERROR: No composer.json found in $zipFile\n");
        continue;
    }

    // Extract fields
    $fullName = $composerJson['name'] ?? null;
    $simpleName = null;
    if ($fullName !== null) {
        $parts = explode('/', $fullName);
        $simpleName = count($parts) > 1 ? $parts[count($parts) - 1] : $fullName;
    }

    $version = $composerJson['version'] ?? null;
    // If no version in composer.json, extract from filename (e.g., name-v1.0.0.zip or name-1.0.0.zip)
    if ($version === null) {
        $version = extractVersionFromFilename($baseName);
    }
    $description = $composerJson['description'] ?? null;

    // License: string or array, joined with " OR "
    $license = null;
    if (isset($composerJson['license'])) {
        if (is_array($composerJson['license'])) {
            $license = count($composerJson['license']) > 0
                ? implode(' OR ', $composerJson['license'])
                : null;
        } else {
            $license = $composerJson['license'];
        }
    }

    // Publisher: first author's name
    $publisher = null;
    if (isset($composerJson['authors']) && is_array($composerJson['authors']) && count($composerJson['authors']) > 0) {
        $publisher = $composerJson['authors'][0]['name'] ?? null;
    }

    // Dependencies: require (runtime) + require-dev (dev), platform deps excluded
    $dependencies = [];

    $requireSections = [
        'require' => 'runtime',
        'require-dev' => 'dev',
    ];

    foreach ($requireSections as $section => $scope) {
        if (!isset($composerJson[$section]) || !is_array($composerJson[$section])) {
            continue;
        }
        foreach ($composerJson[$section] as $depName => $constraint) {
            // Filter platform dependencies
            if (isPlatformDependency($depName)) {
                continue;
            }
            $dependencies[] = [
                'name' => $depName,
                'versionConstraint' => $constraint,
                'scope' => $scope,
            ];
        }
    }

    $result = [
        'name' => $fullName,
        'simpleName' => $simpleName,
        'version' => $version,
        'description' => $description,
        'license' => $license,
        'publisher' => $publisher,
        'dependencies' => $dependencies,
    ];

    $json = json_encode($result, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES) . "\n";
    file_put_contents($outFile, $json);
    echo "OK $outFile\n";
}

/**
 * Extract version from filename when not present in composer.json.
 * Packagist filenames typically include version: vendor-name-v1.0.0.zip
 */
function extractVersionFromFilename(string $baseName): ?string {
    // Match version pattern at end of filename: -v1.0.0 or -1.0.0
    // Version starts with digit after a dash
    if (preg_match('/-v?(\d+\.\d+(?:\.\d+)?)$/', $baseName, $matches)) {
        return $matches[1];
    }
    return null;
}

/**
 * Returns true if the dependency name is a platform dependency
 * (php, ext-*, lib-*, composer-plugin-api).
 */
function isPlatformDependency(string $name): bool {
    $lower = strtolower($name);
    return $lower === 'php'
        || $lower === 'php-64bit'
        || $lower === 'hhvm'
        || str_starts_with($lower, 'ext-')
        || str_starts_with($lower, 'lib-')
        || $lower === 'composer-plugin-api'
        || $lower === 'composer-runtime-api'
        || $lower === 'composer';
}
