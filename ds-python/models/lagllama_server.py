"""
Lag-Llama server — Port 11112
Model: time-series-foundation-models/Lag-Llama  (via HuggingFace hub)
"""
from __future__ import annotations

import os
from contextlib import asynccontextmanager
from pathlib import Path
from typing import List

import numpy as np
import torch
import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

os.environ["TOKENIZERS_PARALLELISM"] = "false"

_predictor = None
_REPO_ID = "time-series-foundation-models/Lag-Llama"
_CKPT_NAME = "lag-llama.ckpt"


def _load_lag_llama():
    """Download checkpoint and build GluonTS predictor."""
    from huggingface_hub import hf_hub_download  # type: ignore
    from lag_llama.gluon.estimator import LagLlamaEstimator  # type: ignore

    ckpt_path = hf_hub_download(
        repo_id=_REPO_ID,
        filename=_CKPT_NAME,
        cache_dir=Path.home() / ".cache" / "lag_llama",
    )

    ckpt = torch.load(ckpt_path, map_location="cpu", weights_only=False)
    mk = ckpt["hyper_parameters"]["model_kwargs"]

    # Pass only the known model architecture kwargs — avoid splatting the full
    # dict which contains runtime objects (distr_output, freq, etc.) that clash.
    estimator = LagLlamaEstimator(
        ckpt_path=ckpt_path,
        prediction_length=12,
        context_length=mk.get("context_length", 32),
        input_size=mk.get("input_size", 1),
        n_layer=mk.get("n_layer", 32),
        n_embd_per_head=mk.get("n_embd_per_head", 128),
        n_head=mk.get("n_head", 16),
        scaling=mk.get("scaling", "mean"),
        time_feat=mk.get("time_feat", True),
        device=torch.device("cpu"),
        batch_size=1,
        num_parallel_samples=20,
    )
    # torch >=2.6 defaults weights_only=True which rejects lag-llama checkpoints.
    # Force weights_only=False for loading (trusted HF cache files only).
    _orig_load = torch.load
    torch.load = lambda *a, **kw: _orig_load(*a, **{**kw, "weights_only": False})
    try:
        transformation = estimator.create_transformation()
        lightning_module = estimator.create_lightning_module()
        predictor = estimator.create_predictor(transformation, lightning_module)
    finally:
        torch.load = _orig_load
    return predictor


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _predictor
    _predictor = _load_lag_llama()
    yield
    _predictor = None


app = FastAPI(title="Lag-Llama", lifespan=lifespan)


class ScanRequest(BaseModel):
    values: List[float]
    prediction_length: int = 12
    num_samples: int = 20


class ScanResponse(BaseModel):
    model: str
    forecast_low: List[float]
    forecast_median: List[float]
    forecast_high: List[float]
    raw_samples: List[List[float]]
    convergence_metric: float


def _convergence(zigzag: List[float]) -> float:
    if len(zigzag) < 4:
        return 0.0
    vals = np.array(zigzag, dtype=np.float64)
    amplitudes = np.abs(np.diff(vals))
    if amplitudes[0] == 0:
        return 0.0
    amplitude_decay = max(0.0, 1.0 - amplitudes[-1] / amplitudes[0])
    peaks = vals[0::2]
    troughs = vals[1::2]
    min_len = min(len(peaks), len(troughs))
    if min_len < 2:
        return float(amplitude_decay)
    peaks = peaks[:min_len]
    troughs = troughs[:min_len]
    x = np.arange(min_len, dtype=np.float64)
    slope_p = float(np.polyfit(x, peaks, 1)[0])
    slope_t = float(np.polyfit(x, troughs, 1)[0])
    slope_opposition = 1.0 if (slope_p * slope_t < 0) else 0.0
    convergence = 0.6 * amplitude_decay + 0.4 * slope_opposition
    return round(float(np.clip(convergence, 0.0, 1.0)), 4)


@app.get("/health")
def health():
    return {"status": "ok", "model": "lag-llama"}


@app.post("/scan", response_model=ScanResponse)
def scan(req: ScanRequest):
    if _predictor is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    if len(req.values) < 4:
        raise HTTPException(status_code=422, detail="Need at least 4 values")

    import pandas as pd
    from gluonts.dataset.common import ListDataset  # type: ignore

    ds = ListDataset(
        [{"start": pd.Period("2020-01-01", freq="D"), "target": np.array(req.values)}],
        freq="D",
    )
    forecasts = list(_predictor.predict(ds, num_samples=req.num_samples))
    samples = forecasts[0].samples  # (num_samples, pred_len)

    low = np.quantile(samples, 0.1, axis=0).tolist()
    median = np.quantile(samples, 0.5, axis=0).tolist()
    high = np.quantile(samples, 0.9, axis=0).tolist()

    convergence = _convergence(req.values)
    import logging
    logging.basicConfig(level=logging.INFO)
    logger = logging.getLogger("lagllama")
    logger.info(
        "[lagllama] input_len=%d  pred_len=%d  "
        "median=[%.4f ... %.4f]  low=[%.4f ... %.4f]  high=[%.4f ... %.4f]  "
        "sample_std=%.4f  convergence=%.4f",
        len(req.values), len(median),
        median[0], median[-1],
        low[0], low[-1],
        high[0], high[-1],
        float(np.std(samples)),
        convergence,
    )
    return ScanResponse(
        model="lag-llama",
        forecast_low=low,
        forecast_median=median,
        forecast_high=high,
        raw_samples=samples.tolist(),
        convergence_metric=convergence,
    )


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=11112, log_level="info")
