#!/bin/bash
set -e
CODE=/opt/code
VENV=/opt/code/ds-python/finrl-poc/.venv
TA4J_DIR=/opt/code/../ta4j-zerodha

echo "=== [1/7] Pulling latest code ==="
echo "VITE_API_BASE_URL=https://tradeapi.dheemantech.in" > $CODE/ui/chart-draw-app/.env.production
echo "VITE_GOOGLE_OAUTH_CLIENT_ID=798033594474-ihj0s8isem46d64kh7p9r0a0unobam3c.apps.googleusercontent.com" >> $CODE/ui/chart-draw-app/.env.production
cd $CODE
git clean -fd
git checkout -f
git pull

echo "=== [2/7] Updating submodules ==="
cd $CODE
git submodule update --init --recursive

echo "=== [3/7] Building ta4j fork ==="
cd $TA4J_DIR
git pull
mvn install -Dmaven.test.skip=true -q

echo "=== [4/7] Building Spring WAR ==="
cd $CODE
./gradlew clean bootWar

echo "=== [5/7] Deploying WAR to Tomcat ==="
sudo systemctl stop tomcat
sudo rm -f /opt/tomcat/webapps/kitecon.war
sudo rm -rf /opt/tomcat/webapps/kitecon
sudo cp $CODE/build/libs/kitecon-0.0.1-SNAPSHOT.war /opt/tomcat/webapps/kitecon.war
sudo systemctl start tomcat

echo "=== [6/7] Setting up Python + ML services ==="
if [ ! -f "$VENV/bin/pip" ]; then
  sudo rm -rf $VENV
  sudo python3 -m venv $VENV
fi
sudo $VENV/bin/pip install -q xgboost flask scikit-learn pandas numpy uvicorn fastapi

# Trade filter service
sudo bash -c 'cat > /etc/systemd/system/trade-filter.service << UNIT_EOF
[Unit]
Description=Trade Filter ML Service
After=network.target

[Service]
User=ubuntu
WorkingDirectory=/opt/code/ds-python/finrl-poc/service
ExecStart=/opt/code/ds-python/finrl-poc/.venv/bin/python server.py
Restart=always
RestartSec=5
Environment=PORT=5001

[Install]
WantedBy=multi-user.target
UNIT_EOF'
sudo systemctl daemon-reload
sudo systemctl enable trade-filter
sudo systemctl restart trade-filter

# Impulse prediction service
sudo bash -c 'cat > /etc/systemd/system/impulse-predict.service << UNIT_EOF
[Unit]
Description=Impulse Prediction Service (FastAPI)
After=network.target

[Service]
User=ubuntu
WorkingDirectory=/opt/code/ds-python/finrl-poc/service
ExecStart=/opt/code/ds-python/finrl-poc/.venv/bin/uvicorn prediction_server:app --host 0.0.0.0 --port 8501
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT_EOF'
sudo systemctl daemon-reload
sudo systemctl enable impulse-predict
sudo systemctl restart impulse-predict

echo "=== [7/7] Done ==="
echo "Tomcat:          $(sudo systemctl is-active tomcat)"
echo "Trade-filter:    $(sudo systemctl is-active trade-filter)"
echo "Impulse-predict: $(sudo systemctl is-active impulse-predict)"
