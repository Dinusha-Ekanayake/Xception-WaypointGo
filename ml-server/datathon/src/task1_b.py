"""Agent-B Task 1 model: route simulator + LightGBM hybrids.
  service = mean(LightGBM-L1, LightGBM-L2) with simulator outputs as features
  late    = mean(simulator P(late), direct LightGBM classifier, LightGBM classifier with simulator features)
"""
import numpy as np
import pandas as pd
import lightgbm as lgb
from .simulator import RouteSimulator, SpeedTable

CAT = ['brand', 'dock_type', 'parking_constraint', 'district', 'outlet_id', 'vehicle_id', 'vehicle_type', 'temp_requirement']
NUM = ['seq', 'n_stops', 'lu', 'lv', 'lw', 'order_units', 'order_volume_m3', 'order_weight_kg', 'service_allowance_min', 'dow',
       'monsoon', 'is_payday', 'festival_ramp', 'is_holiday', 'disruption_index', 'spd_pl', 'exp_travel',
       'planned_travel_duration_min', 'distance_km', 'parr', 'pdep', 'wo', 'wc', 'slack_close', 'slack_open', 'cum_allow',
       'cum_travel_excess', 'cum_pwait', 'start_pdep', 'days_deferred', 'cum_units', 'chilled', 'van']   # no trend in trees
SIMF = ['p_sim', 'svc_sim_med', 'arr_sim_mean', 'arr_sim_q90', 'sim_margin', 'mu0']


def categories(X):
    return {c: sorted(X[c].dropna().unique()) for c in CAT}


def prep(df, cats, extra=None):
    Z = df[CAT + NUM].copy()
    for c in CAT:
        Z[c] = pd.Categorical(Z[c], categories=cats[c])
    if extra is not None:
        for f in SIMF:
            Z[f] = extra[f].values
    return Z


def _train(Z, y, obj, rounds, threads=2):
    p = dict(objective=obj, learning_rate=0.03, num_leaves=31, min_data_in_leaf=50, feature_fraction=0.8, bagging_fraction=0.8,
             bagging_freq=1, lambda_l2=1.0, verbose=-1, num_threads=threads, seed=1, cat_smooth=20)
    return lgb.train(p, lgb.Dataset(Z, y), rounds)


def fit(tr, cats, outlets, general, festivals, n_sim_train=150):
    """outlets: sorted outlet ids present in the modelling frame (design-matrix columns)."""
    sim = RouteSimulator(outlets, festivals, SpeedTable(general['traffic_speed'])).fit(tr)
    s_tr = sim.run(tr, n_sim_train, seed=1)
    Zs = prep(tr, cats, s_tr); Z = prep(tr, cats)
    m = dict(sim=sim, cats=cats,
             svc_l1=_train(Zs, tr.svc.values, 'regression_l1', 600),
             svc_l2=_train(Zs, tr.svc.values, 'regression', 600),
             late_direct=_train(Z, tr.late.values, 'binary', 500),
             late_hybrid=_train(Zs, tr.late.values, 'binary', 500))
    return m


def predict(m, df, n_sim=1000, seed=7):
    s = m['sim'].run(df, n_sim, seed=seed)
    Zs = prep(df, m['cats'], s); Z = prep(df, m['cats'])
    svc = 0.5 * (m['svc_l1'].predict(Zs) + m['svc_l2'].predict(Zs))
    late = (s.p_sim.values + m['late_direct'].predict(Z) + m['late_hybrid'].predict(Zs)) / 3
    return pd.DataFrame({'svc': svc, 'late': np.clip(late, 0.0005, 0.9995), 'p_sim': s.p_sim.values,
                         'svc_sim': s.svc_sim_mean.values}, index=df.index)
