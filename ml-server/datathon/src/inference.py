"""Inference from saved model files (used by the notebook's final cell and for deployment).

    from src import inference
    t1 = inference.predict_task1(orders_df, legs_df)          # planned routes -> pred_service_min, pred_late_prob
    t2 = inference.predict_task2a(task2a_inputs_df)           # depot/brand/iso week rows -> total & chilled m3
"""
import json
import numpy as np
import pandas as pd
import joblib

from . import config as C, data, features_a, features_b, task1_a, task1_b, task2a


def load_models(models_dir=None):
    d = C.MODELS_DIR if models_dir is None else models_dir
    return dict(A=joblib.load(d / 'task1_A.joblib'), B=joblib.load(d / 'task1_B.joblib'),
                blend=json.load(open(d / 'task1_blend.json')), t2a=joblib.load(d / 'task2a.joblib'))


def predict_task1(orders, legs, models=None, g=None, use_fallback=False):
    """orders: rows like task1_test_inputs.csv; legs: the matching route legs (complete routes, planned times only)."""
    g = g or data.load_general(); models = models or load_models()
    if use_fallback:
        mb = joblib.load(C.MODELS_DIR / 'task1_B_no_road_conditions.joblib')
        xb = features_b.build([('test', orders, legs)], g, no_road_conditions=True)
        pb = task1_b.predict(mb, xb)
        return xb[['delivery_id']].join(pb).rename(columns={'svc': 'pred_service_min', 'late': 'pred_late_prob'})[
            ['delivery_id', 'pred_service_min', 'pred_late_prob']].round({'pred_service_min': 2, 'pred_late_prob': 4})
    xb = features_b.build([('test', orders, legs)], g)
    pb = xb[['delivery_id']].join(task1_b.predict(models['B'], xb))
    xa = features_a.build([('test', legs)], [orders], g)
    xa = features_a.set_categories(xa, models['A']['cats'])
    pa = task1_a.predict(models['A'], xa, g['traffic_speed'])
    m = orders[['delivery_id']].merge(pa[['delivery_id', 'svc', 'late']], on='delivery_id').merge(
        pb[['delivery_id', 'svc', 'late']], on='delivery_id', suffixes=('_A', '_B'))
    wl, ws = models['blend']['w_A_late'], models['blend']['w_A_svc']
    a_s, a_l, b_s, b_l = m.svc_A.round(2), m.late_A.round(4), m.svc_B.round(2), m.late_B.round(4)
    m['pred_service_min'] = (ws * a_s + (1 - ws) * b_s).round(2)
    m['pred_late_prob'] = np.clip(wl * a_l + (1 - wl) * b_l, 5e-4, 1 - 5e-4).round(4)
    return m[['delivery_id', 'pred_service_min', 'pred_late_prob', 'svc_A', 'svc_B', 'late_A', 'late_B']]


def predict_task2a(rows, models=None, g=None):
    """rows: depot, brand, iso_year, iso_week (like task2a_test_inputs.csv)."""
    g = g or data.load_general(); models = models or load_models()
    c, wk, _ = task2a.calendar_features(g['calendar'])
    r = rows.merge(wk[['iso_year', 'iso_week', 'widx']], on=['iso_year', 'iso_week'], how='left')
    f = task2a.forecast(models['t2a'], c, sorted(r.widx.unique()))
    out = r.merge(f[['widx', 'depot', 'brand', 'total', 'chilled']], on=['widx', 'depot', 'brand'], how='left')
    out['pred_total_volume_m3'] = out.total.round(2)
    out['pred_chilled_volume_m3'] = np.where(out.brand == 'Fresh', out.chilled, 0.0).round(2)
    return out.drop(columns=['widx', 'total', 'chilled'])
