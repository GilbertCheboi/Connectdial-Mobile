#!/usr/bin/env bash

set -euo pipefail

project_root=$(cd "$(dirname "$0")" && pwd)

sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [[ -z "$sdk_root" ]]; then
    echo "ANDROID_SDK_ROOT or ANDROID_HOME must be set" >&2
    exit 2
fi

aapt2_path=$(find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 -type f -name aapt2 -print | sort -V | tail -n 1)
if [[ -z "$aapt2_path" ]]; then
    echo "aapt2 not found under $sdk_root/build-tools" >&2
    exit 2
fi

android_jar=$(find "$sdk_root/platforms" -mindepth 2 -maxdepth 2 -type f -name android.jar -print | sort -V | tail -n 1)
if [[ -z "$android_jar" ]]; then
    echo "android.jar not found under $sdk_root/platforms" >&2
    exit 2
fi

assert_contains() {
    local description=$1
    local haystack=$2
    local needle=$3

    if [[ "$haystack" != *"$needle"* ]]; then
        echo "FAIL: $description (expected '$needle')" >&2
        exit 1
    fi
}

inspect_branding() {
    local apk_path=$1

    local badging
    local resources

    badging=$($aapt2_path dump badging "$apk_path")
    assert_contains "application label" "$badging" "application-label:'Connectdial'"

    resources=$($aapt2_path dump resources "$apk_path" | tr '[:upper:]' '[:lower:]')
    assert_contains "primary brand color" "$resources" "color/connectdial_primary"
    assert_contains "primary brand color value" "$resources" "#ffdaa520"
    assert_contains "dark brand color" "$resources" "color/connectdial_dark"
    assert_contains "dark brand color value" "$resources" "#ff2e2e2e"
    assert_contains "packaged Connectdial logo" "$resources" "drawable/connectdial_logo"
    assert_contains "packaged padded splash icon" "$resources" "drawable/connectdial_splash_icon"
}

if [[ $# -eq 1 ]]; then
    apk_path=$1
    if [[ ! -f "$apk_path" ]]; then
        echo "APK not found: $apk_path" >&2
        exit 2
    fi

    inspect_branding "$apk_path"

    archive_listing=$(unzip -Z1 "$apk_path")
    assert_contains "launcher foreground asset" "$archive_listing" "res/mipmap-xxxhdpi-v4/connectdial_launcher_icon_foreground.png"

    echo "PASS: Connectdial branding is present in $(basename "$apk_path")"
    exit 0
fi

if [[ $# -ne 0 ]]; then
    echo "Usage: $0 [path/to/app.apk]" >&2
    exit 2
fi

test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT

mkdir -p "$test_dir/res/values" "$test_dir/res/drawable-nodpi"
cp "$project_root/app/src/main/res/values/strings.xml" "$test_dir/res/values/strings.xml"
cp "$project_root/app/src/main/res/values/colors.xml" "$test_dir/res/values/colors.xml"
if [[ -f "$project_root/app/src/main/res/drawable-nodpi/connectdial_logo.png" ]]; then
    cp "$project_root/app/src/main/res/drawable-nodpi/connectdial_logo.png" "$test_dir/res/drawable-nodpi/connectdial_logo.png"
fi
if [[ -f "$project_root/app/src/main/res/drawable-nodpi/connectdial_splash_icon.png" ]]; then
    cp "$project_root/app/src/main/res/drawable-nodpi/connectdial_splash_icon.png" "$test_dir/res/drawable-nodpi/connectdial_splash_icon.png"
fi

printf '%s\n' \
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.connectdial.brandingtest">' \
    '    <application android:label="@string/app_name" />' \
    '</manifest>' > "$test_dir/AndroidManifest.xml"

$aapt2_path compile --dir "$test_dir/res" -o "$test_dir/resources.zip"
$aapt2_path link \
    -I "$android_jar" \
    --manifest "$test_dir/AndroidManifest.xml" \
    -o "$test_dir/branding-test.apk" \
    "$test_dir/resources.zip"

inspect_branding "$test_dir/branding-test.apk"

echo "PASS: Connectdial branding resources compile with the requested identity"
