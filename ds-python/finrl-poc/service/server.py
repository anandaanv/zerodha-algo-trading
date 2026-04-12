"""
Trade filter prediction service.

Usage:
    python service/server.py          # default port 5001
    python service/server.py --port 5001

POST /predict
    Body: JSON with all trade feature fields (same names as CSV columns)
    Returns: {"prob": 0.87, "accept": true, "threshold": 0.82}

GET /health
    Returns: {"status": "ok", "threshold": 0.82, "n_features": 39}
"""
import argparse, json, os, sys
import numpy as np
import joblib
from flask import Flask, request, jsonify

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
from analysis.trade_filter import extract_features

app = Flask(__name__)

MODEL = None
META  = None

def load_model():
    global MODEL, META
    model_path = os.path.join(os.path.dirname(__file__), "model/trade_filter.pkl")
    meta_path  = os.path.join(os.path.dirname(__file__), "model/meta.json")
    MODEL = joblib.load(model_path)
    with open(meta_path) as f:
        META = json.load(f)
    print(f"[TradeFilter] Model loaded  threshold={META['threshold']}  features={len(META['features'])}")

@app.route("/health", methods=["GET"])
def health():
    return jsonify({
        "status": "ok",
        "threshold": META["threshold"],
        "n_features": len(META["features"]),
        "trained_on": META.get("trained_on", ""),
        "n_rows": META.get("n_rows", 0),
    })

@app.route("/predict", methods=["POST"])
def predict():
    data = request.get_json(force=True)
    try:
        features = extract_features(data).reshape(1, -1)
        prob = float(MODEL.predict_proba(features)[0, 1])
        accept = prob >= META["threshold"]
        return jsonify({"prob": round(prob, 4), "accept": accept, "threshold": META["threshold"]})
    except Exception as e:
        return jsonify({"error": str(e)}), 400

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=5001)
    args = parser.parse_args()
    load_model()
    app.run(host="0.0.0.0", port=args.port, debug=False)
