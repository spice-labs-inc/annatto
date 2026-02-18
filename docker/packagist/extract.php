<?php
// Extracts metadata from a Packagist .zip containing composer.json
$zipFile = $argv[1];
$zip = new ZipArchive();
$zip->open($zipFile);
for ($i = 0; $i < $zip->numFiles; $i++) {
    $name = $zip->getNameIndex($i);
    if (basename($name) === 'composer.json') {
        $json = json_decode($zip->getFromIndex($i), true);
        echo json_encode($json, JSON_PRETTY_PRINT) . "\n";
        break;
    }
}
$zip->close();
