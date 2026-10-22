"""Issue #12, R-ML-38: retraining reads the reference the backend scores with, not the supplied CSVs."""
import json

import numpy as np
import pytest

from tests import reference_export
from tests.test_retrain import stop
from training import fetch, planning
from training import retrain_demand as rd

GENERAL = reference_export.GENERAL


def route(date):
    return dict(routeId='R1', date=date, depot='Peliyagoda', vehicleId='V1', vehicleType='van',
                vehicleTemp='ambient', brand='Fresh', district='Colombo',
                stops=[stop(0, '05:10', '07:30'), stop(1, '05:40', '07:30')])


def test_the_exported_reference_gives_the_features_the_csvs_give_on_the_same_days():
    from_csv = planning.features([route('2026-03-02')], planning.reference_tables(GENERAL))
    from_backend = planning.features([route('2026-03-02')], planning.reference_from_json(reference_export.tables()))
    assert from_csv.equals(from_backend)


def test_a_day_past_the_supplied_calendar_has_its_calendar_features_when_the_backend_sends_them():
    # Tuesday 2026-10-20: the supplied calendar ends on 2026-06-28; the backend generates the day.
    csv = planning.features([route('2026-10-20')], planning.reference_tables(GENERAL))
    assert csv[['dow', 'is_payday', 'is_holiday']].isna().all().all(), 'the skew this issue fixes'
    sent = reference_export.tables([reference_export.generated_day('2026-10-20', 1, 2026, 43)])
    x = planning.features([route('2026-10-20')], planning.reference_from_json(sent))
    assert list(x.dow) == [1, 1] and not x[planning.REFERENCE].isna().any().any()


def test_the_demand_retrain_accepts_orders_on_generated_days():
    sent = reference_export.tables([reference_export.generated_day('2026-10-20', 1, 2026, 43)])
    cal, outlets = rd.load_reference_json(sent)
    assert '2026-10-20' in set(cal.date) and len(outlets) == len(reference_export.tables()['outlets'])
    assert np.isnan(cal.set_index('date').festival.loc['2026-10-20'])


def test_the_reference_is_asked_for_every_depot_at_once_and_refused_when_a_table_is_missing():
    asked = []

    class Answer:
        def __init__(self, body):
            self.body = body

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def read(self):
            return json.dumps(self.body).encode()

    def opener(req):
        asked.append(req.full_url)
        assert req.headers['Cookie'] == 'SESSION=x'
        return Answer(reference_export.tables())

    tables = fetch.reference('http://localhost:8080', ['Kandy', 'Peliyagoda'], '2024-01-01', '2027-10-01',
                             'SESSION=x', opener)
    assert set(fetch.REFERENCE_TABLES) <= set(tables)
    assert asked == ['http://localhost:8080/api/ml/training/reference?depot=Kandy&depot=Peliyagoda'
                     '&from=2024-01-01&to=2027-10-01']

    def partial(req):
        body = reference_export.tables()
        del body['calendar']
        return Answer(body)

    with pytest.raises(ValueError, match='calendar'):
        fetch.reference('http://localhost:8080', ['Kandy'], '2024-01-01', '2027-10-01', 'SESSION=x', partial)
    with pytest.raises(ValueError):
        fetch.reference('http://localhost:8080', [], '2024-01-01', '2027-10-01', 'SESSION=x', opener)
