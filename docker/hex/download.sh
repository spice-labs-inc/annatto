#!/usr/bin/env bash
# Downloads Hex packages from repo.hex.pm
# Usage: download.sh <output_dir>
set -euo pipefail

OUT="${1:-.}"
mkdir -p "$OUT"

# 50 real Hex packages: name version
# Frameworks
PACKAGES=(
  "phoenix 1.7.10"
  "ecto 3.11.1"
  "plug 1.15.3"
  "absinthe 1.7.6"
  "broadway 1.0.7"
  "oban 2.17.4"
  "ash 2.21.5"
  "nx 0.7.1"
  "livebook 0.12.1"
  "nerves 1.10.5"
  # Utilities
  "jason 1.4.1"
  "httpoison 2.2.1"
  "tesla 1.8.0"
  "req 0.4.8"
  "finch 0.18.0"
  "decimal 2.1.1"
  "timex 3.7.11"
  "nimble_csv 1.2.0"
  "nimble_parsec 1.4.0"
  "uuid 1.1.8"
  # Security
  "comeonin 5.4.0"
  "bcrypt_elixir 3.1.0"
  "guardian 2.3.2"
  "plug_crypto 2.0.0"
  "jose 1.11.9"
  # Testing
  "ex_machina 2.7.0"
  "mox 1.1.0"
  "credo 1.7.3"
  "dialyxir 1.4.3"
  "ex_doc 0.31.1"
  # Erlang
  "cowboy 2.12.0"
  "ranch 2.1.0"
  "hackney 1.20.1"
  "jsx 3.1.0"
  "lager 3.9.2"
  # Edge cases
  "mime 2.0.5"
  "db_connection 2.6.0"
  "telemetry 1.2.1"
  "telemetry_metrics 0.6.2"
  "telemetry_poller 1.0.0"
  "phoenix_html 3.3.3"
  "phoenix_live_view 0.20.4"
  "floki 0.35.2"
  "earmark_parser 1.4.39"
  "makeup 1.1.1"
  # Special
  "swoosh 1.14.4"
  "gettext 0.24.0"
  "ecto_sql 3.11.1"
  "postgrex 0.17.4"
  "poison 5.0.0"
)

for entry in "${PACKAGES[@]}"; do
  read -r name version <<< "$entry"
  filename="${name}-${version}.tar"
  if [ -f "${OUT}/${filename}" ]; then
    echo "SKIP ${filename} (already exists)"
    continue
  fi
  url="https://repo.hex.pm/tarballs/${filename}"
  echo "Downloading ${filename}..."
  if curl -fsSL -o "${OUT}/${filename}" "$url"; then
    echo "  OK: ${filename}"
  else
    echo "  FAIL: ${filename} from ${url}"
    rm -f "${OUT}/${filename}"
  fi
done

echo ""
echo "Downloaded $(ls -1 "$OUT"/*.tar 2>/dev/null | wc -l) packages"
