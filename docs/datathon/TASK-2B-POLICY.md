# Task 2B: peak-day allocation policy (Scenario S1, Peliyagoda)

No trained model is used. The allocation is produced by Waypoint's planning engine:
- every hard rule is checked in its constraint registry;
- orders are taken in a fixed priority order;
- a second pass re-plans the refrigerated vehicles as a whole.

Every number below comes from the engine's output (`submission_task2b.csv`, written by `PeakDayAllocationTest`) and from the supplied data files. The official `check_allocation.py` reports **"FEASIBILITY: PASSED - every rule satisfied"**, and runs on every change in CI.

## 1. Demand against available capacity

Of the 38 vehicles in the scenario, **10 are in the workshop**. That includes **5 of the 9 refrigerated vehicles**: 4 reefer trucks and 1 reefer van.

| | Demand | Available to carry it |
| --- | --- | --- |
| Fresh chilled | 26 orders, **181.6 m³**, 32,780 kg, in 7 districts | **4 reefers** (3 trucks, 1 van): 86.2 m³ per round, 172.4 m³ if every reefer ran two full trips |
| Fresh ambient | 49 orders, 133.0 m³ | 22 dry trucks, 2 dry vans, plus spare reefer room |
| Style | 5 orders, 77.2 m³ | dry trucks (480-minute budget) |
| Tech | 5 orders, 18.1 m³ | dry trucks (480-minute budget) |

## 2. What limited service: refrigerated trips before 08:00

Chilled goods ride only in reefers, and every chilled window closes at 08:00, so the Fresh budget of **270 minutes per vehicle** (03:30 to 08:00) applies. Each trip carries one brand to one district. That leaves **8 reefer trips** (4 vehicles × 2) for **7 chilled districts**. The farthest district is 173 minutes from the depot one way (Puttalam), so a vehicle cannot fit two distant trips in 270 minutes.

What the engine did with the 8 trips, all within the budget (trip minutes = outbound + stops + handling, no return):

| Reefer | Trip 1 | Trip 2 | Fresh minutes used |
| --- | --- | --- | --- |
| VEH003 truck | Puttalam, 8.7 m³ (188 min) | Gampaha, 21.2 m³ (76 min) | 264 / 270 |
| VEH006 truck | Colombo, 30.9 m³ (108 min) | Galle, 20.3 m³ (143 min) | 251 / 270 |
| VEH007 truck | Kurunegala, 13.0 m³ (176 min) | Kalutara, 5.9 m³ (79 min) | 255 / 270 |
| VEH036 van | Colombo, 4.2 m³ (64 min) | Kalutara, 5.1 m³ (80 min) | 144 / 270 |

**109.2 m³ of chilled goods are carried. The binding resource is reefer trips within the 270-minute window, not volume.** Every reefer runs both its trips, and three of the four are within 19 minutes of the budget. Dry trucks, vans and the Style/Tech budget are not limiting: every Fresh ambient, Style and Tech order fits, except one (below).

## 3. Result

- **73 of 85 orders served.** 11 deferred and 1 unservable.
- 13 vehicles and 25 trips used.
- **All 10 outlets deferred yesterday are served today**, so no outlet is skipped twice in a row.

## 4. Which deferrals were unavoidable, and which were a choice

**Unavoidable:**
- **S1-078** (Style, Kurunegala) is **40.66 m³**, larger than the largest vehicle (38 m³). It cannot be served by any vehicle on any day without splitting, which the rules forbid. It is marked unservable and needs the store to split the order.
- **The amount of chilled goods deferred.** Demand is 181.6 m³ against a hard ceiling of 172.4 m³ even if every reefer ran two full trips. With one district per trip and the 270-minute window, about 72 m³ cannot go today whatever the choice.

**A choice: which chilled orders wait.** With only 8 reefer trips, the engine decides which districts and stops get them, by this fixed priority order:
1. deferred yesterday (`deferred_yesterday`);
2. Fresh;
3. chilled;
4. strict or mall window;
5. the brand's ordering cadence;
6. earliest window close;
7. then distance, volume and longest unserved (`days_since_last_served`) as tie-breaks.

| Deferred | Why that one | What it costs |
| --- | --- | --- |
| Matara, S1-064 and S1-067 (12.0 m³) | 137 minutes each way: a Matara trip would take a reefer's budget that serves more volume nearer | Two stores without chilled stock for a day; last served yesterday, so this is their first miss |
| Gampaha, 4 of 6 (S1-028, S1-031, S1-033, S1-035, 26.9 m³) | The Gampaha trip filled VEH003 to 21.2 of 26.4 m³; the next four do not fit the trip, and no reefer trip is left | Four stores short of chilled goods a week before the festival. They are first in line tomorrow under rule 1 |
| Colombo, S1-003 (van-only), S1-007, S1-012 (16.5 m³) | Colombo's reefer truck is full (30.9 of 33.4 m³). S1-003 needs a van, and the only reefer van would go over its **weight** limit (1,096 of 1,040 kg) though it has room in volume; S1-007 is over the van's volume and weight | Three stores; Colombo has the most chilled outlets (9), so the shortfall is spread |
| Kalutara S1-048, Kurunegala S1-071 (17.1 m³) | S1-048 would take VEH007 to 282 of its 270 Fresh minutes, and is over the reefer van's caps; S1-071 (12.0 m³) does not fit the 6.4 m³ left on the Kurunegala trip | Two stores |

The second pass on the reefers raised served orders from 70 (simple first fit) to 73. It only drops an order when a higher-ranked one takes its place.

## 5. The signals the brief asks for

| Signal | How it is used |
| --- | --- |
| `deferred_yesterday` | Ranked first: 10 of 10 served |
| `days_since_last_served` | Tie-break among equals. Every deferred order was served 1 or 2 days ago, so none goes long without stock |
| Chilled and perishable goods before the festival | Fresh and chilled are ranked above all other brands, and the reefers are re-planned as a whole to carry the most chilled volume |
| 8 AM Fresh deadline | The 270-minute Fresh budget per vehicle, and earliest window close as a key |

## 6. What would serve more

- **Get reefers out of the workshop**, or hire reefers for the festival week. Each extra reefer adds two trips before 08:00; 6 reefers give 12 trips for the 7 chilled districts instead of 8, which addresses the binding constraint directly (the exact gain needs a rerun of the engine with that fleet).
- **Ask OUT070 to split S1-078** into two orders under 38 m³.

After that, the remaining shortfall is time before 08:00, not trucks: the far districts (Puttalam, Matara, Kurunegala) need an early first trip.

The same engine also chose the vehicles and trips at lowest cost: **13 vehicles and 611 litres** of fuel, against 16 vehicles and 725 litres for the first feasible plan. The served set is unchanged.
