#!/bin/bash
set -e
cd /codes/algotrade/zerodha-algo-trading
PROPS=src/main/resources/application.properties
LOG=/codes/algotrade/zerodha-algo-trading/logs/overnight_sim.log

get_jwt() {
  curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"anand","password":"protrader"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])"
}

wait_for_app() {
  for i in $(seq 1 60); do
    sleep 5
    if curl -s http://localhost:8080/api/auth/login -X POST -H "Content-Type: application/json" -d '{"username":"anand","password":"protrader"}' 2>/dev/null | grep -q token; then
      echo "$(date) App ready" >> $LOG
      return 0
    fi
  done
  echo "$(date) App failed to start!" >> $LOG
  return 1
}

run_sim() {
  THR=$1
  SYMBOLS=$2
  LABEL=$3

  echo "" >> $LOG
  echo "$(date) === Threshold $THR — $LABEL ===" >> $LOG

  sed -i "s/impulse.threshold=.*/impulse.threshold=$THR/" $PROPS
  kill $(lsof -t -i:8080) 2>/dev/null || true
  sleep 5
  nohup ./gradlew bootRun > logs/consoleoutput.log 2>&1 &
  wait_for_app

  JWT=$(get_jwt)
  mysql -u anand -ppassword algotrading -e "SET FOREIGN_KEY_CHECKS=0; TRUNCATE TABLE trade_action_log; TRUNCATE TABLE trade_order; TRUNCATE TABLE trade_signal; SET FOREIGN_KEY_CHECKS=1;" 2>/dev/null

  echo "$(date) Running..." >> $LOG
  if [ -z "$SYMBOLS" ]; then
    RESULT=$(curl -s -X POST "http://localhost:8080/api/simulation/run?strategy=IMPULSE&timeframe=FifteenMinute&from=2026-01-01T00:00:00Z&to=2026-03-01T00:00:00Z&stepMinutes=15" \
      -H "Authorization: Bearer $JWT" --max-time 72000 2>&1)
  else
    RESULT=$(curl -s -X POST "http://localhost:8080/api/simulation/run?strategy=IMPULSE&timeframe=FifteenMinute&from=2026-01-01T00:00:00Z&to=2026-03-01T00:00:00Z&stepMinutes=15&symbols=$SYMBOLS" \
      -H "Authorization: Bearer $JWT" --max-time 72000 2>&1)
  fi

  echo "$(date) Done: $RESULT" >> $LOG

  TABLE=$(echo "${THR}_${LABEL}" | tr '.' '_' | tr '-' '_')
  mysql -u anand -ppassword algotrading -e "DROP TABLE IF EXISTS sim_sig_${TABLE}; CREATE TABLE sim_sig_${TABLE} AS SELECT * FROM trade_signal; DROP TABLE IF EXISTS sim_act_${TABLE}; CREATE TABLE sim_act_${TABLE} AS SELECT * FROM trade_action_log;" 2>/dev/null
  echo "$(date) Saved → sim_sig_${TABLE}" >> $LOG
}

echo "$(date) === OVERNIGHT SIM STARTING ===" > $LOG

# Start prediction server
pkill -f "prediction_server" 2>/dev/null || true
sleep 2
nohup python3 /codes/algotrade/zerodha-algo-trading/ds-python/finrl-poc/service/prediction_server.py > /dev/null 2>&1 &
sleep 5

# Get FnO symbols
kill $(lsof -t -i:8080) 2>/dev/null || true
sleep 3
nohup ./gradlew bootRun > logs/consoleoutput.log 2>&1 &
wait_for_app
JWT=$(get_jwt)
FNO=$(curl -s "http://localhost:8080/api/pattern-scan/fno-symbols" -H "Authorization: Bearer $JWT" | python3 -c "import sys,json; print(','.join(s for s in json.load(sys.stdin) if '&' not in s))")
echo "$(date) FnO: $(echo $FNO | tr ',' '\n' | wc -l) stocks" >> $LOG

# Phase 1: Nifty30
for THR in 0.93 0.95 0.97; do
  run_sim $THR "" "nifty30"
done

# Phase 2: Full FnO
for THR in 0.93 0.95 0.97; do
  run_sim $THR "$FNO" "fno249"
done

echo "" >> $LOG
echo "$(date) === ALL 6 RUNS COMPLETE ===" >> $LOG
echo "DONE" >> $LOG
