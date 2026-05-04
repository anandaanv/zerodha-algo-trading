# Candidate-Pivot Strategy — EC2 Deploy Checklist

Branch: `feature/candidate-pivot-training`

PRs:
- Parent (`zerodha-algo-trading`): https://github.com/anandaanv/zerodha-algo-trading/pull/146
- Submodule (`algo-strategies-private`): https://github.com/anandaanv/algo-strategies-private/pull/2

Validated on 1y holdout (FnO 250):
- 38-bar timeout (CASH/FUT): 1,407 trades, **WR 76.9%, +2,496%, PF 3.47**
- 15-bar timeout (OPTIONS): 1,359 trades, **WR 76.4%, +1,853%, PF 3.79**

---

## 1. Merge PRs (in order)

```bash
# Submodule first — parent PR depends on the new submodule pointer
gh pr merge 2 -R anandaanv/algo-strategies-private --merge
gh pr merge 146 -R anandaanv/zerodha-algo-trading --merge
```

---

## 2. SSH to EC2

```bash
ssh ec2-prod
cd /opt/zerodha-algo-trading   # adjust if path differs
```

---

## 3. Pull latest code

```bash
git fetch origin
git checkout master
git pull origin master
git submodule update --init --recursive
```

Verify the new strategy files are present:

```bash
ls strategies/impulse/src/main/java/com/dtech/kitecon/elliott/CandidatePivotSignalScanner.java
ls strategies/impulse/src/main/java/com/dtech/kitecon/simulation/strategy/CandidatePivotSimulationStrategy.java
```

---

## 4. Transfer the new ML model file

The model is **gitignored** (binary). Copy from local:

```bash
# From your laptop:
scp deploy_artifacts/impulse_395feat.pkl ec2-prod:/opt/zerodha-algo-trading/ds-python/finrl-poc/service/model/impulse.pkl
scp deploy_artifacts/impulse_feature_cols.json ec2-prod:/opt/zerodha-algo-trading/ds-python/finrl-poc/service/model/impulse_feature_cols.json
```

On EC2, verify:

```bash
ls -la /opt/zerodha-algo-trading/ds-python/finrl-poc/service/model/impulse.pkl
# Should show ~1.7MB
.venv/bin/python -c "import joblib; m = joblib.load('/opt/zerodha-algo-trading/ds-python/finrl-poc/service/model/impulse.pkl'); print('features:', m.n_features_in_)"
# Should print: features: 395
```

---

## 5. Update `application.properties` to enable the new live scanner

Edit `/opt/zerodha-algo-trading/src/main/resources/application.properties` (or wherever prod props live):

```properties
# Enable the new candidate-pivot live scanner
candpivot.live.enabled=true
candpivot.live.maxTradesPerSymbol=2
candpivot.live.tradeType=OPTIONS
candpivot.live.entryValidHours=5

# ML thresholds (per training analysis)
candpivot.thr.bull=0.60
candpivot.thr.bear=0.60

# Per-trade-type exits — defaults are fine but can override
candpivot.tradeType=OPTIONS
candpivot.timeout.options=15
candpivot.timeout.cash=38
candpivot.timeout.fut=25
candpivot.target.options=5.0
candpivot.target.cash=5.0
candpivot.target.fut=5.0

# Disable old impulse scanner if not also wanted
impulse.enabled=false
impulse.threshold=0.10
```

---

## 6. Run the existing deploy pipeline

```bash
sudo /opt/deploy.sh
```

This rebuilds the Java jar (Spring Boot) and restarts the service. Watch for:

- `Started KiteconApplication in N seconds` (should be ~20s)
- No `BeanCreationException` — if you see one, double-check the application.properties syntax

---

## 7. Restart the prediction server

```bash
# Find the prediction server process
sudo systemctl status prediction-server  # if managed by systemd
# OR find by port:
sudo lsof -i :8501 -t | xargs sudo kill
sudo systemctl restart prediction-server
# OR manually:
cd /opt/zerodha-algo-trading/ds-python/finrl-poc
nohup .venv/bin/python service/prediction_server.py > /var/log/prediction_server.log 2>&1 &
```

Verify the new model loaded:

```bash
curl -s http://localhost:8501/health | jq '.models[] | select(.name=="impulse")'
# Expected output:  {"name": "impulse", "feature_count": 395, "num_classes": 2}
```

If you see `feature_count: 381`, the prediction server is running on the OLD model — kill it and restart.

---

## 8. Smoke test

Manually trigger a scan to confirm signals fire:

```bash
JWT=$(curl -sS -X POST -H 'Content-Type: application/json' -d '{"username":"...","password":"..."}' https://tradeapi.dheemantech.in/api/auth/login | jq -r '.token')
curl -sS -X POST -H "Authorization: Bearer $JWT" https://tradeapi.dheemantech.in/api/candpivot/trigger-scan
```

Watch the logs:

```bash
sudo journalctl -u kitecon -f | grep CandPivotLive
# Should see: [CandPivotLive] starting scan tradeType=OPTIONS thrBull=0.60 thrBear=0.60
# And per-symbol:           [CandPivotLive] WATCHING <SYMBOL> LONG entry=... sl=... tgt=... conf=...
```

---

## 9. Verify trades flow into DB

```bash
mysql -e "SELECT id, symbol, direction, status, entry_price, stop_loss, target, instrument_type, created_at FROM trade_signal WHERE strategy_type='IMPULSE' ORDER BY id DESC LIMIT 5;" kitecon
```

Should see WATCHING_ENTRY signals with `instrument_type='OPTIONS'`.

---

## 10. Monitor first hour

The cron fires at `:46` past every hour during market (9:16 IST → 15:16 IST). Watch the next scheduled run land in logs and confirm trades appear in the UI under `/trades`.

If something looks wrong, **disable the new scanner** without redeploying:

```bash
# In application.properties:
candpivot.live.enabled=false
# Then:
sudo systemctl restart kitecon
```

The OLD `ImpulseSignalScanner` was already disabled in step 5; flipping `candpivot.live.enabled` back to false leaves the system silent (no automated trades).

---

## What's NOT in this deploy (deferred)

- **Pullback-confirm at entry in live**: the SIM uses adverse-move + recovery; live MVP just sets a 5-hour entry window and lets the existing entry handler trigger on price-cross. To fully replicate SIM behavior, a tick-monitor service is needed (~150 LOC follow-up).
- **EMA20 close-cross stop in live**: live exits use static SL/target. Adding a per-bar EMA20 cross monitor is a follow-up.
- **Reverse-on-stop in live**: not wired. SIM auto-reverses; live does not.

For the first run, the strategy operates on a simpler entry/exit model. Sim performance numbers (+1,853% with options) are an upper bound — live will likely capture 50–80% of that until the tick monitor is added.
