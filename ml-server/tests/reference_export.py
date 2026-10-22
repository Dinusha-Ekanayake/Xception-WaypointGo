"""The backend's reference export (GET /api/ml/training/reference) built from the supplied CSVs, for tests."""
import json
from pathlib import Path

import pandas as pd

GENERAL = Path(__file__).resolve().parents[2] / 'data' / 'General Data'
TABLES = ('outlets', 'service_allowance', 'traffic_speed', 'calendar')


def tables(extra_days: list[dict] | None = None) -> dict:
    """Each table as the backend sends it: JSON records with the CSV's column names.

    ``extra_days`` are calendar rows the backend would generate past the supplied calendar.
    """
    out = {}
    for name in TABLES:
        df = pd.read_csv(GENERAL / f'{name}.csv')
        if name == 'calendar':
            df = df.drop(columns=['dow_name'])
        out[name] = json.loads(df.to_json(orient='records'))
    out['calendar'] += extra_days or []
    out['referenceVersionId'] = '00000000-0000-7000-8000-000000000001'
    return out


def generated_day(date: str, dow: int, iso_year: int, iso_week: int) -> dict:
    """A day as OperatingCalendarPolicy generates it: no payday, festival or holiday, open unless Sunday."""
    return dict(date=date, dow=dow, is_weekend=int(dow >= 5), iso_year=iso_year, iso_week=iso_week, is_payday=0,
                festival=None, festival_ramp=0.0, is_holiday=0, monsoon=0, is_operating=int(dow != 6))
