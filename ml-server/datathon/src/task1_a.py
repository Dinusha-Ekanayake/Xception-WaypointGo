"""Agent-A Task 1 model (ported from work/agent_a/t1_models.py, t1_sim.py, t1_final.py).
  stage 1 : LightGBM on log service with the `late` flag as a feature (evaluated with late=0/1 for the simulator)
  simulator: Monte-Carlo propagation along each route (start delay, district travel-noise pool, service noise)
  service : mean(LightGBM-L2, XGBoost-L2, CatBoost-RMSE) on planned features
  late    : 0.5 * simulator P(late) (1000 draws) + 0.5 * mean(LightGBM, XGBoost, CatBoost classifiers with simulator features)
"""
import numpy as np
import pandas as pd
import lightgbm as lgb
import xgboost as xgb
from catboost import CatBoostRegressor, CatBoostClassifier
from sklearn.model_selection import GroupKFold
from .features_a import CAT, NUM

SIM = ['sim_p_late', 'sim_arr_mean', 'sim_arr_sd', 'sim_slack_mean', 'sim_slack_q10', 'sim_p_early',
       'sim_wait_mean', 'sim_svc_mean', 'sim_svc_median']
FEAT = NUM + CAT
P1 = dict(n_estimators=600, learning_rate=0.03, num_leaves=63, min_child_samples=40, subsample=0.8, subsample_freq=1,
          colsample_bytree=0.8, reg_lambda=1.0, verbose=-1, n_jobs=4)
PR = dict(n_estimators=800, learning_rate=0.03, num_leaves=31, min_child_samples=50, subsample=0.8, subsample_freq=1,
          colsample_bytree=0.8, reg_lambda=1.0, verbose=-1, n_jobs=4)


# ------------------------------------------------------------------ stage 1 + noise
def stage1(tr):
    f = FEAT + ['late']
    y = np.log(tr.service_min.values)
    oof0 = np.zeros(len(tr)); oof1 = np.zeros(len(tr)); oof = np.zeros(len(tr))
    for a, b in GroupKFold(4).split(tr, groups=tr.route_id):
        m = lgb.LGBMRegressor(**P1).fit(tr.iloc[a][f], y[a])
        X = tr.iloc[b][f].copy(); oof[b] = m.predict(X)
        X['late'] = 0.0; oof0[b] = m.predict(X); X['late'] = 1.0; oof1[b] = m.predict(X)
    m = lgb.LGBMRegressor(**P1).fit(tr[f], y)
    return m, oof0, oof1, y - oof


def stage1_predict(m, df):
    X = df[FEAT + ['late']].copy(); X['late'] = 0.0; e0 = m.predict(X); X['late'] = 1.0; e1 = m.predict(X)
    return e0, e1


def fit_noise(tr, ts):
    x = tr.copy()
    x['ahr'] = (x.actual_depart_time_m // 60).astype(int) % 24
    x = x.merge(ts.rename(columns={'hour': 'ahr', 'speed_index': 'aspeed'}), on=['district', 'ahr', 'monsoon'], how='left')
    res = x.actual_travel_duration_min / (x.planned_travel_duration_min * 100 / x.aspeed * 100 / x.disruption_index)
    pools = {d: res[x.district.values == d].values for d in x.district.unique()}
    s0 = x[x.seq == 0]
    return dict(travel=pools, start=(s0.actual_depart_time_m - s0.planned_depart_time_m).values)


def simulate(df, logsvc0, logsvc1, svc_resid, noise, ts, S=200, seed=0):
    rng = np.random.default_rng(seed)
    dists = sorted(ts.district.unique()); di = {d: i for i, d in enumerate(dists)}
    spd = np.zeros((len(dists), 24, 2))
    for r in ts.itertuples():
        spd[di[r.district], r.hour, r.monsoon] = r.speed_index
    df = df.reset_index(drop=True)
    ridx = pd.factorize(df.route_id.values)[0]
    R = ridx.max() + 1; K = df.seq.max() + 1; pos = df.seq.values

    def arr(v, fill=np.nan):
        a = np.full((R, K), fill, dtype=float); a[ridx, pos] = v; return a
    ptrav = arr(df.planned_travel_duration_min.values)
    openm = arr(df.window_open_time_m.values); close = arr(df.window_close_time_m.values)
    s0 = arr(logsvc0); s1 = arr(logsvc1)
    dstart = np.zeros(R); dstart[ridx[pos == 0]] = df.planned_depart_time_m.values[pos == 0]
    dist_r = np.zeros(R, int); dist_r[ridx] = df.district.map(di).values
    mons_r = np.zeros(R, int); mons_r[ridx] = df.monsoon.values
    disr_r = np.zeros(R); disr_r[ridx] = df.disruption_index.values
    nst = np.zeros(R, int); np.maximum.at(nst, ridx, pos + 1)
    arr_s = np.full((R, K, S), np.nan); svc_s = np.full((R, K, S), np.nan); late_s = np.zeros((R, K, S))
    t = dstart[:, None] + rng.choice(noise['start'], size=(R, S))
    for k in range(K):
        act = nst > k
        if not act.any():
            break
        hr = (np.floor(t / 60).astype(int)) % 24
        sp = spd[dist_r[:, None], hr, mons_r[:, None]]
        eps = np.ones((R, S))
        for d, pool in noise['travel'].items():
            m = dist_r == di[d]
            if m.any():
                eps[m] = rng.choice(pool, size=(m.sum(), S))
        trav = ptrav[:, k][:, None] * 100 / sp * 100 / disr_r[:, None] * eps
        a = t + np.round(trav)
        late = a > close[:, k][:, None]
        start = np.maximum(a, openm[:, k][:, None])
        ls = np.where(late, s1[:, k][:, None], s0[:, k][:, None]) + rng.choice(svc_resid, size=(R, S))
        sv = np.maximum(np.round(np.exp(ls)), 1)
        arr_s[:, k] = np.where(act[:, None], a, np.nan)
        svc_s[:, k] = np.where(act[:, None], sv, np.nan)
        late_s[:, k] = late
        t = np.where(act[:, None], start + sv, t)
    A = arr_s[ridx, pos]; Sv = svc_s[ridx, pos]; Lt = late_s[ridx, pos]
    cl = df.window_close_time_m.values[:, None]; op = df.window_open_time_m.values[:, None]
    return pd.DataFrame({'sim_p_late': Lt.mean(1), 'sim_arr_mean': A.mean(1), 'sim_arr_sd': A.std(1),
                         'sim_slack_mean': (cl - A).mean(1), 'sim_slack_q10': np.quantile(cl - A, 0.1, axis=1),
                         'sim_p_early': (A < op).mean(1), 'sim_wait_mean': np.maximum(op - A, 0).mean(1),
                         'sim_svc_mean': Sv.mean(1), 'sim_svc_median': np.median(Sv, 1)})


# ------------------------------------------------------------------ fit / predict
def _cat_str(X):
    return X.astype({c: str for c in CAT})


def fit(trn, ts, log=print):
    """trn: A-feature training frame (categoricals set, sorted by route/seq)."""
    s1, o0, o1, resid = stage1(trn); log('  A: stage-1 done')
    noise = fit_noise(trn, ts)
    st = simulate(trn, o0, o1, resid, noise, ts, S=200, seed=1)
    tr = pd.concat([trn.reset_index(drop=True), st], axis=1)
    y = tr.service_min.values; f = FEAT
    reg = dict(lgb=lgb.LGBMRegressor(**PR).fit(tr[f], y),
               xgb=xgb.XGBRegressor(n_estimators=800, learning_rate=0.03, max_depth=7, subsample=0.8, colsample_bytree=0.8,
                                    enable_categorical=True, tree_method='hist', n_jobs=4, min_child_weight=20).fit(tr[f], y),
               cat=CatBoostRegressor(iterations=1500, learning_rate=0.06, depth=6, loss_function='RMSE', verbose=0, thread_count=4,
                                     cat_features=[c for c in f if c in CAT], allow_writing_files=False).fit(_cat_str(tr[f]), y))
    log('  A: service models done')
    fc = FEAT + SIM; yl = tr.late.values
    clf = dict(lgb=lgb.LGBMClassifier(**PR).fit(tr[fc], yl),
               xgb=xgb.XGBClassifier(n_estimators=800, learning_rate=0.03, max_depth=6, subsample=0.8, colsample_bytree=0.8,
                                     enable_categorical=True, tree_method='hist', n_jobs=4, min_child_weight=20).fit(tr[fc], yl),
               cat=CatBoostClassifier(iterations=1500, learning_rate=0.06, depth=6, verbose=0, thread_count=4,
                                      cat_features=[c for c in fc if c in CAT], allow_writing_files=False).fit(_cat_str(tr[fc]), yl))
    log('  A: late models done')
    return dict(stage1=s1, resid=resid, noise=noise, reg=reg, clf=clf)


def predict(m, tst, ts):
    e0, e1 = stage1_predict(m['stage1'], tst)
    se = simulate(tst, e0, e1, m['resid'], m['noise'], ts, S=200, seed=2)
    se_big = simulate(tst, e0, e1, m['resid'], m['noise'], ts, S=1000, seed=3)
    te = pd.concat([tst.reset_index(drop=True), se], axis=1)
    f = FEAT; fc = FEAT + SIM
    svc = (m['reg']['lgb'].predict(te[f]) + m['reg']['xgb'].predict(te[f]) + m['reg']['cat'].predict(_cat_str(te[f]))) / 3
    ens = (m['clf']['lgb'].predict_proba(te[fc])[:, 1] + m['clf']['xgb'].predict_proba(te[fc])[:, 1]
           + m['clf']['cat'].predict_proba(_cat_str(te[fc]))[:, 1]) / 3
    return pd.DataFrame({'delivery_id': te.delivery_id.values, 'svc': np.clip(svc, 1, None),
                         'late': np.clip(0.5 * se_big.sim_p_late.values + 0.5 * ens, 0.002, 0.998),
                         'p_sim': se_big.sim_p_late.values})
