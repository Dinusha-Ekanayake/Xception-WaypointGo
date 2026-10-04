"""Agent-B Monte-Carlo route simulator (the core of the lateness model).

Generator reverse-engineered from the training routes:
  * route start delay  = actual_depart - planned_depart at seq 0 ~ empirical distribution (mean 7.7 min), i.i.d. per route
  * travel             = planned x 100/speed_index[district, hour(actual depart), monsoon] x 100/disruption[district, date]
                         x dow factor x exp(eps),  eps ~ district-specific empirical residuals (hill districts ~0.35 sd)
  * arrival            = depart + travel;  service starts at max(arrival, window_open)
  * log service        = parametric outlet model (outlet FE, log units/volume/weight, dow, monsoon, payday, holiday,
                         chilled, van, trend, festival-specific ramps) + beta_L * late + route effect + empirical residual
  * next depart        = service start + service (delay propagates along the route)
P(late) = share of simulated paths whose arrival exceeds window_close.
"""
import numpy as np
import pandas as pd

DOW = [0, 1, 2, 3, 4, 5]
SVC_COLS = ['lu', 'lv', 'lw', 'monsoon', 'is_payday', 'is_holiday', 'chilled', 'van', 'trend']


class SpeedTable:
    def __init__(self, ts):
        self.DI = {d: i for i, d in enumerate(sorted(ts.district.unique()))}
        self.SPD = np.zeros((len(self.DI), 24, 2))
        for r in ts.itertuples():
            self.SPD[self.DI[r.district], r.hour, r.monsoon] = r.speed_index

    def idx(self, districts):
        return np.vectorize(self.DI.get)(np.asarray(districts))

    def lookup(self, didx, t, mon):
        h = (np.floor(np.asarray(t) / 60).astype(int)) % 24
        return self.SPD[didx, h, np.asarray(mon)]


class RouteSimulator:
    def __init__(self, outlets, festivals, speed_table):
        self.outlets = list(outlets); self.fests = list(festivals); self.st = speed_table

    def design(self, df, late=None):
        M = [(df.outlet_id == o).values.astype(float) for o in self.outlets]
        M += [(df.dow == dw).values.astype(float) for dw in DOW[1:]]
        M += [df[c].values.astype(float) for c in SVC_COLS + ['fr_' + f for f in self.fests]]
        M.append(np.zeros(len(df)) if late is None else np.asarray(late, float))
        return np.column_stack(M)

    def fit(self, tr):
        A = self.design(tr, tr.late.values); y = np.log(tr.svc.values)
        self.beta = np.linalg.lstsq(A, y, rcond=None)[0]; self.bL = self.beta[-1]
        res = y - A @ self.beta
        rm = pd.Series(res).groupby(tr.route_id.values).agg(['mean', 'count'])
        self.var_r = max(0, rm['mean'].var() - (res.var() / rm['count']).mean())     # route-level random effect
        self.svc_res = res; self.sd_res = res.std()
        base = tr.planned_travel_duration_min * 100 / tr.disruption_index
        spd = self.st.lookup(self.st.idx(tr.district.values), tr.actual_depart_time_m.values, tr.monsoon.values)
        lr = np.log(tr.actual_travel_duration_min.clip(lower=0.5) / (base * 100 / spd))
        self.dow_tr = pd.Series(lr).groupby(tr.dow.values).mean().to_dict()
        self.tr_res = (lr - tr.dow.map(self.dow_tr)).values
        self.tr_res_d = {self.st.DI[d]: self.tr_res[(tr.district == d).values] for d in tr.district.unique()}
        f = tr[tr.seq == 0]; self.dd = (f.actual_depart_time_m - f.pdep).values
        return self

    def mu0(self, df):
        return self.design(df, None) @ self.beta

    def run(self, df, N=300, seed=0):
        rng = np.random.default_rng(seed)
        df = df.sort_values(['route_id', 'seq']); idx = df.index.values
        mu0 = self.mu0(df); n = len(df)
        rid = df.route_id.values; seq = df.seq.values
        routes, rinv = np.unique(rid, return_inverse=True); R = len(routes)
        dep = np.zeros((R, N))
        reff = rng.normal(0, np.sqrt(self.var_r), (R, N))
        scale = np.sqrt(max(1e-6, 1 - self.var_r / self.sd_res ** 2))
        arr_out = np.zeros((n, N)); svc_out = np.zeros((n, N))
        dist = self.st.idx(df.district.values); mon = df.monsoon.values
        base = (df.planned_travel_duration_min * 100 / df.disruption_index * np.exp(df.dow.map(self.dow_tr))).values
        pdep = df.pdep.values; wo = df.wo.values; wc = df.wc.values
        for k in range(seq.max() + 1):
            ii = np.where(seq == k)[0]; rr = rinv[ii]
            if k == 0:
                dep[rr] = pdep[ii][:, None] + rng.choice(self.dd, (len(ii), N))
            d = dep[rr]
            h = (np.floor(d / 60).astype(int)) % 24
            sp = self.st.SPD[dist[ii][:, None], h, mon[ii][:, None]]
            eps = np.zeros((len(ii), N))
            for dd_ in np.unique(dist[ii]):
                jj = np.where(dist[ii] == dd_)[0]; eps[jj] = rng.choice(self.tr_res_d[dd_], (len(jj), N))
            tt = base[ii][:, None] * 100 / sp * np.exp(eps)
            a = np.round(d + tt)
            late = a > wc[ii][:, None]
            ls = mu0[ii][:, None] + self.bL * late + reff[rr] + scale * rng.choice(self.svc_res, (len(ii), N))
            sv = np.maximum(1, np.round(np.exp(ls)))
            dep[rr] = np.maximum(a, wo[ii][:, None]) + sv
            arr_out[ii] = a; svc_out[ii] = sv
        out = pd.DataFrame({'p_sim': (arr_out > wc[:, None]).mean(1), 'svc_sim_med': np.median(svc_out, 1),
                            'svc_sim_mean': svc_out.mean(1), 'arr_sim_mean': arr_out.mean(1),
                            'arr_sim_q90': np.quantile(arr_out, .9, axis=1), 'mu0': mu0}, index=idx)
        out['sim_margin'] = df.wc.values - out.arr_sim_mean.values
        return out.loc[df.index]
