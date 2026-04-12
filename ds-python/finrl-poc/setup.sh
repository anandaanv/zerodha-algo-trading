#!/bin/bash
# One-time setup for FinRL POC
# Run from ds-python/finrl-poc/

set -e
cd "$(dirname "$0")"

echo "=== Creating virtual environment ==="
python3 -m venv .venv
source .venv/bin/activate

echo "=== Installing dependencies ==="
pip install --upgrade pip
pip install -r requirements.txt

echo "=== Creating .env ==="
if [ ! -f .env ]; then
    cp .env.example .env
    echo "  → Edit .env if your DB credentials differ"
fi

echo "=== Applying DB schema ==="
mysql -u anand -ppassword finrl_poc < schema.sql && echo "  → finrl_poc schema ready"

echo ""
echo "Done. To start:"
echo "  source .venv/bin/activate"
echo "  python train.py --symbol RELIANCE --steps 100000"
echo "  python backtest.py --symbol RELIANCE --save-db"
