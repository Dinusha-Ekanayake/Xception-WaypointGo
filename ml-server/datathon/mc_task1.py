"""Task 1: Monte Carlo route simulator + ML corrector.

Pipeline
--------
1. Feature frame (only information known before departure).
2. Three LightGBM "physics" components learned from the training route records:
     DEP  : depot departure delay of a route           target log1p(actual_depart - planned_depart)
     TRV  : leg travel time                            target log(actual_travel / planned_travel)
     SVC  : service (handling) time at the outlet       target log(service_min)
   TRV and SVC also take *state* features (simulated departure clock, simulated
   arrival lateness / waiting) so the simulator can propagate delays.
3. Monte Carlo engine: every route is rolled forward N times. Noise = out-of-fold
   empirical residuals + a route-level random effect (residuals are correlated
   within a route). Output per stop: P(late), service distribution, arrival quantiles.
4. ML corrector: LightGBM models trained on out-of-fold simulator outputs + static
   features, producing the final pred_late_prob and pred_service_min.

Labels (see docs):
   service_min = leave_outlet_time - max(arrival_time, window_open_time)
   late        = arrival_time > window_close_time
"""
from __future__ import annotations

import time
import numpy as np
import pandas as pd
import lightgbm as lgb
from sklearn.model_selection import GroupKFold

GEN = "General Data/"

CAT_COLS = ["brand", "district", "depot", "dock_type", "parking_constraint", "vehicle_type",
            "vehicle_temp", "temp_requirement", "outlet_id", "vehicle_id", "mall"]


# --------------------------------------------------------------------------- utils
def hhmm(s: pd.Series) -> pd.Series:
    t = s.str.split(":", expand=True).astype(float)
    return t[0] * 60 + t[1]


USE_CAT = False          # True: id-like columns are native LightGBM categoricals, not ordered numbers
_CAT_LEVELS = range(256)  # fixed level set so training and inference always share the same categories


def _as_cat(X):
    X = X.copy()
    for c in CAT_COLS:
        if c in X.columns:
            X[c] = pd.Categorical(X[c], categories=_CAT_LEVELS)
    return X


class CatRegressor(lgb.LGBMRegressor):
    def fit(self, X, y, **kw):
        if "eval_set" in kw:
            kw["eval_set"] = [(_as_cat(a), b) for a, b in kw["eval_set"]]
        return super().fit(_as_cat(X), y, **kw)

    def predict(self, X, **kw):
        return super().predict(_as_cat(X), **kw)


class CatClassifier(lgb.LGBMClassifier):
    def fit(self, X, y, **kw):
        if "eval_set" in kw:
            kw["eval_set"] = [(_as_cat(a), b) for a, b in kw["eval_set"]]
        return super().fit(_as_cat(X), y, **kw)

    def predict_proba(self, X, **kw):
        return super().predict_proba(_as_cat(X), **kw)


def lgbm(objective="regression", **kw):
    p = dict(objective=objective, learning_rate=0.05, n_estimators=2000, num_leaves=63,
             min_child_samples=40, subsample=0.8, subsample_freq=1, colsample_bytree=0.8,
             reg_lambda=1.0, verbose=-1, n_jobs=-1)
    p.update(kw)
    if USE_CAT:
        p.update(cat_smooth=10, min_data_per_group=50)
        return CatClassifier(**p) if objective == "binary" else CatRegressor(**p)
    return lgb.LGBMClassifier(**p) if objective == "binary" else lgb.LGBMRegressor(**p)


def fit_es(model, X, y, groups_time, val_frac=0.15, cat=None):
    """Fit with early stopping on the last `val_frac` of time, then refit on all
    rows with the found number of trees (no information from the future leaks
    into the stopping rule)."""
    order = np.argsort(groups_time, kind="stable")
    cut = order[: int(len(order) * (1 - val_frac))], order[int(len(order) * (1 - val_frac)):]
    m = type(model)(**model.get_params())
    m.fit(X.iloc[cut[0]], y[cut[0]], eval_set=[(X.iloc[cut[1]], y[cut[1]])],
          callbacks=[lgb.early_stopping(100, verbose=False)])
    n = max(50, int(m.best_iteration_ * 1.05))
    model.set_params(n_estimators=n)
    model.fit(X, y)
    return model


# --------------------------------------------------------------------------- data
class Context:
    """Reference tables, loaded once."""

    def __init__(self, data_dir: str):
        g = data_dir + GEN
        self.outlets = pd.read_csv(g + "outlets.csv")
        self.cal = pd.read_csv(g + "calendar.csv")
        self.rc = pd.read_csv(g + "road_conditions.csv")
        self.ts = pd.read_csv(g + "traffic_speed.csv")
        self.sa = pd.read_csv(g + "service_allowance.csv")
        self.dt = pd.read_csv(g + "district_travel.csv")
        cal = self.cal.copy()
        cal["d"] = pd.to_datetime(cal.date)
        pay = cal.loc[cal.is_payday == 1, "d"].values
        idx = np.searchsorted(pay, cal.d.values, side="right") - 1
        lag = (cal.d.values - pay[np.clip(idx, 0, None)]).astype("timedelta64[D]").astype(int)
        cal["pay_lag"] = np.where(idx >= 0, np.minimum(lag, 10), 10)
        nxt = np.searchsorted(pay, cal.d.values, side="left")
        lead = (pay[np.clip(nxt, 0, len(pay) - 1)] - cal.d.values).astype("timedelta64[D]").astype(int)
        cal["pay_lead"] = np.where(nxt < len(pay), np.minimum(lead, 10), 10)
        self.calf = cal[["date", "is_weekend", "is_payday", "pay_lag", "pay_lead", "festival_ramp",
                         "is_holiday"]]
        # speed index lookup [district][monsoon][hour]
        self.speed = {(r.district, r.monsoon, r.hour): r.speed_index for r in self.ts.itertuples()}
        self.vocab = {
            "brand": ["Fresh", "Style", "Tech"],
            "district": sorted(self.dt.district.unique()),
            "depot": ["Kandy", "Peliyagoda"],
            "dock_type": ["mall_bay", "rear_dock", "street"],
            "parking_constraint": ["mall_dock", "normal", "van_only"],
            "vehicle_type": ["truck", "van"],
            "vehicle_temp": ["ambient", "reefer"],
            "temp_requirement": ["ambient", "chilled"],
            "outlet_id": sorted(self.outlets.outlet_id),
            "vehicle_id": [f"VEH{i:03d}" for i in range(1, 61)],
            "mall": [0, 1],
        }

    def speed_lookup(self, district: np.ndarray, monsoon: np.ndarray, minute: np.ndarray) -> np.ndarray:
        hour = (np.asarray(minute) // 60).astype(int) % 24
        return np.array([self.speed.get((d, m, h), 100.0) for d, m, h in zip(district, monsoon, hour)])


def build_frame(orders: pd.DataFrame, legs: pd.DataFrame, ctx: Context) -> pd.DataFrame:
    """One row per delivered stop, static (pre-departure) features + labels if present."""
    keep = ["delivery_id", "route_id", "seq_in_route", "outlet_id", "temp_requirement", "order_units",
            "order_weight_kg", "order_volume_m3", "window_open_time", "window_close_time"]
    o = orders.loc[orders.route_id.notna(), keep].rename(columns={"seq_in_route": "seq"})
    o["seq"] = o.seq.astype(int)
    x = o.merge(legs, on=["route_id", "seq"], how="left", validate="1:1")
    x = x.merge(ctx.outlets[["outlet_id", "dock_type", "parking_constraint", "mall_window"]], on="outlet_id")
    x = x.merge(ctx.calf, on="date", how="left")
    x = x.merge(ctx.rc, on=["district", "date"], how="left")
    x["disruption_index"] = x.disruption_index.fillna(100.0)
    x = x.merge(ctx.sa, on=["brand", "dock_type"], how="left")
    x["mall"] = x.mall_window.notna().astype(int)
    x["dow"] = pd.to_datetime(x.date).dt.dayofweek
    x["doy"] = pd.to_datetime(x.date).dt.dayofyear

    for c in ["planned_depart_time", "planned_arrival_time", "window_open_time", "window_close_time"]:
        x[c[:-5] + "_m"] = hhmm(x[c])
    x["wo"], x["wc"] = x.window_open_m, x.window_close_m
    x["plan_slack"] = x.wc - x.planned_arrival_m            # minutes of slack in the plan
    x["plan_wait"] = (x.wo - x.planned_arrival_m).clip(lower=0)
    x["speed_plan"] = ctx.speed_lookup(x.district.values, x.monsoon.values, x.planned_depart_m.values)
    x["exp_travel"] = x.planned_travel_duration_min * 100 / x.speed_plan * 100 / x.disruption_index

    x = x.sort_values(["route_id", "seq"]).reset_index(drop=True)
    g = x.groupby("route_id")
    x["n_stops"] = g.seq.transform("size")
    x["seq_frac"] = x.seq / x.n_stops
    for c in ["order_units", "order_volume_m3", "order_weight_kg"]:
        x["route_" + c] = g[c].transform("sum")
    x["cum_units_before"] = g.order_units.cumsum() - x.order_units
    x["cum_plan_travel"] = g.planned_travel_duration_min.cumsum()
    x["cum_exp_excess"] = g.exp_travel.cumsum() - x.cum_plan_travel   # expected traffic overrun so far
    x["cum_allow_before"] = g.service_allowance_min.cumsum() - x.service_allowance_min
    x["route_start_m"] = g.planned_depart_m.transform("first")
    x["min_slack_before"] = g.plan_slack.cummin()
    x["plan_slack_next"] = g.plan_slack.shift(-1).fillna(999)

    # labels (training data only)
    if "arrival_time" in x and x.arrival_time.notna().all():
        for c in ["actual_depart_time", "arrival_time", "leave_outlet_time"]:
            x[c[:-5] + "_m"] = hhmm(x[c])
        x["svc"] = x.leave_outlet_m - np.maximum(x.arrival_m, x.wo)
        x["late"] = (x.arrival_m > x.wc).astype(int)
    for c in CAT_COLS:
        x[c] = pd.Categorical(x[c], categories=ctx.vocab[c]).codes
    return x


STATIC = ["brand", "district", "depot", "dock_type", "parking_constraint", "vehicle_type", "vehicle_temp",
          "temp_requirement", "outlet_id", "vehicle_id", "mall", "order_units", "order_weight_kg",
          "order_volume_m3", "seq", "n_stops", "seq_frac", "distance_km", "planned_travel_duration_min",
          "planned_depart_m", "planned_arrival_m", "wo", "wc", "plan_slack", "plan_wait", "speed_plan",
          "exp_travel", "disruption_index", "monsoon", "dow", "doy", "is_weekend", "is_payday", "pay_lag",
          "pay_lead", "festival_ramp", "is_holiday", "service_allowance_min", "route_order_units",
          "route_order_volume_m3", "route_order_weight_kg", "cum_units_before", "cum_plan_travel",
          "cum_exp_excess", "cum_allow_before", "route_start_m", "min_slack_before", "plan_slack_next"]
DEP_F = ["brand", "district", "depot", "vehicle_type", "vehicle_temp", "vehicle_id", "n_stops",
         "route_start_m", "route_order_units", "route_order_volume_m3", "route_order_weight_kg",
         "disruption_index", "monsoon", "dow", "doy", "is_weekend", "is_payday", "pay_lag", "pay_lead",
         "festival_ramp", "is_holiday"]
TRV_STATE = ["sim_dep_m", "sim_dep_delay", "speed_sim"]
SVC_STATE = ["sim_arr_m", "sim_lateby", "sim_wait", "sim_arr_delay"]
TRV_F = STATIC + TRV_STATE
SVC_F = STATIC + SVC_STATE


# --------------------------------------------------------------------------- components
class Component:
    """LightGBM point model + out-of-fold residual pool + route random-effect sigma."""

    def __init__(self, feats, by_brand=True):
        self.feats, self.by_brand = feats, by_brand

    def fit(self, X: pd.DataFrame, y: np.ndarray, route: np.ndarray, time_key: np.ndarray, brand: np.ndarray):
        base = lgbm(n_estimators=600, learning_rate=0.05)
        # 3-fold grouped OOF residuals -> honest noise distribution for the simulator
        oof = np.zeros(len(y))
        for tr, va in GroupKFold(3).split(X, y, route):
            m = type(base)(**base.get_params())
            m.fit(X.iloc[tr][self.feats], y[tr])
            oof[va] = m.predict(X.iloc[va][self.feats])
        self.model = fit_es(lgbm(), X[self.feats], y, time_key)
        res = y - oof
        self.pools, self.sig_u = {}, {}
        df = pd.DataFrame({"r": res, "route": route, "b": brand if self.by_brand else 0})
        for b, d in df.groupby("b"):
            gm = d.groupby("route").r.transform("mean")
            n = d.groupby("route").r.transform("size")
            multi = n >= 2
            # within-route deviations, de-biased for the lost degree of freedom
            within = ((d.r - gm) * np.sqrt(n / (n - 1).clip(lower=1)))[multi].values
            s2_within = within.var() if len(within) > 50 else d.r.var()
            rm = d.groupby("route").r.mean()
            rn = d.groupby("route").r.size()
            s2_between = max(0.0, rm[rn >= 2].var() - (s2_within / rn[rn >= 2]).mean()) if (rn >= 2).sum() > 30 else 0.0
            self.sig_u[b] = float(np.sqrt(s2_between))
            pool = within if len(within) > 50 else (d.r - d.r.mean()).values
            self.pools[b] = pool.astype(np.float32)
        self.resid_mean = float(res.mean())
        return self

    def predict(self, X):
        return self.model.predict(X[self.feats])


# --------------------------------------------------------------------------- simulator
class MonteCarloSimulator:
    def __init__(self, ctx: Context, n_draws=100, seed=0):
        self.ctx, self.n, self.seed = ctx, n_draws, seed

    def fit(self, x: pd.DataFrame):
        t0 = time.time()
        first = x[x.seq == 0]
        dep_y = np.log1p((first.actual_depart_m - first.planned_depart_m).clip(lower=0).values)
        self.dep = Component(DEP_F).fit(first, dep_y, first.route_id.values, first.date.values, first.brand.values)

        x = x.copy()
        x["sim_dep_m"] = x.actual_depart_m
        x["sim_dep_delay"] = x.actual_depart_m - x.planned_depart_m
        x["speed_sim"] = self.ctx.speed_lookup(np.array(self.ctx.vocab["district"])[x.district.values],
                                               x.monsoon.values, x.actual_depart_m.values)
        trv_y = np.log(x.actual_travel_duration_min.clip(lower=1) / x.planned_travel_duration_min.clip(lower=1)).values
        self.trv = Component(TRV_F).fit(x, trv_y, x.route_id.values, x.date.values, x.brand.values)

        x["sim_arr_m"] = x.arrival_m
        x["sim_lateby"] = x.arrival_m - x.wc
        x["sim_wait"] = (x.wo - x.arrival_m).clip(lower=0)
        x["sim_arr_delay"] = x.arrival_m - x.planned_arrival_m
        self.svc = Component(SVC_F).fit(x, np.log(x.svc.values), x.route_id.values, x.date.values, x.brand.values)
        self.fit_seconds = time.time() - t0
        return self

    # ---- one-step noise
    def _noise(self, comp, brand_codes, rng, u):
        out = np.empty((len(brand_codes), self.n), dtype=np.float64)
        for b in np.unique(brand_codes):
            m = brand_codes == b
            pool = comp.pools.get(b, next(iter(comp.pools.values())))
            out[m] = rng.choice(pool, size=(m.sum(), self.n))
        return out + u

    def simulate(self, x: pd.DataFrame) -> pd.DataFrame:
        """x: frame from build_frame (sorted by route, seq). Returns per-stop simulator features."""
        rng = np.random.default_rng(self.seed)
        N = self.n
        x = x.reset_index(drop=True)
        routes, r_idx = np.unique(x.route_id.values, return_inverse=True)
        R = len(routes)
        district_names = np.array(self.ctx.vocab["district"])[x.district.values]
        rb = np.zeros(R, dtype=int)
        rb[r_idx] = x.brand.values

        def route_effect(comp):
            sig = np.array([comp.sig_u.get(b, 0.0) for b in rb])
            return rng.standard_normal((R, N)) * sig[:, None]

        u_trv, u_svc = route_effect(self.trv), route_effect(self.svc)

        # departure from depot
        first = x[x.seq == 0]
        mu = self.dep.predict(first)
        eps = self._noise(self.dep, first.brand.values, rng, 0.0)
        delay = np.clip(np.expm1(mu[:, None] + eps), 0, None)
        t = np.zeros((R, N))
        t[r_idx[first.index]] = first.planned_depart_m.values[:, None] + delay

        arr_all = np.zeros((len(x), N), dtype=np.float32)
        svc_all = np.zeros((len(x), N), dtype=np.float32)
        for s in range(int(x.seq.max()) + 1):
            rows = np.where(x.seq.values == s)[0]
            if len(rows) == 0:
                continue
            ri = r_idx[rows]
            dep = t[ri]                                               # (k, N)
            X = x.iloc[np.repeat(rows, N)][STATIC].reset_index(drop=True)
            X["sim_dep_m"] = dep.ravel()
            X["sim_dep_delay"] = (dep - x.planned_depart_m.values[rows, None]).ravel()
            X["speed_sim"] = self.ctx.speed_lookup(np.repeat(district_names[rows], N),
                                                   np.repeat(x.monsoon.values[rows], N), dep.ravel())
            mu = self.trv.predict(X).reshape(-1, N)
            eps = self._noise(self.trv, x.brand.values[rows], rng, u_trv[ri])
            travel = x.planned_travel_duration_min.clip(lower=1).values[rows, None] * np.exp(mu + eps)
            arr = np.round(dep + travel)
            wo, wc = x.wo.values[rows, None], x.wc.values[rows, None]
            X["sim_arr_m"] = arr.ravel()
            X["sim_lateby"] = (arr - wc).ravel()
            X["sim_wait"] = np.clip(wo - arr, 0, None).ravel()
            X["sim_arr_delay"] = (arr - x.planned_arrival_m.values[rows, None]).ravel()
            mu = self.svc.predict(X).reshape(-1, N)
            eps = self._noise(self.svc, x.brand.values[rows], rng, u_svc[ri])
            svc = np.exp(mu + eps)
            t[ri] = np.maximum(arr, wo) + svc
            arr_all[rows], svc_all[rows] = arr, svc

        wc = x.wc.values[:, None]
        lateby = arr_all - wc
        q = np.quantile(lateby, [0.1, 0.5, 0.9], axis=1)
        out = pd.DataFrame({
            "sim_p_late": (lateby > 0).mean(1),
            "sim_lateby_q10": q[0], "sim_lateby_q50": q[1], "sim_lateby_q90": q[2],
            "sim_lateby_mean": lateby.mean(1), "sim_lateby_std": lateby.std(1),
            "sim_p_wait": (arr_all < x.wo.values[:, None]).mean(1),
            "sim_svc_mean": svc_all.mean(1), "sim_svc_med": np.median(svc_all, 1),
            "sim_svc_logmean": np.log(svc_all).mean(1), "sim_svc_std": svc_all.std(1),
            "sim_arr_delay_mean": (arr_all - x.planned_arrival_m.values[:, None]).mean(1),
        }, index=x.index)
        return out


SIM_F = ["sim_p_late", "sim_lateby_q10", "sim_lateby_q50", "sim_lateby_q90", "sim_lateby_mean",
         "sim_lateby_std", "sim_p_wait", "sim_svc_mean", "sim_svc_med", "sim_svc_logmean", "sim_svc_std",
         "sim_arr_delay_mean"]
CORR_F = STATIC + SIM_F


# --------------------------------------------------------------------------- full model
def time_blocks(dates: pd.Series, k: int) -> np.ndarray:
    ud = np.sort(dates.unique())
    edges = np.array_split(ud, k)
    m = {d: i for i, blk in enumerate(edges) for d in blk}
    return dates.map(m).values


def oof_sim_features(x: pd.DataFrame, ctx: Context, k=5, n_draws=100, log=print) -> pd.DataFrame:
    """Simulator features for every row of x, each produced by a simulator that
    never saw that row's time block (prevents the corrector from learning on
    leaked, over-confident simulator output)."""
    blk = time_blocks(x.date, k)
    out = []
    for b in range(k):
        t0 = time.time()
        tr, va = x[blk != b], x[blk == b]
        sim = MonteCarloSimulator(ctx, n_draws, seed=b).fit(tr)
        out.append(sim.simulate(va).set_index(va.index))
        log(f"   OOF block {b + 1}/{k}: {va.date.min()}..{va.date.max()}  rows={len(va)}  {time.time() - t0:.0f}s")
    return pd.concat(out).loc[x.index]


class Task1Model:
    """Serialisable end-to-end Task 1 model (saved with joblib)."""

    def __init__(self, n_draws=100, k_oof=5):
        self.n_draws, self.k_oof = n_draws, k_oof

    def fit(self, x: pd.DataFrame, ctx: Context, log=print, sim_oof: pd.DataFrame | None = None):
        if sim_oof is None:
            sim_oof = oof_sim_features(x, ctx, self.k_oof, self.n_draws, log)
        self.sim_oof_ = sim_oof
        Z = pd.concat([x[STATIC], sim_oof], axis=1)
        tkey = x.date.values
        self.late_model = fit_es(lgbm("binary", learning_rate=0.03, num_leaves=31, min_child_samples=100),
                                 Z[CORR_F], x.late.values, tkey)
        self.svc_l2 = fit_es(lgbm("regression", learning_rate=0.03, num_leaves=63), Z[CORR_F], x.svc.values, tkey)
        self.svc_l1 = fit_es(lgbm("regression_l1", learning_rate=0.03, num_leaves=63), Z[CORR_F], x.svc.values, tkey)
        log("   corrector trees: late=%d svc_l2=%d svc_l1=%d" % (self.late_model.n_estimators,
                                                                 self.svc_l2.n_estimators, self.svc_l1.n_estimators))
        self.simulator = MonteCarloSimulator(ctx, self.n_draws, seed=123).fit(x)
        self.ctx = ctx
        return self

    def predict_parts(self, x: pd.DataFrame) -> pd.DataFrame:
        sim = self.simulator.simulate(x)
        Z = pd.concat([x[STATIC].reset_index(drop=True), sim.reset_index(drop=True)], axis=1)
        p = self.late_model.predict_proba(Z[CORR_F])[:, 1]
        s2, s1 = self.svc_l2.predict(Z[CORR_F]), self.svc_l1.predict(Z[CORR_F])
        out = sim.reset_index(drop=True)
        out["delivery_id"] = x.delivery_id.values
        out["pred_late_prob"] = np.clip(p, 0.001, 0.999)
        out["svc_l2"], out["svc_l1"] = s2, s1
        out["pred_service_min"] = np.clip(0.5 * s2 + 0.5 * s1, 1, None)
        return out

    def predict(self, orders: pd.DataFrame, legs: pd.DataFrame) -> pd.DataFrame:
        x = build_frame(orders, legs, self.ctx)
        p = self.predict_parts(x)[["delivery_id", "pred_service_min", "pred_late_prob"]]
        return orders[["delivery_id"]].merge(p, on="delivery_id", how="left")
