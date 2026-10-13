#!/bin/sh
# Sequential runtime acceptance for the modern recipes in a combined ALL51 image.
set -eu
: "${NEXUS_URL:?Set NEXUS_URL to the disposable ALL51 Nexus}"
: "${NEXUS_CONTAINER:?Set NEXUS_CONTAINER to the disposable ALL51 container}"
MODERN_TEST_WORK=${MODERN_TEST_WORK:-build/modern-final-work}
MODERN_LOG_DIR=${MODERN_LOG_DIR:-build/logs/modern-final}
HF_PYTHON=${HF_PYTHON:-build/hf-client-venv/bin/python}
export NEXUS_URL NEXUS_CONTAINER MODERN_TEST_WORK
mkdir -p "$MODERN_LOG_DIR"
# This fixture is owned by this test suite; never restart an unrelated Nexus installation.
docker restart nexus-modern-upstream > "$MODERN_LOG_DIR/upstream-restart.log"
python3 scripts/test-modern-protocols.py > "$MODERN_LOG_DIR/protocol14-components.log" 2>&1
python3 scripts/test-modern-native.py > "$MODERN_LOG_DIR/native6.log" 2>&1
"$HF_PYTHON" scripts/test-modern-huggingface.py --state "$MODERN_TEST_WORK/state.json" > "$MODERN_LOG_DIR/hf-paginated.log" 2>&1
"$HF_PYTHON" scripts/test-modern-huggingface.py --state "$MODERN_TEST_WORK/state.json" --private-bearer > "$MODERN_LOG_DIR/hf-private-bearer.log" 2>&1
"$HF_PYTHON" scripts/test-modern-huggingface.py --state "$MODERN_TEST_WORK/state.json" --public > "$MODERN_LOG_DIR/hf-public.log" 2>&1
python3 scripts/test-modern-private-proxy.py --state "$MODERN_TEST_WORK/state.json" > "$MODERN_LOG_DIR/private-basic-isolation.log" 2>&1
echo "PASS modern ALL51 acceptance: 14 recipes, Components, six native clients, three HF SDK modes and private upstream isolation"
