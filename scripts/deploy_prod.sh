#!/bin/bash
# =====================================================================
# Production Deployment — Impulse Strategy (Price-Jump + Elliott)
# Run on EC2: bash /opt/code/scripts/deploy_prod.sh
# =====================================================================
set -e

PROJECT_DIR="/opt/code"
TA4J_DIR="$HOME/ta4j-zerodha"
PYTHON_VENV="$PROJECT_DIR/ds-python/finrl-poc/.venv"
MODEL_DIR="$PROJECT_DIR/ds-python/finrl-poc/service/model"
TOMCAT_DIR="/opt/tomcat"
BRANCH="${1:-master}"

echo "═══════════════════════════════════════════════"
echo "  Production Deploy — $(date '+%Y-%m-%d %H:%M:%S')"
echo "  Branch: $BRANCH"
echo "═══════════════════════════════════════════════"

# ── 1. Install Maven (if needed) ──
echo ""
echo "▶ Step 1: Maven..."
if ! command -v mvn &> /dev/null; then
    echo "  Installing Maven..."
    sudo apt-get update -qq && sudo apt-get install -y -qq maven
fi
echo "  ✓ $(mvn -v 2>/dev/null | head -1 || echo 'Maven installed')"

# ── 2. ta4j fork ──
echo ""
echo "▶ Step 2: ta4j-core 0.22.7-SNAPSHOT..."
if [ ! -d "$TA4J_DIR" ]; then
    echo "  Cloning ta4j fork..."
    git clone git@github.com:anandaanv/ta4j-zerodha.git "$TA4J_DIR"
fi
cd "$TA4J_DIR"
git pull 2>/dev/null

# Install parent POM + core
mvn install -N -DskipTests -Denforcer.skip=true -Dmaven.test.skip=true -q
cd ta4j-core
mvn install -DskipTests -Denforcer.skip=true -Dmaven.test.skip=true -q
echo "  ✓ ta4j-core installed to ~/.m2"

# ── 3. Pull code ──
echo ""
echo "▶ Step 3: Pull code ($BRANCH)..."
cd "$PROJECT_DIR"
git fetch origin
git checkout "$BRANCH"
git pull origin "$BRANCH"

# Update strategies submodule
cd "$PROJECT_DIR/strategies"
git fetch origin
git checkout "$BRANCH" 2>/dev/null || git checkout main
git pull
cd "$PROJECT_DIR"

# ── 4. Fix symlinks ──
echo ""
echo "▶ Step 4: Fix symlinks..."
for link in $(find src/main/java -type l ! -exec test -e {} \; -print 2>/dev/null); do
    target=$(readlink "$link")
    new_target=$(echo "$target" | sed "s|/codes/algotrade/zerodha-algo-trading|$PROJECT_DIR|g")
    rm -f "$link"
    ln -s "$new_target" "$link"
    echo "  Fixed: $(basename $link)"
done
echo "  ✓ Symlinks OK"

# ── 5. Build WAR ──
echo ""
echo "▶ Step 5: Build WAR..."
cd "$PROJECT_DIR"
./gradlew bootWar
echo "  ✓ WAR built: $(ls -lh build/libs/kitecon-*.war | awk '{print $5}')"

# ── 6. Python + Models ──
echo ""
echo "▶ Step 6: Python environment..."
if [ ! -d "$PYTHON_VENV" ]; then
    python3 -m venv "$PYTHON_VENV"
fi
$PYTHON_VENV/bin/pip install -q xgboost scikit-learn pandas numpy joblib fastapi "uvicorn[standard]"
echo "  ✓ Python deps installed"

# Check models
echo "  Models:"
for pkl in "$MODEL_DIR"/*.pkl; do
    name=$(basename "$pkl" .pkl)
    echo "    $name ($(stat -c%s "$pkl" | numfmt --to=iec 2>/dev/null || echo '?'))"
done

# ── 7. Restart Prediction Server ──
echo ""
echo "▶ Step 7: Prediction server..."
pkill -f prediction_server.py 2>/dev/null && echo "  Killed old" || echo "  None running"
sleep 2

cd "$PROJECT_DIR/ds-python/finrl-poc"
export EXTRA_MODEL_PATHS="$MODEL_DIR/impulse.pkl"
nohup $PYTHON_VENV/bin/python service/prediction_server.py > /tmp/prediction_server.log 2>&1 &
echo "  Started PID=$!"

for i in $(seq 1 30); do
    if curl -s http://localhost:8501/health > /dev/null 2>&1; then
        echo "  ✓ Prediction server ready"
        break
    fi
    sleep 2
done

# ── 8. Deploy WAR to Tomcat ──
echo ""
echo "▶ Step 8: Deploy to Tomcat..."
sudo cp "$PROJECT_DIR/build/libs/kitecon-0.0.1-SNAPSHOT.war" "$TOMCAT_DIR/webapps/kitecon.war"
echo "  WAR copied — Tomcat auto-redeploys"

# Wait for Tomcat to redeploy
echo "  Waiting for redeploy..."
sleep 15
for i in $(seq 1 60); do
    CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/kitecon/api/auth/health 2>/dev/null || echo "0")
    if [ "$CODE" = "403" ] || [ "$CODE" = "200" ]; then
        echo "  ✓ Tomcat ready (HTTP $CODE)"
        break
    fi
    if [ "$i" = "60" ]; then
        echo "  ⚠ Tomcat not responding after 5 min — check logs"
    fi
    sleep 5
done

# ── 9. Verify ──
echo ""
echo "═══════════════════════════════════════════════"
echo "  DEPLOYMENT STATUS"
echo "═══════════════════════════════════════════════"

# Prediction server
PRED_OK=$(curl -s http://localhost:8501/health 2>/dev/null)
if [ -n "$PRED_OK" ]; then
    echo "  ✓ Prediction Server: OK"
else
    echo "  ✗ Prediction Server: NOT RUNNING"
fi

# Tomcat
TOMCAT_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/kitecon/api/auth/health 2>/dev/null)
if [ "$TOMCAT_CODE" = "403" ] || [ "$TOMCAT_CODE" = "200" ]; then
    echo "  ✓ Tomcat: OK (HTTP $TOMCAT_CODE)"
else
    echo "  ✗ Tomcat: HTTP $TOMCAT_CODE"
fi

# Config
echo ""
echo "  Config:"
echo "    impulse.enabled=$(grep '^impulse.enabled=' $PROJECT_DIR/src/main/resources/application.properties | cut -d= -f2)"
echo "    impulse.threshold=$(grep '^impulse.threshold=' $PROJECT_DIR/src/main/resources/application.properties | cut -d= -f2)"
echo ""
echo "═══════════════════════════════════════════════"
echo "  NEXT: Login to Kite via browser"
echo "  Then set impulse.enabled=true and re-deploy"
echo "═══════════════════════════════════════════════"
