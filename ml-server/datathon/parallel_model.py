"""Task 1 - PARALLEL model: Monte Carlo simulator and LightGBM side by side, combined.

    branch A  Monte Carlo route simulator (500 draws / route) -> P(late), service minutes
    branch B  LightGBM on pre-departure features               -> P(late), service minutes
    combine   P(late)  : weighted average in log-odds, then isotonic calibration
              service  : weighted average
    weights + calibration are fitted on out-of-fold predictions of the development period.

Fixes over the first (sequential) build:
  * outlet_id, vehicle_id, district, ... are native LightGBM categoricals (not ordered numbers)
  * no day-of-year feature (festival dates move each year; festival_ramp carries that signal)
  * isotonic calibration of P(late) on out-of-fold output
  * fallback model without road_conditions for days the disruption feed is not yet known
  * 500 Monte Carlo draws per route, simulated in route chunks (bounded memory)
"""
from __future__ import annotations

import time
import numpy as np
import pandas as pd
import lightgbm as lgb
from sklearn.isotonic import IsotonicRegression
from sklearn.model_selection import GroupKFold
from sklearn.metrics import log_loss

import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mc_task1 import Context, build_frame, CAT_COLS  # data loading + label construction

ROAD = ["disruption_index", "exp_travel", "cum_exp_excess"]          # need the road-condition feed
STATIC_ALL = ["brand", "district", "depot", "dock_type", "parking_constraint", "vehicle_type", "vehicle_temp",
              "temp_requirement", "outlet_id", "vehicle_id", "mall", "order_units", "order_weight_kg",
              "order_volume_m3", "seq", "n_stops", "seq_frac", "distance_km", "planned_travel_duration_min",
              "planned_depart_m", "planned_arrival_m", "wo", "wc", "plan_slack", "plan_wait", "speed_plan",
              "exp_travel", "disruption_index", "monsoon", "dow", "is_weekend", "is_payday", "pay_lag",
              "pay_lead", "festival_ramp", "is_holiday", "service_allowance_min", "route_order_units",
              "route_order_volume_m3", "route_order_weight_kg", "cum_units_before", "cum_plan_travel",
              "cum_exp_excess", "cum_allow_before", "route_start_m", "min_slack_before", "plan_slack_next"]
DEP_ALL = ["brand", "district", "depot", "vehicle_type", "vehicle_temp", "vehicle_id", "n_stops", "route_start_m",
           "route_order_units", "route_order_volume_m3", "route_order_weight_kg", "disruption_index", "monsoon",
           "dow", "is_weekend", "is_payday", "pay_lag", "pay_lead", "festival_ramp", "is_holiday"]
TRV_STATE = ["sim_dep_m", "sim_dep_delay", "speed_sim"]
SVC_STATE = ["sim_arr_m", "sim_lateby", "sim_wait", "sim_arr_delay"]


def feature_sets(use_road=True):
    keep = (lambda f: True) if use_road else (lambda f: f not in ROAD)
    static = [f for f in STATIC_ALL if keep(f)]
    return dict(static=static, dep=[f for f in DEP_ALL if keep(f)],
                trv=static + TRV_STATE, svc=static + SVC_STATE)


# ------------------------------------------------------------------ data
def prepare(orders, legs, ctx):
    """Label construction + features (mc_task1.build_frame), then id-like columns -> categoricals.
    Integer copies (brand_i, district_i) are kept for the simulator's own bookkeeping."""
    x = build_frame(orders, legs, ctx)
    x["brand_i"], x["district_i"] = x.brand.values.copy(), x.district.values.copy()
    for c in CAT_COLS:
        x[c] = pd.Categorical(x[c], categories=range(len(ctx.vocab[c])))
    return x


def lgbm(objective="regression", **kw):
    p = dict(objective=objective, learning_rate=0.05, n_estimators=2000, num_leaves=63, min_child_samples=40,
             subsample=0.8, subsample_freq=1, colsample_bytree=0.8, reg_lambda=1.0, cat_smooth=10,
             min_data_per_group=50, verbose=-1, n_jobs=-1)
    p.update(kw)
    return lgb.LGBMClassifier(**p) if objective == "binary" else lgb.LGBMRegressor(**p)


def time_split(dates, val_frac=0.15):
    order = np.argsort(dates, kind="stable"); k = int(len(order) * (1 - val_frac))
    return order[:k], order[k:]


def fit_es(model, X, y, dates, val_frac=0.15):
    """Early stopping on the latest 15% of dates, then refit on all rows with that many trees.
    The search history (train + validation curves) is kept on the model as `curve_`."""
    tr, va = time_split(dates, val_frac)
    m = type(model)(**model.get_params())
    m.fit(X.iloc[tr], y[tr], eval_set=[(X.iloc[tr], y[tr]), (X.iloc[va], y[va])],
          eval_names=["train", "validation"], callbacks=[lgb.early_stopping(100, verbose=False)])
    n = max(50, int(m.best_iteration_ * 1.05))
    model.set_params(n_estimators=n)
    model.fit(X, y)
    model.curve_ = {k: list(v.values())[0] for k, v in m.evals_result_.items()}
    model.best_iter_ = m.best_iteration_
    return model


# ------------------------------------------------------------------ simulator
class Component:
    """LightGBM point model + out-of-fold residual pool (per brand) + route random-effect sigma."""

    def __init__(self, feats):
        self.feats = feats

    def fit(self, X, y, route, dates, brand):
        base = lgbm(n_estimators=600)
        oof = np.zeros(len(y))
        for tr, va in GroupKFold(3).split(X, y, route):
            m = type(base)(**base.get_params()).fit(X.iloc[tr][self.feats], y[tr])
            oof[va] = m.predict(X.iloc[va][self.feats])
        self.model = fit_es(lgbm(), X[self.feats], y, dates)
        res = y - oof
        self.pools, self.sig_u = {}, {}
        df = pd.DataFrame({"r": res, "route": route, "b": brand})
        for b, d in df.groupby("b"):
            g = d.groupby("route").r
            n = g.transform("size"); multi = n >= 2
            within = ((d.r - g.transform("mean")) * np.sqrt(n / (n - 1).clip(lower=1)))[multi].values
            s2w = within.var() if len(within) > 50 else d.r.var()
            rm, rn = g.mean(), g.size()
            s2b = max(0.0, rm[rn >= 2].var() - (s2w / rn[rn >= 2]).mean()) if (rn >= 2).sum() > 30 else 0.0
            self.sig_u[b] = float(np.sqrt(s2b))
            self.pools[b] = (within if len(within) > 50 else (d.r - d.r.mean()).values).astype(np.float32)
        return self

    def predict(self, X):
        return self.model.predict(X[self.feats])


class Simulator:
    def __init__(self, ctx, n_draws=500, seed=0, use_road=True, chunk_rows=600_000):
        self.ctx, self.n, self.seed, self.use_road, self.chunk_rows = ctx, n_draws, seed, use_road, chunk_rows
        self.fs = feature_sets(use_road)

    def fit(self, x):
        fs, ctx = self.fs, self.ctx
        first = x[x.seq == 0]
        y = np.log1p((first.actual_depart_m - first.planned_depart_m).clip(lower=0).values)
        self.dep = Component(fs["dep"]).fit(first, y, first.route_id.values, first.date.values, first.brand_i.values)
        x = x.copy()
        x["sim_dep_m"] = x.actual_depart_m
        x["sim_dep_delay"] = x.actual_depart_m - x.planned_depart_m
        x["speed_sim"] = ctx.speed_lookup(np.array(ctx.vocab["district"])[x.district_i.values],
                                          x.monsoon.values, x.actual_depart_m.values)
        y = np.log(x.actual_travel_duration_min.clip(lower=1) / x.planned_travel_duration_min.clip(lower=1)).values
        self.trv = Component(fs["trv"]).fit(x, y, x.route_id.values, x.date.values, x.brand_i.values)
        x["sim_arr_m"] = x.arrival_m
        x["sim_lateby"] = x.arrival_m - x.wc
        x["sim_wait"] = (x.wo - x.arrival_m).clip(lower=0)
        x["sim_arr_delay"] = x.arrival_m - x.planned_arrival_m
        self.svc = Component(fs["svc"]).fit(x, np.log(x.svc.values), x.route_id.values, x.date.values,
                                            x.brand_i.values)
        return self

    def _noise(self, comp, brands, rng, u):
        out = np.empty((len(brands), self.n))
        for b in np.unique(brands):
            m = brands == b
            pool = comp.pools.get(b, next(iter(comp.pools.values())))
            out[m] = rng.choice(pool, size=(m.sum(), self.n))
        return out + u

    def simulate(self, x, keep_draws_for=None):
        """Roll every route forward n_draws times. Returns per-stop summaries
        (and raw arrival draws for the route ids in keep_draws_for)."""
        orig_index = x.index
        x = x.reset_index(drop=True)
        routes = x.route_id.unique()
        size = x.groupby("route_id", sort=False).size().reindex(routes).values
        # chunk whole routes so that (stops x draws) stays bounded
        bounds, acc, start = [], 0, 0
        for i, s in enumerate(size):
            acc += s * self.n
            if acc > self.chunk_rows:
                bounds.append((start, i + 1)); start, acc = i + 1, 0
        if start < len(routes):
            bounds.append((start, len(routes)))
        rng = np.random.default_rng(self.seed)
        parts, kept = [], {}
        rid = pd.Series(np.arange(len(routes)), index=routes)
        x_r = rid.loc[x.route_id].values
        for a, b in bounds:
            sub = x[(x_r >= a) & (x_r < b)]
            s, k = self._simulate_chunk(sub, rng, keep_draws_for)
            parts.append(s); kept.update(k)
        out = pd.concat(parts).loc[x.index].set_index(orig_index)
        return (out, kept) if keep_draws_for is not None else out

    def _simulate_chunk(self, x, rng, keep):
        N, ctx = self.n, self.ctx
        idx0 = x.index.values
        x = x.reset_index(drop=True)
        routes, r_idx = np.unique(x.route_id.values, return_inverse=True)
        R = len(routes)
        dnames = np.array(ctx.vocab["district"])[x.district_i.values]
        rb = np.zeros(R, dtype=int); rb[r_idx] = x.brand_i.values

        def effect(comp):
            return rng.standard_normal((R, N)) * np.array([comp.sig_u.get(b, 0.0) for b in rb])[:, None]

        u_trv, u_svc = effect(self.trv), effect(self.svc)
        first = x[x.seq == 0]
        delay = np.clip(np.expm1(self.dep.predict(first)[:, None] +
                                 self._noise(self.dep, first.brand_i.values, rng, 0.0)), 0, None)
        t = np.zeros((R, N))
        t[r_idx[first.index]] = first.planned_depart_m.values[:, None] + delay
        arr_all = np.zeros((len(x), N), np.float32); svc_all = np.zeros((len(x), N), np.float32)
        static = self.fs["static"]
        for s in range(int(x.seq.max()) + 1):
            rows = np.where(x.seq.values == s)[0]
            if not len(rows):
                continue
            ri = r_idx[rows]; dep = t[ri]
            X = x.iloc[np.repeat(rows, N)][static].reset_index(drop=True)
            X["sim_dep_m"] = dep.ravel()
            X["sim_dep_delay"] = (dep - x.planned_depart_m.values[rows, None]).ravel()
            X["speed_sim"] = ctx.speed_lookup(np.repeat(dnames[rows], N), np.repeat(x.monsoon.values[rows], N),
                                              dep.ravel())
            mu = self.trv.predict(X).reshape(-1, N)
            travel = x.planned_travel_duration_min.clip(lower=1).values[rows, None] * \
                np.exp(mu + self._noise(self.trv, x.brand_i.values[rows], rng, u_trv[ri]))
            arr = np.round(dep + travel)
            wo, wc = x.wo.values[rows, None], x.wc.values[rows, None]
            X["sim_arr_m"] = arr.ravel(); X["sim_lateby"] = (arr - wc).ravel()
            X["sim_wait"] = np.clip(wo - arr, 0, None).ravel()
            X["sim_arr_delay"] = (arr - x.planned_arrival_m.values[rows, None]).ravel()
            svc = np.exp(self.svc.predict(X).reshape(-1, N) +
                         self._noise(self.svc, x.brand_i.values[rows], rng, u_svc[ri]))
            t[ri] = np.maximum(arr, wo) + svc
            arr_all[rows], svc_all[rows] = arr, svc
        lateby = arr_all - x.wc.values[:, None]
        out = pd.DataFrame({"sim_p_late": (lateby > 0).mean(1), "sim_svc": svc_all.mean(1),
                            "sim_lateby_q10": np.quantile(lateby, .1, 1), "sim_lateby_q90": np.quantile(lateby, .9, 1)},
                           index=idx0)
        kept = {}
        if keep is not None:
            for r in set(keep) & set(routes):
                m = (x.route_id == r).values
                kept[r] = dict(arrivals=arr_all[m], seq=x.seq.values[m], wo=x.wo.values[m], wc=x.wc.values[m],
                               planned=x.planned_arrival_m.values[m])
        return out, kept


# ------------------------------------------------------------------ LightGBM branch
class LGBBranch:
    def __init__(self, use_road=True):
        self.feats = feature_sets(use_road)["static"]

    def fit(self, x):
        X, d = x[self.feats], x.date.values
        self.late = fit_es(lgbm("binary", learning_rate=0.03, num_leaves=31, min_child_samples=100), X, x.late.values, d)
        self.s2 = fit_es(lgbm("regression", learning_rate=0.03), X, x.svc.values, d)
        self.s1 = fit_es(lgbm("regression_l1", learning_rate=0.03), X, x.svc.values, d)
        return self

    def predict(self, x):
        X = x[self.feats]
        return self.late.predict_proba(X)[:, 1], 0.5 * self.s2.predict(X) + 0.5 * self.s1.predict(X)


# ------------------------------------------------------------------ combination
def logit(p):
    p = np.clip(p, 1e-4, 1 - 1e-4); return np.log(p / (1 - p))


def mix_late(p_sim, p_lgb, w):
    return 1 / (1 + np.exp(-(w * logit(p_sim) + (1 - w) * logit(p_lgb))))


def time_blocks(dates, k):
    ud = np.sort(pd.unique(dates)); m = {}
    for i, blk in enumerate(np.array_split(ud, k)):
        for d in blk:
            m[d] = i
    return pd.Series(dates).map(m).values


def oof_branches(x, ctx, k=5, n_draws=500, use_road=True, log=print):
    """Out-of-fold predictions of both branches: each time block is predicted by
    models that never saw it."""
    blk = time_blocks(x.date.values, k)
    out = pd.DataFrame(index=x.index, columns=["sim_late", "sim_svc", "lgb_late", "lgb_svc"], dtype=float)
    for b in range(k):
        t0 = time.time()
        tr, va = x[blk != b], x[blk == b]
        s = Simulator(ctx, n_draws, seed=b, use_road=use_road).fit(tr).simulate(va)
        pl, ps = LGBBranch(use_road).fit(tr).predict(va)
        out.loc[va.index, ["sim_late", "sim_svc"]] = s[["sim_p_late", "sim_svc"]].values
        out.loc[va.index, "lgb_late"], out.loc[va.index, "lgb_svc"] = pl, ps
        log(f"  block {b + 1}/{k}  {va.date.min()} .. {va.date.max()}  rows={len(va):,}  {time.time() - t0:.0f}s")
    out["block"] = blk
    return out


def search_weights(oof, y_late, y_svc, grid=np.round(np.linspace(0, 1, 21), 2)):
    ll = [log_loss(y_late, np.clip(mix_late(oof.sim_late, oof.lgb_late, w), 1e-3, 1 - 1e-3)) for w in grid]
    mae = [np.abs(w * oof.sim_svc + (1 - w) * oof.lgb_svc - y_svc).mean() for w in grid]
    tab = pd.DataFrame({"simulator_share": grid, "late_logloss": ll, "service_MAE": mae})
    return float(grid[int(np.argmin(ll))]), float(grid[int(np.argmin(mae))]), tab


def calibration_check(p_raw, y, blocks):
    """Nested check on dev: isotonic fitted on the other blocks, applied to each block."""
    p_cal = np.zeros(len(p_raw))
    for b in np.unique(blocks):
        m = blocks == b
        iso = IsotonicRegression(y_min=0.001, y_max=0.999, out_of_bounds="clip").fit(p_raw[~m], y[~m])
        p_cal[m] = iso.predict(p_raw[m])
    return p_cal


class ParallelModel:
    """Final, saveable model: primary (with road conditions) + fallback (without)."""

    def __init__(self, ctx, n_draws=500):
        self.ctx, self.n_draws, self.parts = ctx, n_draws, {}

    def add(self, name, sim, lgb_branch, w_late, w_svc, iso):
        self.parts[name] = dict(sim=sim, lgb=lgb_branch, w_late=w_late, w_svc=w_svc, iso=iso)
        return self

    def predict_frame(self, x, use_fallback=False):
        p = self.parts["fallback" if use_fallback else "primary"]
        s = p["sim"].simulate(x)
        pl, ps = p["lgb"].predict(x)
        raw = mix_late(s.sim_p_late.values, pl, p["w_late"])
        late = p["iso"].predict(raw) if p["iso"] is not None else raw
        return pd.DataFrame({"delivery_id": x.delivery_id.values,
                             "pred_service_min": np.clip(p["w_svc"] * s.sim_svc.values + (1 - p["w_svc"]) * ps, 1, None),
                             "pred_late_prob": np.clip(late, 0.001, 0.999)})

    def predict(self, orders, legs, use_fallback=False):
        x = prepare(orders, legs, self.ctx)
        return orders[["delivery_id"]].merge(self.predict_frame(x, use_fallback), on="delivery_id", how="left")


def component_frames(x, ctx, use_road=True):
    """Training frames of the simulator's travel and service components (actual states),
    used to draw their train / validation / hold-out error curves."""
    fs = feature_sets(use_road)
    x = x.copy()
    x["sim_dep_m"] = x.actual_depart_m
    x["sim_dep_delay"] = x.actual_depart_m - x.planned_depart_m
    x["speed_sim"] = ctx.speed_lookup(np.array(ctx.vocab["district"])[x.district_i.values],
                                      x.monsoon.values, x.actual_depart_m.values)
    x["sim_arr_m"] = x.arrival_m
    x["sim_lateby"] = x.arrival_m - x.wc
    x["sim_wait"] = (x.wo - x.arrival_m).clip(lower=0)
    x["sim_arr_delay"] = x.arrival_m - x.planned_arrival_m
    y_trv = np.log(x.actual_travel_duration_min.clip(lower=1) / x.planned_travel_duration_min.clip(lower=1)).values
    return {"Simulator: travel time": (x[fs["trv"]], y_trv),
            "Simulator: service time": (x[fs["svc"]], np.log(x.svc.values))}
