#!/bin/bash
set -e
cd /codes/algotrade/zerodha-algo-trading
LOG=logs/pipeline.log

log() { echo "$(date '+%H:%M:%S') $1" | tee -a $LOG; }

log "=== PIPELINE START ==="

# Get JWT
JWT=$(curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"anand","password":"protrader"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")
log "JWT: ${#JWT} chars"

# Get symbols
ALL=$(curl -s "http://localhost:8080/api/pattern-scan/fno-symbols" -H "Authorization: Bearer $JWT" | python3 -c "import sys,json; syms=[s for s in json.load(sys.stdin) if '&' not in s]; print(','.join(syms))")
log "Symbols: $(echo $ALL | tr ',' '\n' | wc -l)"

# Split into 11 batches
python3 -c "
syms='$ALL'.split(','); n=11; bs=len(syms)//n+1
for i in range(n):
    batch=syms[i*bs:(i+1)*bs]
    if batch: open(f'/tmp/r_{i}.txt','w').write(','.join(batch))
"

# Step 1: Regen CSVs
log "Step 1: Regenerating CSVs..."
START=$(date +%s)
for i in $(seq 0 10); do
  B=$(cat /tmp/r_$i.txt 2>/dev/null)
  if [ -n "$B" ]; then
    curl -s "http://localhost:8080/api/pattern-scan/impulse-train-batch?tf=15m&strategy=historical-raw&symbols=$B" \
      -H "Authorization: Bearer $JWT" --max-time 1200 -o /dev/null &
  fi
done
wait
log "CSVs done in $(($(date +%s)-START))s"

# Step 2: Retrain
log "Step 2: Retraining..."
cd ds-python/finrl-poc
PYTHONPATH=service:$PYTHONPATH python3 service/train_impulse_model.py \
  --strategy historical-raw --tf 15m --test-frac 0.30 --max-bars-to-target 0 \
  --model-out service/model/impulse.pkl 2>&1 | tee -a $LOG | grep -E "Loaded|features|quality|Model saved"
cd /codes/algotrade/zerodha-algo-trading
log "Retrain done"

# Step 3: Restart prediction server
log "Step 3: Restarting prediction server..."
kill $(pgrep -f "prediction_server.py") 2>/dev/null || true
sleep 2
nohup python3 ds-python/finrl-poc/service/prediction_server.py > /dev/null 2>&1 &
sleep 5
curl -s http://localhost:8501/health > /dev/null 2>&1
log "Prediction server restarted"

# Step 4: Simulate
log "Step 4: Running simulation (3 stocks, thr=0.90)..."
JWT=$(curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"anand","password":"protrader"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")
mysql -u anand -ppassword algotrading -e "SET FOREIGN_KEY_CHECKS=0; TRUNCATE TABLE trade_action_log; TRUNCATE TABLE trade_order; TRUNCATE TABLE trade_signal; SET FOREIGN_KEY_CHECKS=1;" 2>/dev/null

START=$(date +%s)
RESULT=$(curl -s -X POST "http://localhost:8080/api/simulation/run?strategy=IMPULSE&timeframe=FifteenMinute&from=2026-01-01T00:00:00Z&to=2026-03-01T00:00:00Z&stepMinutes=15&symbols=RELIANCE,HDFCBANK,MARUTI" \
  -H "Authorization: Bearer $JWT" --max-time 7200 2>&1)
log "Simulation result: $RESULT"
log "Sim time: $(($(date +%s)-START))s"

log "=== PIPELINE COMPLETE ==="
