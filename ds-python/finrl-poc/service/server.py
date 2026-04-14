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
from stable_baselines3 import PPO

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
from analysis.trade_filter import extract_features

app = Flask(__name__)

MODEL = None
META  = None
EXIT_MODEL = None

def load_model():
    global MODEL, META, EXIT_MODEL
    model_path = os.path.join(os.path.dirname(__file__), "model/trade_filter.pkl")
    meta_path  = os.path.join(os.path.dirname(__file__), "model/meta.json")
    MODEL = joblib.load(model_path)
    with open(meta_path) as f:
        META = json.load(f)
    print(f"[TradeFilter] Model loaded  threshold={META['threshold']}  features={len(META['features'])}")

    exit_model_path = os.path.join(os.path.dirname(__file__), "../models/ppo_exit_optimizer_70_full.zip")
    if os.path.exists(exit_model_path):
        EXIT_MODEL = PPO.load(exit_model_path)
        print(f"[ExitOptimizer] RL exit model loaded from {exit_model_path}")
    else:
        print(f"[ExitOptimizer] No exit model found at {exit_model_path} — exit predictions disabled")

@app.route("/health", methods=["GET"])
def health():
    return jsonify({
        "status": "ok",
        "threshold": META["threshold"],
        "n_features": len(META["features"]),
        "trained_on": META.get("trained_on", ""),
        "n_rows": META.get("n_rows", 0),
        "exit_model_loaded": EXIT_MODEL is not None,
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

@app.route("/predict-exit", methods=["POST"])
def predict_exit():
    """
    Predict whether to HOLD or EXIT an active trade.

    Input JSON: {
        "entry_price": 1300.0,
        "stop_loss": 1260.0,
        "target": 1380.0,
        "direction": "LONG",
        "current_price": 1340.0,
        "current_high": 1345.0,
        "current_low": 1335.0,
        "current_open": 1338.0,
        "current_volume": 50000,
        "bars_held": 10,
        "mfe": 0.035,
        "mae": 0.005,
        "entry_rsi": 55.0,
        "entry_adx": 28.0,
        "entry_macd_hist": 0.5,
        "entry_bb_pctb": 0.7,
        "entry_bb_width": 0.04,
        "entry_stoch_k": 0.6,
        "entry_daily_rsi": 52.0,
        "entry_rr": 2.0,
        "rsi": 60.0,
        "stoch_rsi_k": 0.7,
        "macd_hist": 0.3,
        "macd_cross": 1.0,
        "adx": 30.0,
        "adx_momentum": 2.0,
        "bb_pct_b": 0.8,
        "bb_width": 0.05,
        "rsi_slope": 1.5,
        "macd_hist_slope": 0.2,
        "price_vs_ema": 1.2,
        "atr_ratio": 1.1
    }

    Returns: {"action": "HOLD" or "EXIT", "confidence": 0.85}
    """
    if EXIT_MODEL is None:
        return jsonify({"error": "Exit model not loaded"}), 503

    data = request.get_json(force=True)
    try:
        entry = float(data.get("entry_price", 0))
        sl = float(data.get("stop_loss", 0))
        target = float(data.get("target", 0))
        is_long = data.get("direction", "LONG") == "LONG"
        close = float(data.get("current_price", 0))
        high = float(data.get("current_high", close))
        low = float(data.get("current_low", close))
        opn = float(data.get("current_open", close))
        volume = float(data.get("current_volume", 0))
        bars_held = int(data.get("bars_held", 0))
        max_hold = 200

        # Trade vitals
        if is_long:
            unrealized_pnl = (close - entry) / entry if entry > 0 else 0
            dist_to_target = (target - close) / entry if entry > 0 else 0
            dist_to_sl = (close - sl) / entry if entry > 0 else 0
        else:
            unrealized_pnl = (entry - close) / entry if entry > 0 else 0
            dist_to_target = (close - target) / entry if entry > 0 else 0
            dist_to_sl = (sl - close) / entry if entry > 0 else 0

        mfe = float(data.get("mfe", max(0, unrealized_pnl)))
        mae = float(data.get("mae", 0))
        bars_norm = bars_held / max_hold

        # Entry context (frozen)
        entry_context = np.array([
            float(data.get("entry_rsi", 50)) / 100.0,
            float(data.get("entry_adx", 25)) / 100.0,
            float(data.get("entry_macd_hist", 0)),
            float(data.get("entry_bb_pctb", 0.5)),
            float(data.get("entry_bb_width", 0.05)),
            float(data.get("entry_stoch_k", 0.5)),
            float(data.get("entry_daily_rsi", 50)) / 100.0,
            min(float(data.get("entry_rr", 1.0)), 10.0) / 10.0,
            1.0 if is_long else 0.0,
        ], dtype=np.float32)

        # Live indicators
        atr_raw = float(data.get("atr_raw", max(high - low, 0.01)))
        body = abs(close - opn)
        candle_range = max(high - low, 0.01)

        live_obs = np.array([
            unrealized_pnl, mfe, mae, bars_norm, dist_to_target, dist_to_sl,
            float(data.get("rsi", 50)) / 100.0,
            float(data.get("stoch_rsi_k", 0.5)),
            float(data.get("macd_hist", 0)),
            float(data.get("macd_cross", 0)),
            float(data.get("adx", 25)) / 100.0,
            float(data.get("adx_momentum", 0)) / 100.0,
            float(data.get("bb_pct_b", 0.5)),
            float(data.get("bb_width", 0.05)),
            float(data.get("rsi_slope", 0)),
            float(data.get("macd_hist_slope", 0)),
            float(data.get("price_vs_ema", 0)),
            float(data.get("atr_ratio", 1.0)),
            body / max(atr_raw, 0.01),
            (high - max(opn, close)) / candle_range,
            (min(opn, close) - low) / candle_range,
            float(data.get("volume_ratio", 1.0)),
        ], dtype=np.float32)

        obs = np.concatenate([entry_context, live_obs])

        action, _ = EXIT_MODEL.predict(obs, deterministic=True)
        action_name = "EXIT" if int(action) == 1 else "HOLD"

        # Get action probabilities for confidence
        import torch
        obs_tensor = torch.tensor(obs.reshape(1, -1), dtype=torch.float32)
        with torch.no_grad():
            dist = EXIT_MODEL.policy.get_distribution(obs_tensor)
            probs = dist.distribution.probs.numpy()[0]
        confidence = float(probs[int(action)])

        return jsonify({
            "action": action_name,
            "confidence": round(confidence, 4),
            "unrealized_pnl_pct": round(unrealized_pnl * 100, 2),
            "bars_held": bars_held,
        })
    except Exception as e:
        return jsonify({"error": str(e)}), 400

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=5001)
    args = parser.parse_args()
    load_model()
    app.run(host="0.0.0.0", port=args.port, debug=False)
