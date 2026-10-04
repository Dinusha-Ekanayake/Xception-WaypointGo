"""Waypoint model service: serves the trained Datathon models to the backend.

It only predicts. It never trains, never reads a database and keeps no state
between requests beyond the models it loaded at start, which is what lets the
backend treat it as a replaceable adapter (ADR-001).
"""
import logging
import time

from fastapi import FastAPI, HTTPException

from . import registry, scoring
from .schemas import (DeliveryRiskRequest, DeliveryRiskResponse, DemandForecastRequest, DemandForecastResponse,
                      Health, StopPrediction, WeekForecast)

log = logging.getLogger('ml-server')
app = FastAPI(title='Waypoint model service', version='1.0')

# Refuses to start on a missing or altered model file (registry.verify).
LOADED = registry.load()


def _info(kind):
    return LOADED.info[kind]


@app.get('/health', response_model=Health)
def health():
    return Health(status='ok', models=[m.as_json() for m in LOADED.info.values()])


@app.post('/v1/delivery-risk', response_model=DeliveryRiskResponse)
def delivery_risk(req: DeliveryRiskRequest):
    started = time.perf_counter()
    try:
        rows = scoring.delivery_risk(LOADED, req.routes, req.reference)
    except scoring.BadRequest as e:
        raise HTTPException(status_code=422, detail=str(e)) from e
    used = {rc for *_, rc in rows}
    info = _info('delivery_risk')
    log.info('delivery-risk: %d routes, %d stops in %.0f ms', len(req.routes), len(rows),
             (time.perf_counter() - started) * 1000)
    return DeliveryRiskResponse(
        kind=info.kind, modelName=info.name, modelVersion=info.version,
        roadConditions='mixed' if len(used) > 1 else used.pop(),
        predictions=[StopPrediction(deliveryId=d, serviceMin=s, lateProb=p, roadConditions=rc) for d, s, p, rc in rows])


@app.post('/v1/demand-forecast', response_model=DemandForecastResponse)
def demand_forecast(req: DemandForecastRequest):
    try:
        rows = scoring.demand_forecast(LOADED, req.calendar, req.weeks)
    except scoring.BadRequest as e:
        raise HTTPException(status_code=422, detail=str(e)) from e
    info = _info('demand_forecast')
    return DemandForecastResponse(
        kind=info.kind, modelName=info.name, modelVersion=info.version,
        forecasts=[WeekForecast(depot=d, brand=b, isoYear=y, isoWeek=w, totalM3=t, chilledM3=c)
                   for d, b, y, w, t, c in rows])
