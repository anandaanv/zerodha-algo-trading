"""
IBM Granite TTM server — Port 11113
Model: ibm-granite/granite-timeseries-ttm-r2
"""
from __future__ import annotations

import os
from contextlib import asynccontextmanager
from typing import List

import numpy as np
import pandas as pd
import torch
import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

os.environ["TOKENIZERS_PARALLELISM"] = "false"

_pipeline = None
_CONTEXT_LEN = 512
_FORECAST_LEN = 96


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _pipeline
    from tsfm_public import TinyTimeMixerForPrediction  # type: ignore
    from transformers import AutoConfig  # type: ignore

    cfg = AutoConfig.from_pretrained(
        "ibm-granite/granite-timeseries-ttm-r2",
        context_length=_CONTEXT_LEN,
        prediction_length=_FORECAST_LEN,
    )
    model = TinyTimeMixerForPrediction.from_pretrained(
        "ibm-granite/granite-timeseries-ttm-r2",
        config=cfg,
        ignore_mismatched_sizes=True,
    )
    model = model.to(torch.bfloat16).eval()
    _pipeline = model
    yield
    _pipeline = None


app = FastAPI(title="IBM Granite TTM", lifespan=lifespan)


class ScanRequest(BaseModel):
    values: List[float]
    prediction_length: int = 24


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


def _pad_or_truncate(values: List[float], target_len: int) -> np.ndarray:
    arr = np.array(values, dtype=np.float32)
    if len(arr) >= target_len:
        return arr[-target_len:]
    pad_len = target_len - len(arr)
    return np.pad(arr, (pad_len, 0), mode="edge")


@app.get("/health")
def health():
    return {"status": "ok", "model": "granite-ttm-r2"}


@app.post("/scan", response_model=ScanResponse)
def scan(req: ScanRequest):
    if _pipeline is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    if len(req.values) < 4:
        raise HTTPException(status_code=422, detail="Need at least 4 values")

    context = _pad_or_truncate(req.values, _CONTEXT_LEN)
    # TTM expects (batch, time, channels)
    x = torch.tensor(context, dtype=torch.bfloat16).unsqueeze(0).unsqueeze(-1)

    with torch.no_grad():
        out = _pipeline(past_values=x)

    # prediction_outputs: (1, forecast_len, 1)
    pred = out.prediction_outputs.squeeze().float().numpy()  # (forecast_len,)
    pred = pred[: req.prediction_length].tolist()

    # TTM is deterministic; synthesize uncertainty bands via small Gaussian noise
    rng = np.random.default_rng(seed=42)
    noise_std = np.std(req.values[-min(32, len(req.values)):]) * 0.05
    n_samples = 20
    samples = np.array(pred) + rng.normal(0, noise_std, size=(n_samples, len(pred)))

    low = np.quantile(samples, 0.1, axis=0).tolist()
    median = np.array(pred).tolist()
    high = np.quantile(samples, 0.9, axis=0).tolist()

    convergence = _convergence(req.values)
    import logging
    logging.basicConfig(level=logging.INFO)
    logger = logging.getLogger("granite")
    logger.info(
        "[granite] input_len=%d  pred_len=%d  "
        "median=[%.4f ... %.4f]  low=[%.4f ... %.4f]  high=[%.4f ... %.4f]  "
        "sample_std=%.4f  convergence=%.4f",
        len(req.values), len(median),
        median[0], median[-1],
        low[0], low[-1],
        high[0], high[-1],
        float(np.std(pred)),
        convergence,
    )
    return ScanResponse(
        model="granite-ttm-r2",
        forecast_low=low,
        forecast_median=median,
        forecast_high=high,
        raw_samples=samples.tolist(),
        convergence_metric=convergence,
    )


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=11113, log_level="info")
