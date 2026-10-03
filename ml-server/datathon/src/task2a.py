"""Task 2A: weekly depot x brand total and chilled volume.

History = every order once (deliveries_train + task1_test_inputs, incl. deferred / not_run), assigned to the calendar
iso_year/iso_week of its order_date. Only Fresh has chilled demand.

Final recipe (converged):
  total   = "A-fest": expected order count (fixed weekday schedules, mean of last 26 weeks per depot x brand x dow; 0 on
            non-operating days -> orders are lost, not shifted) x volume-per-order from a per-brand weighted log-linear
            regression on festival-specific ramps, payday (+lag), post-holiday, holiday, monsoon, dow, depot, trend.
  chilled = total x (B chilled GLM / B total GLM) per depot-week   (weekday-aware chilled share; chilled <= total)
            B GLM = per-brand Tweedie(1.5) log-link GLM on daily volume: depot x dow + festival-specific ramps + payday +
            monsoon + day-after-closure + trend.
"""
import warnings
import numpy as np
import pandas as pd
import statsmodels.api as sm
import statsmodels.formula.api as smf

warnings.filterwarnings('ignore')
DEPOTS = ['Kandy', 'Peliyagoda']; BRANDS = ['Fresh', 'Style', 'Tech']
KEYS = [(d, b) for d in DEPOTS for b in BRANDS]


def orders_history(dtr, dte):
    o = pd.concat([dtr, dte], ignore_index=True)
    assert o.delivery_id.is_unique
    o['chilled_v'] = np.where(o.temp_requirement == 'chilled', o.order_volume_m3, 0.0)
    return o


# ------------------------------------------------------------------ calendar features
def calendar_features(cal):
    c = cal.copy()
    c['fest_next'] = c.festival.bfill()
    fests = sorted(c.festival.dropna().unique())
    for f in fests:
        c['fr_' + f] = c.festival_ramp * (c.fest_next == f)
    c['fest_next'] = c.fest_next.fillna('none')
    c['after_closed'] = (c.is_operating.shift(1) == 0).astype(int)
    c['t'] = (pd.to_datetime(c.date) - pd.Timestamp('2024-01-01')).dt.days / 365          # B GLM trend
    c['t_a'] = (pd.to_datetime(c.date) - pd.Timestamp('2024-01-01')).dt.days / 365.25     # A-fest trend
    c['post_hol'] = c.is_holiday.shift(1, fill_value=0) | c.is_holiday.shift(2, fill_value=0)
    c['pay_lag1'] = c.is_payday.shift(1, fill_value=0)
    c['mar_evt'] = (c.iso_week == 10).astype(int)          # recurring early-March Style spike
    wk = c.groupby(['iso_year', 'iso_week']).agg(start=('date', 'min')).reset_index().sort_values('start').reset_index(drop=True)
    wk['widx'] = np.arange(len(wk))
    c = c.merge(wk[['iso_year', 'iso_week', 'widx']], on=['iso_year', 'iso_week'])
    return c, wk, fests


def daily_panel(o, c):
    """Operating days x depot x brand with order count n, volume v, chilled volume ch (0 where no orders)."""
    dd = o.groupby(['order_date', 'depot', 'brand']).agg(n=('delivery_id', 'size'), v=('order_volume_m3', 'sum'),
                                                         ch=('chilled_v', 'sum')).reset_index().rename(columns={'order_date': 'date'})
    grid = pd.MultiIndex.from_product([c.date[c.is_operating == 1], DEPOTS, BRANDS], names=['date', 'depot', 'brand']).to_frame(index=False)
    return grid.merge(dd, how='left').fillna({'n': 0, 'v': 0, 'ch': 0}).merge(c, on='date')


def weekly_actuals(o, c):
    return o.merge(c[['date', 'iso_year', 'iso_week', 'widx']], left_on='order_date', right_on='date').groupby(
        ['widx', 'iso_year', 'iso_week', 'depot', 'brand']).agg(v=('order_volume_m3', 'sum'), ch=('chilled_v', 'sum')).reset_index()


# ------------------------------------------------------------------ A-fest (total)
def _xa(d, fests, b):
    x = d[['fr_' + f for f in fests] + ['is_payday', 'pay_lag1', 'post_hol', 'is_holiday', 'monsoon', 't_a', 'mar_evt']].astype(float).copy()
    x['kandy'] = (d.depot == 'Kandy').astype(float)
    for k in range(1, 6):
        x[f'dow{k}'] = (d.dow == k).astype(float)
    if b != 'Style':
        x = x.drop(columns=['mar_evt'])
    return sm.add_constant(x, has_constant='add')


def fit_afest(hist, fests):
    h26 = hist[pd.to_datetime(hist.date) > pd.to_datetime(hist.date).max() - pd.Timedelta(days=182)]
    model = dict(fests=fests, n_hat=h26.groupby(['depot', 'brand', 'dow']).n.mean().rename('n_hat').reset_index(), brands={})
    for b in BRANDS:
        h = hist[(hist.brand == b) & (hist.n > 0)]
        X = _xa(h, fests, b); y = np.log(h.v / h.n); w = h.n.values
        m = sm.WLS(y, X, weights=w).fit()
        smear = np.average(np.exp(y - m.predict(X)), weights=w)            # Duan smearing back-transform
        model['brands'][b] = dict(params=m.params, smear=smear)
    return model


def predict_afest(model, fut):
    f = fut.merge(model['n_hat'], on=['depot', 'brand', 'dow'], how='left').fillna({'n_hat': 0})
    parts = []
    for b in BRANDS:
        g = f[f.brand == b].copy(); p = model['brands'][b]
        X = _xa(g, model['fests'], b)[p['params'].index]
        g['v_hat'] = g.n_hat * np.exp(X.values @ p['params'].values) * p['smear']
        parts.append(g)
    return pd.concat(parts)


# ------------------------------------------------------------------ B GLM (chilled share)
def _formula(fests, col):
    return col + '~C(depot)*C(dow)+' + '+'.join('fr_' + f for f in fests) + '+is_payday+monsoon+after_closed+t'


def fit_glm(hist, fests):
    tw = sm.families.Tweedie(var_power=1.5, link=sm.families.links.Log())
    out = {}
    for b in BRANDS:
        h = hist[hist.brand == b].rename(columns={'ch': 'c'})
        for col in (['v', 'c'] if b == 'Fresh' else ['v']):
            out[(b, col)] = smf.glm(_formula(fests, col), data=h, family=tw).fit()
    return out


def predict_glm(glms, fut):
    f = fut.copy(); f['p_v'] = 0.0; f['p_c'] = 0.0
    for b in BRANDS:
        m = f.brand == b
        f.loc[m, 'p_v'] = glms[(b, 'v')].predict(f[m]).values
        if b == 'Fresh':
            f.loc[m, 'p_c'] = glms[(b, 'c')].predict(f[m]).values
    return f


# ------------------------------------------------------------------ combined forecast
def future_rows(c, widxs):
    fut = c[(c.widx.isin(widxs)) & (c.is_operating == 1)]
    return pd.concat([fut.assign(depot=d, brand=b) for d, b in KEYS], ignore_index=True)


def fit(daily, fests, origin_widx):
    hist = daily[daily.widx < origin_widx]
    hist_b = hist[hist.date <= hist[hist.n > 0].date.max()]   # B GLM trained on dates with history only
    return dict(afest=fit_afest(hist, fests), glm=fit_glm(hist_b, fests))


def forecast(model, c, widxs):
    """Weekly forecasts for the given week indices -> depot, brand, widx, iso_year, iso_week, total, chilled."""
    fut = future_rows(c, widxs)
    a = predict_afest(model['afest'], fut).groupby(['widx', 'depot', 'brand']).v_hat.sum()
    g = predict_glm(model['glm'], fut).groupby(['widx', 'depot', 'brand'])[['p_v', 'p_c']].sum()
    idx = pd.MultiIndex.from_product([sorted(widxs), DEPOTS, BRANDS], names=['widx', 'depot', 'brand'])
    r = pd.DataFrame(index=idx).join(a).join(g).fillna(0).reset_index()
    r['total'] = r.v_hat
    ratio = np.where(r.p_v > 0, r.p_c / r.p_v.where(r.p_v > 0, 1), 0)
    r['chilled'] = np.where(r.brand == 'Fresh', np.minimum(r.total * ratio, r.total), 0.0)
    r['b_total'] = r.p_v; r['b_chilled'] = np.where(r.brand == 'Fresh', r.p_c, 0.0)
    wk = c.drop_duplicates('widx').set_index('widx')[['iso_year', 'iso_week']]
    return r.join(wk, on='widx')


def backtest(daily, W, c, fests, origins, H=10):
    """Rolling-origin backtest (origins = week indices). Returns per-row frame with actuals."""
    rows = []
    for org in origins:
        m = fit(daily, fests, org)
        f = forecast(m, c, list(range(org, org + H))).merge(W[['widx', 'depot', 'brand', 'v', 'ch']], on=['widx', 'depot', 'brand'], how='left')
        f['origin'] = org; rows.append(f)
    return pd.concat(rows)
