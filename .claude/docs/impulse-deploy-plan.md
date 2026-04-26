# Impulse Production Deployment Plan — April 22, 2026

## What's Done
- EC2 instance started (i-044d55f62bbbcc527, 52.66.109.94)
- IP whitelisted for SSH
- Maven installed (3.8.7, needs `-Denforcer.skip` for ta4j)
- ta4j-core 0.22.7-SNAPSHOT built and installed to ~/.m2
- Code checked out on `feature/price-jump-impulse-strategy`
- All symlinks fixed
- Java compiles successfully
- Price-jump model (303 feat) deployed to prediction server
- Prediction server running on port 8501

## What's NOT Done
- Spring Boot restart (port 8080 occupied by existing app)
- March simulation on prod (needs Spring Boot restart)
- Kite login
- Test order placement
- deploy.sh update

## Production Architecture
- EC2: `/opt/code` = main repo
- Prediction server: port 8501 (Python FastAPI)
- Spring Boot: port 8080 (Gradle bootRun)
- Strategies submodule: `/opt/code/strategies`
- ta4j fork: `~/ta4j-zerodha` (Maven local install)
- Models: `/opt/code/ds-python/finrl-poc/service/model/`

## Deploy Script Requirements
1. Pull code from feature branch → merge to master
2. Update strategies submodule
3. Build ta4j (if changed): `mvn install -DskipTests -Denforcer.skip -Dmaven.test.skip`
4. Compile Java: `./gradlew compileJava`
5. Restart prediction server with correct model
6. Restart Spring Boot (stop gracefully, then start)
7. Verify both services

## Models
- Price-jump: `/opt/code/ds-python/finrl-poc/service/model/impulse.pkl` (303 feat)
- Elliott: needs separate model file (impulse_elliott.pkl)

## Config to set for live trading
```properties
impulse.enabled=true
impulse.threshold=0.80
impulse.exit.trail.trigger.pct=1.0
impulse.exit.trail.distance.pct=2.0
impulse.exit.max.bars=225
impulse.exit.entry.patience.bars=150
impulse.exit.stall.bars=75
```
