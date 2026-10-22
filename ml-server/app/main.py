"""Waypoint model service: serves the trained models to the backend.

It only predicts. It never trains, never reads a database and keeps no state
between requests beyond the models it loaded, which is what lets the backend
treat it as a replaceable adapter (ADR-001). Beside the image's models it
serves those in the model store (issue #12), and a request names the one it
means; a request that names none is answered by the image's model.
"""
import logging
import time

from fastapi import FastAPI, HTTPException

from . import registry, scoring
from .schemas import (DeliveryRiskRequest, DeliveryRiskResponse, DemandForecastRequest, DemandForecastResponse,
                      Health, StopPrediction, WeekForecast)

log = logging.getLogger('ml-server')
app = FastAPI(title='Waypoint model service', version='1.1')

# Refuses to start on a missing or altered model file in the image (registry.verify). A bad store
# entry does not stop it: the entry is listed as rejected on /health.
LOADED = registry.load()


def _resolve(kind, label):
    try:
        return scoring.resolve(LOADED, kind, label)
    except scoring.BadRequest as e:
        raise HTTPException(status_code=422, detail=str(e)) from e


@app.get('/health', response_model=Health)
def health():
    if LOADED.store is not None:
        LOADED.store.refresh()
    return Health(status='ok', models=[m.as_json() for m in LOADED.info.values()], available=LOADED.available(),
                  rejected=LOADED.store.rejected() if LOADED.store is not None else [])


@app.post('/v1/delivery-risk', response_model=DeliveryRiskResponse)
def delivery_risk(req: DeliveryRiskRequest):
    started = time.perf_counter()
    info, _ = _resolve('delivery_risk', req.model)
    try:
        rows = scoring.delivery_risk(LOADED, req.routes, req.reference, req.model)
    except scoring.BadRequest as e:
        raise HTTPException(status_code=422, detail=str(e)) from e
    used = {rc for *_, rc in rows}
    log.info('delivery-risk %s: %d routes, %d stops in %.0f ms', info.label, len(req.routes), len(rows),
             (time.perf_counter() - started) * 1000)
    return DeliveryRiskResponse(
        kind=info.kind, modelName=info.name, modelVersion=info.version,
        roadConditions='mixed' if len(used) > 1 else used.pop(),
        predictions=[StopPrediction(deliveryId=d, serviceMin=s, lateProb=p, roadConditions=rc) for d, s, p, rc in rows])


@app.post('/v1/demand-forecast', response_model=DemandForecastResponse)
def demand_forecast(req: DemandForecastRequest):
    info, _ = _resolve('demand_forecast', req.model)
    try:
        rows = scoring.demand_forecast(LOADED, req.calendar, req.outlets, req.weeks, req.model)
    except scoring.BadRequest as e:
        raise HTTPException(status_code=422, detail=str(e)) from e
    return DemandForecastResponse(
        kind=info.kind, modelName=info.name, modelVersion=info.version,
        forecasts=[WeekForecast(depot=d, brand=b, isoYear=y, isoWeek=w, totalM3=t, chilledM3=c)
                   for d, b, y, w, t, c in rows])
