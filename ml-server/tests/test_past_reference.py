"""A day past the supplied calendar and road data: no payday sent, no road row (R-CAL-05, MF-07)."""
import numpy as np
import pandas as pd

from app import scoring


def test_an_empty_road_table_keeps_its_numbers_numeric():
    g = scoring.general({name: [] for name in scoring.TABLES})
    assert g['road_conditions'].disruption_index.dtype == float
    merged = pd.DataFrame({'district': ['Kandy'], 'date': ['2026-10-23']}).merge(
        g['road_conditions'], on=['district', 'date'], how='left')
    assert (merged.disruption_index.fillna(100.0) * 2).dtype == float


def test_a_calendar_with_no_payday_counts_every_day_as_far_from_one():
    cal = pd.DataFrame({'date': ['2026-10-22', '2026-10-23'], 'is_weekend': [0, 0], 'is_payday': [0, 0],
                        'festival_ramp': [0.0, 0.0], 'is_holiday': [0, 0], 'monsoon': [1, 1]})
    g = {'outlets': pd.DataFrame(), 'calendar': cal, 'road_conditions': pd.DataFrame(),
         'traffic_speed': pd.DataFrame(columns=['district', 'monsoon', 'hour', 'speed_index']),
         'service_allowance': pd.DataFrame(), 'district_travel': pd.DataFrame()}

    class Trained:
        vocab = {}

    ctx = scoring._context(g, Trained())
    assert list(ctx.calf.pay_lag) == [10, 10]
    assert list(ctx.calf.pay_lead) == [10, 10]
    assert np.issubdtype(ctx.calf.pay_lag.dtype, np.integer)
