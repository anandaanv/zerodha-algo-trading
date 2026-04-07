"""
Chronos-Bolt (Tiny) server — Port 11111
Model: amazon/chronos-bolt-tiny
"""
from __future__ import annotations

import os
from contextlib import asynccontextmanager
from typing import List

import numpy as np
import torch
import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

os.environ["TOKENIZERS_PARALLELISM"] = "false"

# ── model state ──────────────────────────────────────────────────────────────
_pipeline = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _pipeline
    from chronos import ChronosBoltPipeline  # type: ignore

    _pipeline = ChronosBoltPipeline.from_pretrained(
        "amazon/chronos-bolt-tiny",
        device_map="cpu",
        torch_dtype=torch.bfloat16,
    )
    yield
    _pipeline = None


app = FastAPI(title="Chronos-Bolt Tiny", lifespan=lifespan)


# ── schemas ───────────────────────────────────────────────────────────────────
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


# ── helpers ───────────────────────────────────────────────────────────────────
def _convergence(zigzag: List[float]) -> float:
    """
    Convergence metric for triangle/wedge detection on zigzag coordinates.
    Splits zigzag into even-indexed (peaks) and odd-indexed (troughs) sub-series,
    fits linear regression on each, then scores based on:
      - amplitude decay  (last swing / first swing)
      - slope sign opposition (peak slope down + trough slope up = converging)
    Returns a value in [0, 1] where 1.0 = perfect triangle convergence.
    """
    if len(zigzag) < 4:
        return 0.0

    vals = np.array(zigzag, dtype=np.float64)

    # swing amplitudes
    amplitudes = np.abs(np.diff(vals))
    if amplitudes[0] == 0:
        return 0.0
    amplitude_decay = max(0.0, 1.0 - amplitudes[-1] / amplitudes[0])

    # slope of peaks vs troughs
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

    # converging when slopes oppose: peaks fall, troughs rise (or vice versa)
    slope_opposition = 1.0 if (slope_p * slope_t < 0) else 0.0

    convergence = 0.6 * amplitude_decay + 0.4 * slope_opposition
    return round(float(np.clip(convergence, 0.0, 1.0)), 4)


# ── endpoints ─────────────────────────────────────────────────────────────────
@app.get("/health")
def health():
    return {"status": "ok", "model": "chronos-bolt-tiny"}


@app.post("/scan", response_model=ScanResponse)
def scan(req: ScanRequest):
    if _pipeline is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    if len(req.values) < 4:
        raise HTTPException(status_code=422, detail="Need at least 4 values")

    context = torch.tensor(req.values, dtype=torch.bfloat16).unsqueeze(0)

    forecast = _pipeline.predict(
        context=context,
        prediction_length=req.prediction_length,
        num_samples=req.num_samples,
        limit_prediction_length=False,
    )
    # forecast shape: (1, num_samples, prediction_length)
    samples = forecast[0].float().numpy()  # (num_samples, pred_len)

    low = np.quantile(samples, 0.1, axis=0).tolist()
    median = np.quantile(samples, 0.5, axis=0).tolist()
    high = np.quantile(samples, 0.9, axis=0).tolist()

    convergence = _convergence(req.values)
    import logging
    logging.basicConfig(level=logging.INFO)
    logger = logging.getLogger("chronos")
    logger.info(
        "[chronos] input_len=%d  pred_len=%d  "
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
        model="chronos-bolt-tiny",
        forecast_low=low,
        forecast_median=median,
        forecast_high=high,
        raw_samples=samples.tolist(),
        convergence_metric=convergence,
    )


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=11111, log_level="info")
