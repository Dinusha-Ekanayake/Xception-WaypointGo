"""Request and response shapes. Field names are the backend's (camelCase)."""
from typing import Any, Dict, List, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field

HHMM = r'^([01]\d|2[0-3]):[0-5]\d$'
ISO_DATE = r'^\d{4}-\d{2}-\d{2}$'


class Model(BaseModel):
    model_config = ConfigDict(extra='forbid')


class Stop(Model):
    seq: int = Field(ge=0)
    deliveryId: str = Field(min_length=1)
    outletId: str = Field(min_length=1)
    orderDate: str = Field(pattern=ISO_DATE)
    deferred: bool = False
    tempRequirement: Literal['chilled', 'ambient']
    units: int = Field(gt=0)
    weightKg: float = Field(gt=0)
    volumeM3: float = Field(gt=0)
    fromPoint: str = Field(min_length=1)
    distanceKm: float = Field(ge=0)
    plannedDepartTime: str = Field(pattern=HHMM)
    plannedTravelMin: float = Field(ge=0)
    plannedArrivalTime: str = Field(pattern=HHMM)
    windowOpenTime: str = Field(pattern=HHMM)
    windowCloseTime: str = Field(pattern=HHMM)


class Route(Model):
    routeId: str = Field(min_length=1)
    date: str = Field(pattern=ISO_DATE)
    depot: str
    vehicleId: str
    vehicleType: Literal['van', 'truck']
    vehicleTemp: Literal['reefer', 'ambient']
    brand: str
    district: str
    stops: List[Stop] = Field(min_length=1)


# A model as the backend names it, name@version (R-ML-37). Absent: the image's model of the kind.
LABEL = r'^[a-z0-9][a-z0-9._-]{0,79}@[a-z0-9][a-z0-9._-]{0,79}$'


class DeliveryRiskRequest(Model):
    routes: List[Route] = Field(min_length=1)
    reference: Dict[str, List[Dict[str, Any]]]
    model: Optional[str] = Field(default=None, pattern=LABEL)


class StopPrediction(Model):
    deliveryId: str
    serviceMin: float
    lateProb: float
    roadConditions: Literal['used', 'fallback']


class DeliveryRiskResponse(Model):
    kind: str
    modelName: str
    modelVersion: str
    roadConditions: Literal['used', 'fallback', 'mixed']
    predictions: List[StopPrediction]


class Week(Model):
    depot: str
    brand: str
    isoYear: int = Field(ge=2000, le=2100)
    isoWeek: int = Field(ge=1, le=53)


class DemandForecastRequest(Model):
    calendar: List[Dict[str, Any]] = Field(min_length=7)
    # Every outlet of the depots forecast (outlet_id, brand, depot): the model schedules their orders.
    outlets: List[Dict[str, Any]] = Field(min_length=1)
    weeks: List[Week] = Field(min_length=1)
    model: Optional[str] = Field(default=None, pattern=LABEL)


class WeekForecast(Model):
    depot: str
    brand: str
    isoYear: int
    isoWeek: int
    totalM3: float
    chilledM3: float


class DemandForecastResponse(Model):
    kind: str
    modelName: str
    modelVersion: str
    forecasts: List[WeekForecast]


class Health(Model):
    status: Literal['ok']
    # The image's model of each kind: what answers a request that names none.
    models: List[Dict[str, Any]]
    # Every model loaded, the store's too, each with its source (R-ML-37).
    available: List[Dict[str, Any]] = []
    # Store entries that failed their checks, with the reason; never loaded.
    rejected: List[Dict[str, Any]] = []
    detail: Optional[str] = None
