#!/usr/bin/env bash

set -euo pipefail

cd "$(dirname "$0")/.."

product_pattern='r[i]de[_ -]?arrow|j[s]oft|sk/j[s]oft'
secret_pattern='AIza[0-9A-Za-z_-]{35}|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|firebaseio\.com|r2\.dev/regions/'

if git grep -I -n -i -E "$product_pattern" -- .; then
    echo "Product-specific identifiers are not allowed in this generic library."
    exit 1
fi

if git grep -I -n -E "$secret_pattern" -- .; then
    echo "A credential or product-specific service endpoint may have been committed."
    exit 1
fi

blocked_files=$(git ls-files | grep -E '(^|/)(google-services\.json|GoogleService-Info\.plist|[^/]+\.(jks|keystore|p12|mobileprovision))$' || true)
if [[ -n "$blocked_files" ]]; then
    echo "Private application configuration files are not allowed:"
    echo "$blocked_files"
    exit 1
fi

if ! git -C src/valhalla diff --quiet; then
    echo "The Valhalla submodule must be clean; carry local changes as reviewed patch files."
    exit 1
fi

models_version=$(sed -n 's/^valhallaModels = "\([^"]*\)"$/\1/p' android/gradle/libs.versions.toml)
if [[ -z "$models_version" ]]; then
    echo "Unable to resolve the Android Valhalla models version."
    exit 1
fi
for artifact in valhalla-models valhalla-models-config; do
    if ! grep -F "io.github.rallista:$artifact:$models_version" README.md >/dev/null; then
        echo "README Android dependency $artifact must match version $models_version."
        exit 1
    fi
done

for patch_file in patches/*.patch; do
    git -C src/valhalla apply --check "$(pwd)/$patch_file"
done

echo "Genericity and configuration-leak checks passed."
