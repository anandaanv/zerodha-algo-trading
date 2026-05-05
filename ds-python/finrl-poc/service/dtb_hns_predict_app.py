"""
Flask service for DTB+HNS XGBoost model scoring.
Listens on port 5002. Mirrors trade-filter pattern.
"""
import os
import joblib
import numpy as np
from flask import Flask, request, jsonify

app = Flask(__name__)

MODEL_PATH = os.environ.get("DTB_HNS_MODEL_PATH", "/tmp/dtbhns_model/dtb_hns_binary_xgb_v2.pkl")
MODEL = None
FEATURE_COLS = None

try:
    bundle = joblib.load(MODEL_PATH)
    MODEL = bundle["model"]
    FEATURE_COLS = bundle["feature_cols"]
    print(f"[dtb-hns-predict] Loaded model from {MODEL_PATH} with {len(FEATURE_COLS)} features")
except Exception as e:
    print(f"[dtb-hns-predict] WARN: failed to load model: {e}")

@app.route("/health")
def health():
    return jsonify({
        "status": "ok" if MODEL is not None else "model_not_loaded",
        "n_features": len(FEATURE_COLS) if FEATURE_COLS else 0
    })

@app.route("/score-dtb-hns", methods=["POST"])
def score():
    if MODEL is None:
        return jsonify({"error": "model not loaded"}), 503
    body = request.get_json(force=True, silent=True) or {}
    features = body.get("features")
    if not isinstance(features, list) or len(features) != 400:
        return jsonify({"error": f"features must be list of 400 floats, got {len(features) if isinstance(features, list) else type(features).__name__}"}), 400
    arr = np.array(features, dtype=np.float64).reshape(1, -1)
    score = float(MODEL.predict_proba(arr)[:, 1][0])
    prediction = int(score >= 0.5)
    return jsonify({"score": score, "prediction": prediction})

if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5002))
    app.run(host="0.0.0.0", port=port, debug=False)
