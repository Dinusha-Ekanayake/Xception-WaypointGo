"""Task 2A final model: bottom-up weekly demand = deterministic order schedule x expected volume per order.

Demand history (booklet rule: count every order once, including deferred and never-dispatched orders, in the ISO week of
its order_date) = deliveries_train + task1_test_inputs + the never-run orders of the test period, which are absent from
task1_test_inputs ("all were dispatched") and are inferred from the gaps in its delivery_id sequence.

Model (fitted per segment: Fresh ambient, Fresh chilled, Style, Tech):
  log(order_volume_m3) = outlet effect + trend + weekday + payday (day 0, days 1-2) + monsoon
                         + annual Fourier pair (Fresh) / seasonal-week dummies (Style) + one ramp per festival + noise
  E[volume]            = exp(linear predictor) x Duan smearing factor
  Fresh chilled is fitted by weighted least squares with weights halving every CHILLED_HALF_LIFE_YEARS back from the
  end of the history: the chilled order size drifts, and recent orders describe it best (25-origin backtest: chilled
  WAPE 2.17% -> 2.12%, better at 24 of 25 origins; total better at 23 of 25).
Order schedule (from calendar.is_operating; closed days lose their orders, they are not moved):
  Fresh ambient : every Fresh outlet on every operating day
  Fresh chilled : each outlet on its own fixed weekdays
  Style         : each outlet on its one fixed weekday
  Tech          : at most one order per outlet-day, expected count = outlet x weekday rate
Weekly forecast = sum over scheduled orders of expected count x E[volume]; chilled = the Fresh chilled segment.

The fitted model holds only coefficients and schedule tables (plain Python containers), so the saved file contains no
raw data and loads with a normal import of this module. The calendar and outlet tables are passed in at prediction time.
"""
import re
import numpy as np
import pandas as pd
import statsmodels.formula.api as smf

FESTS = ['new_year', 'vesak', 'poson', 'esala', 'deepavali', 'christmas', 'thai_pongal']
STYLE_PEAK_WEEKS = (10, 15, 32)          # recurring Style peaks (ISO weeks), visible in every year of the history
MIN_CHILLED_DAY_COUNT = 5                # a weekday is a chilled delivery day for an outlet if seen more than this often
SEGMENTS = ['Fresh_ambient', 'Fresh_chilled', 'Style', 'Tech']
SEG_BASE = {'Fresh_ambient': ['C(outlet_id)', 't_years', 'C(dow)', 'pay0', 'pay12', 'monsoon', 's1', 'c1'],
            'Fresh_chilled': ['C(outlet_id)', 't_years', 'C(dow)', 'pay0', 'pay12', 'monsoon', 's1', 'c1'],
            'Style': ['C(outlet_id)', 't_years', 'pay0', 'pay12', 'monsoon'] + [f'sw{w}' for w in STYLE_PEAK_WEEKS],
            'Tech': ['C(outlet_id)', 't_years', 'C(dow)', 'pay0', 'pay12', 'monsoon']}
RECOVERED_STATUS = 'not_run (recovered)'
ROUND_DECIMALS = 3
CHILLED_HALF_LIFE_YEARS = 1.0          # recency weighting of the Fresh chilled volume regression (see docstring)
KEYS = ['depot', 'brand', 'iso_year', 'iso_week']


# ------------------------------------------------------------------ calendar features (calendar.csv only, known in advance)
def calendar_features(cal: pd.DataFrame) -> pd.DataFrame:
    c = cal.copy()
    c['dtm'] = pd.to_datetime(c.date)
    fest = c.loc[c.festival.notna(), ['dtm', 'festival']].reset_index(drop=True)
    fd = fest.dtm.values
    i = np.searchsorted(fd, c.dtm.values, side='left')
    c['next_fest'] = [fest.festival.iloc[k] if k < len(fest) else 'none' for k in i]
    pays = c.dtm[c.is_payday == 1].values
    dd = (c.dtm.values[:, None] - pays[None, :]) / np.timedelta64(1, 'D')
    c['d_from_pay'] = dd[np.arange(len(c)), np.abs(dd).argmin(axis=1)].astype(int)       # signed days to nearest payday
    c['t_years'] = (c.dtm - pd.Timestamp('2024-01-01')).dt.days / 365.25
    doy = c.dtm.dt.dayofyear
    c['s1'] = np.sin(2 * np.pi * doy / 365.25)
    c['c1'] = np.cos(2 * np.pi * doy / 365.25)
    c['pay0'] = (c.d_from_pay == 0).astype(int)
    c['pay12'] = c.d_from_pay.between(1, 2).astype(int)
    for f in FESTS:
        c['ramp_' + f] = np.where(c.next_fest == f, c.festival_ramp, 0.0)
    for w in STYLE_PEAK_WEEKS:
        c[f'sw{w}'] = (c.iso_week == w).astype(int)
    return c


# ------------------------------------------------------------------ demand history
def recover_hidden_orders(test_orders: pd.DataFrame, outlets: pd.DataFrame) -> pd.DataFrame:
    """Never-run orders of the test period. delivery_ids are consecutive integers sorted by (order_date, outlet,
    temperature) with no gap in the training file, so every missing id inside the test range is an order that never ran.
    Its date and outlet follow from its neighbours: a gap right after an outlet's ambient order, before the next outlet
    or date, is that outlet's chilled order; otherwise it is the ambient order of the outlet that follows."""
    t = test_orders.assign(n=test_orders.delivery_id.str[3:].astype(int)).sort_values('n').set_index('n')
    gaps = np.setdiff1d(np.arange(t.index.min(), t.index.max() + 1), t.index.values)
    rows = []
    for gap in gaps:
        prv, nxt = t.loc[:gap - 1].iloc[-1], t.loc[gap + 1:].iloc[0]
        if prv.temp_requirement == 'ambient' and (nxt.outlet_id != prv.outlet_id or nxt.order_date != prv.order_date):
            oid, dte, tmp = prv.outlet_id, prv.order_date, 'chilled'
        else:
            oid, dte, tmp = nxt.outlet_id, nxt.order_date, 'ambient'
        rows.append({'delivery_id': f'ORD{gap:07d}', 'order_date': dte, 'outlet_id': oid, 'temp_requirement': tmp,
                     'dispatch_status': RECOVERED_STATUS})
    cols = ['delivery_id', 'order_date', 'outlet_id', 'temp_requirement', 'dispatch_status']
    hid = pd.DataFrame(rows, columns=cols).merge(outlets[['outlet_id', 'brand', 'district', 'depot']], on='outlet_id', how='left')
    hid['order_volume_m3'] = np.nan
    return hid


def build_history(dtr: pd.DataFrame, dte: pd.DataFrame, outlets: pd.DataFrame) -> pd.DataFrame:
    """Every order once: training orders + test-period orders + inferred never-run orders (volume unknown -> NaN)."""
    cols = ['delivery_id', 'order_date', 'dispatch_status', 'outlet_id', 'brand', 'district', 'depot', 'temp_requirement',
            'order_volume_m3']
    hid = recover_hidden_orders(dte, outlets)
    o = pd.concat([dtr[cols], dte[cols], hid[cols]], ignore_index=True)
    assert o.delivery_id.is_unique, 'an order must be counted once'
    o['recovered'] = o.dispatch_status == RECOVERED_STATUS
    return o


def weekly_actuals(orders: pd.DataFrame, cal: pd.DataFrame) -> pd.DataFrame:
    """Weekly depot x brand totals of orders with a known volume: v = total m3, ch = chilled m3, n = orders."""
    o = orders[orders.order_volume_m3.notna()].copy()
    o['chilled_v'] = np.where(o.temp_requirement == 'chilled', o.order_volume_m3, 0.0)
    o = o.merge(cal[['date', 'iso_year', 'iso_week']], left_on='order_date', right_on='date', how='left')
    return o.groupby(KEYS).agg(v=('order_volume_m3', 'sum'), ch=('chilled_v', 'sum'), n=('delivery_id', 'size')).reset_index()


# ------------------------------------------------------------------ model
def seg_mask(X: pd.DataFrame, seg: str) -> pd.Series:
    fresh = X.brand == 'Fresh'
    return {'Fresh_ambient': fresh & (X.temp_requirement == 'ambient'), 'Fresh_chilled': fresh & (X.temp_requirement == 'chilled'),
            'Style': X.brand == 'Style', 'Tech': X.brand == 'Tech'}[seg]


class LinearPredictor:
    """A fitted OLS kept as its coefficient table and evaluated directly (small, transparent, version-independent)."""
    PAT_CAT = re.compile(r'^C\((\w+)\)\[T\.(.+)\]$')

    def __init__(self, params):
        self.params = {str(k): float(v) for k, v in dict(params).items()}

    def predict(self, X: pd.DataFrame) -> np.ndarray:
        out = np.zeros(len(X))
        for name, b in self.params.items():
            if name == 'Intercept':
                out += b
                continue
            m = self.PAT_CAT.match(name)
            if m:
                out += b * (X[m.group(1)].astype(str).values == m.group(2))
            else:
                out += b * X[name].values.astype(float)
        return out


class DemandModel:
    def fit(self, orders: pd.DataFrame, cal: pd.DataFrame, outlets: pd.DataFrame) -> 'DemandModel':
        """orders: demand history (build_history output or any frame with its columns). Orders without a volume
        (inferred never-run orders) inform the schedule tables but cannot enter the volume regressions."""
        CAL = calendar_features(cal)
        X = orders.merge(CAL, left_on='order_date', right_on='date', how='left')
        assert X.date.notna().all(), 'every order_date must be in calendar.csv'
        V = X[X.order_volume_m3.notna()].copy()
        V['lv'] = np.log(V.order_volume_m3)
        self.models, self.smear, self.terms, self.n_obs, self.resid_sd = {}, {}, {}, {}, {}
        for s in SEGMENTS:
            g = V[seg_mask(V, s)]
            terms = SEG_BASE[s] + [f'ramp_{f}' for f in FESTS if (g[f'ramp_{f}'] != 0).any()]
            if s == 'Fresh_chilled':
                w = np.power(0.5, (g.t_years.max() - g.t_years.values) / CHILLED_HALF_LIFE_YEARS)
                m = smf.wls('lv ~ ' + ' + '.join(terms), data=g, weights=w).fit()
            else:
                w = None
                m = smf.ols('lv ~ ' + ' + '.join(terms), data=g).fit()
            lp = LinearPredictor(m.params)
            chk = g.sample(min(2000, len(g)), random_state=0)
            assert np.allclose(lp.predict(chk), np.asarray(m.predict(chk)), atol=1e-8), 'coefficient evaluator mismatch'
            self.models[s] = lp
            self.smear[s] = float(np.average(np.exp(m.resid), weights=w))   # Duan smearing: E[exp(noise)]
            self.terms[s] = terms; self.n_obs[s] = int(len(g)); self.resid_sd[s] = float(np.std(m.resid))
        d0, d1 = orders.order_date.min(), orders.order_date.max()
        op = CAL[(CAL.is_operating == 1) & CAL.date.between(d0, d1)]
        tech = outlets[outlets.brand == 'Tech'].outlet_id
        grid = pd.MultiIndex.from_product([tech, op.date], names=['outlet_id', 'date']).to_frame(index=False).merge(op[['date', 'dow']], on='date')
        n = (X[X.brand == 'Tech'].groupby(['outlet_id', 'order_date']).size().rename('n').reset_index()
             .rename(columns={'order_date': 'date'}))
        grid = grid.merge(n, on=['outlet_id', 'date'], how='left').fillna({'n': 0})
        rate = grid.assign(y=grid.n > 0).groupby(['outlet_id', 'dow']).y.mean()
        self.tech_rate = {(str(o), int(w)): float(r) for (o, w), r in rate.items()}
        fc = X[seg_mask(X, 'Fresh_chilled')]
        cnt = fc.groupby(['outlet_id', 'dow']).size()
        self.chilled_days = {}
        for (o, w), k in cnt.items():
            if k > MIN_CHILLED_DAY_COUNT:
                self.chilled_days.setdefault(str(o), []).append(int(w))
        self.style_day = {str(o): int(w) for o, w in X[X.brand == 'Style'].groupby('outlet_id').dow.agg(lambda s: s.mode()[0]).items()}
        self.history_start, self.history_end, self.n_orders = str(d0), str(d1), int(len(orders))
        return self

    def schedule(self, dates: pd.DataFrame, outlets: pd.DataFrame) -> pd.DataFrame:
        """dates: calendar rows (date, dow, is_operating). One row per expected order."""
        days = dates[dates.is_operating == 1][['date', 'dow']]
        fresh = outlets.loc[outlets.brand == 'Fresh', 'outlet_id'].tolist()
        tech = outlets.loc[outlets.brand == 'Tech', 'outlet_id'].tolist()
        rows = []
        for d_, w_ in days.itertuples(index=False):
            for oid in fresh:
                rows.append((d_, oid, 'ambient', 1.0))
                if w_ in self.chilled_days.get(oid, ()):
                    rows.append((d_, oid, 'chilled', 1.0))
            for oid, sd in self.style_day.items():
                if w_ == sd:
                    rows.append((d_, oid, 'ambient', 1.0))
            for oid in tech:
                rows.append((d_, oid, 'ambient', self.tech_rate.get((oid, int(w_)), 0.0)))
        S = pd.DataFrame(rows, columns=['order_date', 'outlet_id', 'temp_requirement', 'expected_count'])
        return S.merge(outlets[['outlet_id', 'brand', 'depot']], on='outlet_id')

    def expected_volume(self, S: pd.DataFrame, cal: pd.DataFrame) -> pd.DataFrame:
        """Adds exp_vol (expected m3 of one order) to rows with order_date, outlet_id, brand, temp_requirement."""
        X = S.merge(calendar_features(cal), left_on='order_date', right_on='date', how='left')
        X['exp_vol'] = np.nan
        for s, m in self.models.items():
            mk = seg_mask(X, s)
            if mk.any():
                X.loc[mk, 'exp_vol'] = np.exp(m.predict(X[mk])) * self.smear[s]
        return X

    def weekly(self, date_from: str, date_to: str, cal: pd.DataFrame, outlets: pd.DataFrame) -> pd.DataFrame:
        """Weekly depot x brand forecast (total, chilled) for the calendar dates in [date_from, date_to]."""
        return self.weekly_for_dates(cal[(cal.date >= date_from) & (cal.date <= date_to)], cal, outlets)

    def weekly_for_dates(self, dates: pd.DataFrame, cal: pd.DataFrame, outlets: pd.DataFrame) -> pd.DataFrame:
        X = self.expected_volume(self.schedule(dates, outlets), cal)
        X['total'] = X.exp_vol * X.expected_count
        X['chilled'] = np.where(X.temp_requirement == 'chilled', X.total, 0.0)
        return X.groupby(KEYS)[['total', 'chilled']].sum().reset_index()


# ------------------------------------------------------------------ prediction in submission form
def predict_rows(model: DemandModel, rows: pd.DataFrame, cal: pd.DataFrame, outlets: pd.DataFrame) -> pd.DataFrame:
    """rows: depot, brand, iso_year, iso_week (like task2a_test_inputs.csv). Returns the rows, in the same order, with
    pred_total_volume_m3 and pred_chilled_volume_m3: non-negative, chilled <= total, chilled exactly 0 unless Fresh."""
    wanted = rows[['iso_year', 'iso_week']].drop_duplicates()
    dates = cal.merge(wanted, on=['iso_year', 'iso_week'], how='inner')
    assert len(dates.drop_duplicates(['iso_year', 'iso_week'])) == len(wanted), 'forecast weeks must be in calendar.csv'
    f = model.weekly_for_dates(dates, cal, outlets)
    out = rows.merge(f, on=KEYS, how='left', validate='m:1')
    total = out.total.fillna(0.0).clip(lower=0).round(ROUND_DECIMALS)       # no scheduled order in a week -> 0 demand
    chilled = np.where(out.brand == 'Fresh', out.chilled.fillna(0.0).clip(lower=0), 0.0).round(ROUND_DECIMALS)
    out['pred_total_volume_m3'] = total
    out['pred_chilled_volume_m3'] = np.minimum(chilled, total)
    out = out.drop(columns=['total', 'chilled'])
    assert len(out) == len(rows) and (out.pred_chilled_volume_m3 <= out.pred_total_volume_m3).all()
    assert (out.pred_chilled_volume_m3[out.brand != 'Fresh'] == 0).all() and (out.pred_total_volume_m3 >= 0).all()
    return out


def impute_recovered_volumes(model: DemandModel, history: pd.DataFrame, cal: pd.DataFrame) -> pd.DataFrame:
    """History with the unknown volumes of inferred orders replaced by the model's expected volume (reporting only)."""
    h = history.copy()
    miss = h.order_volume_m3.isna()
    if miss.any():
        e = model.expected_volume(h.loc[miss, ['delivery_id', 'order_date', 'outlet_id', 'brand', 'temp_requirement']], cal)
        h.loc[miss, 'order_volume_m3'] = h.loc[miss, 'delivery_id'].map(e.set_index('delivery_id').exp_vol).values
    return h


# ------------------------------------------------------------------ rolling-origin backtest
def backtest(history: pd.DataFrame, cal: pd.DataFrame, outlets: pd.DataFrame, origins, horizon_weeks: int = 10) -> pd.DataFrame:
    """origins: Mondays (YYYY-MM-DD). Each fit uses only orders dated strictly before the origin; the forecast covers
    the following horizon_weeks ISO weeks. Returns origin, depot, brand, iso_year, iso_week, total, chilled."""
    out = []
    for od in origins:
        od = pd.Timestamp(od)
        assert od.dayofweek == 0, 'origins must be Mondays (ISO week starts)'
        start, end = od.strftime('%Y-%m-%d'), (od + pd.Timedelta(days=7 * horizon_weeks - 1)).strftime('%Y-%m-%d')
        train = history[history.order_date < start]
        assert train.order_date.max() < start
        m = DemandModel().fit(train, cal, outlets)
        f = m.weekly(start, end, cal, outlets)
        f['origin'] = start
        out.append(f)
    return pd.concat(out, ignore_index=True)
