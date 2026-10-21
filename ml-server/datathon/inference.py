"""Inference from the saved model files: the final notebook's last cell as functions.

Written for the model service, not part of the Datathon submission. The other
modules in this folder are vendored unchanged, and the pickled models name
their classes as these top-level modules (`parallel_model`, `mc_task1`,
`task2a_demand`), so the files keep their names.

    models = inference.load_models(models_dir)
    t1 = inference.predict_task1(orders, legs, models)        # planned routes -> pred_service_min, pred_late_prob
    t2 = inference.predict_task2a(rows, models, calendar, outlets)
"""
from pathlib import Path

import joblib

import task2a_demand

TASK1 = 'task1_parallel_model.joblib'
TASK2A = 'task2a_model.joblib'


def load_models(models_dir):
    d = Path(models_dir)
    return dict(task1=joblib.load(d / TASK1), task2a=joblib.load(d / TASK2A))


def predict_task1(orders, legs, models, use_fallback=False):
    """orders: rows like task1_test_inputs.csv; legs: their route legs (complete routes, planned times only).

    Scored with the reference tables the model was trained with. `use_fallback`
    scores with the copy trained without road conditions.
    """
    return models['task1'].predict(orders, legs, use_fallback=use_fallback)


def predict_task2a(rows, models, calendar, outlets):
    """rows: depot, brand, iso_year, iso_week (like task2a_test_inputs.csv); adds pred_total_volume_m3, pred_chilled_volume_m3."""
    return task2a_demand.predict_rows(models['task2a'], rows, calendar, outlets)
