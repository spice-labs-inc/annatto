#!/usr/bin/env bash
# Downloads .podspec.json files from CocoaPods Trunk API.
# Usage: ./download.sh <output_dir>
#
# Each package is specified as NAME/VERSION in the PACKAGES array below.
# The script fetches from: https://trunk.cocoapods.org/api/v1/pods/<Name>/specs/<version>
set -euo pipefail

OUTDIR="${1:?Usage: $0 <output_dir>}"
mkdir -p "$OUTDIR"

PACKAGES=(
    # Zero deps (~20)
    "Alamofire/5.8.1"
    "SnapKit/5.7.1"
    "Masonry/1.1.0"
    "SwiftyJSON/5.0.2"
    "ObjectMapper/4.2.0"
    "MBProgressHUD/1.2.0"
    "SVProgressHUD/2.3.1"
    "IQKeyboardManager/7.0.3"
    "Charts/5.1.0"
    "lottie-ios/4.4.1"
    "KeychainAccess/4.2.2"
    "CryptoSwift/1.8.1"
    "Hero/1.6.3"
    "FloatingPanel/2.8.2"
    "Toast-Swift/5.1.1"
    "NVActivityIndicatorView/5.2.0"
    "SwiftyStoreKit/0.16.1"
    "Starscream/4.0.6"
    "PhoneNumberKit/3.7.11"
    "ReactiveSwift/7.1.1"

    # Simple deps (~10)
    "RxCocoa/6.7.1"
    "CocoaLumberjack/3.8.5"
    "Sentry/8.20.0"
    "Kingfisher/7.11.0"
    "SwiftProtobuf/1.25.2"
    "Nimble/13.2.1"
    "Quick/7.4.0"
    "Amplitude-iOS/8.18.2"
    "ReachabilitySwift/5.2.3"
    "PromisesObjC/2.4.0"

    # Subspec deps (~15)
    "Moya/15.0.3"
    "AFNetworking/4.0.1"
    "SDWebImage/5.19.1"
    "RxSwift/6.7.1"
    "PromiseKit/8.1.2"
    "Realm/10.49.1"
    "GoogleUtilities/7.13.0"
    "Texture/3.2.0"
    "OHHTTPStubs/9.1.0"
    "AppCenter/5.0.4"
    "GTMSessionFetcher/3.3.2"
    "nanopb/2.30910.0"
    "gRPC-Core/1.62.2"
    "leveldb-library/1.22.4"
    "BoringSSL-GRPC/0.0.32"

    # Edge cases (~5)
    "Reachability/3.2"
    "SwiftLint/0.54.0"
    "Firebase/10.22.0"
    "FBSDKCoreKit/17.0.1"
    "abseil/1.20240116.1"
)

echo "Downloading ${#PACKAGES[@]} CocoaPods .podspec.json files..."

for entry in "${PACKAGES[@]}"; do
    name="${entry%%/*}"
    version="${entry##*/}"
    outfile="${OUTDIR}/${name}-${version}.podspec.json"

    if [[ -f "$outfile" ]]; then
        echo "  [skip] $name@$version (already exists)"
        continue
    fi

    url="https://trunk.cocoapods.org/api/v1/pods/${name}/specs/${version}"
    echo "  [fetch] $name@$version from $url"
    if curl -fsSL "$url" -o "$outfile" 2>/dev/null; then
        echo "    -> OK ($(wc -c < "$outfile") bytes)"
    else
        echo "    -> FAILED, trying CDN..."
        # Fallback: CocoaPods CDN Specs repo
        cdn_url="https://cdn.cocoapods.org/Specs/${name}/${version}/${name}.podspec.json"
        if curl -fsSL "$cdn_url" -o "$outfile" 2>/dev/null; then
            echo "    -> CDN OK ($(wc -c < "$outfile") bytes)"
        else
            echo "    -> BOTH FAILED for $name@$version"
            rm -f "$outfile"
        fi
    fi
done

echo "Download complete. Files in $OUTDIR:"
ls "$OUTDIR"/*.podspec.json 2>/dev/null | wc -l
