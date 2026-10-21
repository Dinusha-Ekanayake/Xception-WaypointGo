# Product names from the warehouse

Owner: @Oxshadha. Requested 2026-10-21: the warehouse API now gives each product a `name` (and a `category`); show it to people instead of the product id, in search and everywhere a product is listed. The folder takes its issue number once the issue is opened.

## Current state

- `warehouse.products` caches the catalogue (`GET /products`) with no name. Screens build a label from the id (`productLabel` in `shared/wording/terms.ts`: "Fresh item 019 (inferred)"), or show the raw id (the store's item picker, the admin's orders).
- Lines reach screens through four modules' views, each with only `productId`: Ordering `OrderLineView` (store, dispatcher, admin), Loading `ItemView` (loader), Execution `DeliveryLineView` (driver, dispatcher), Receipt `ReceiptLineView` (store).
- The loader's and driver's policies do not hold `warehouse:ReadCatalogue`.

## Decisions

1. **One names read, shared by every screen** (`GET /api/warehouse/catalogue/names?ids=`, at most 200, `warehouse:ReadCatalogue`). Considered first: the name on each line view of the four modules. The views are built in static mappers in each module, so that meant four contract changes and four mirrors for one display field; one read and one label instead. The loader's and driver's policies gain `warehouse:ReadCatalogue` on the catalogue only (new default versions); the catalogue holds no stock, no price and nothing personal. The names a device has read are kept on it (`waypoint.product-names`, at most 3000), so a label reads the same offline; a failed read keeps today's label and asks again after a minute.
2. **Still labelled inferred.** Every catalogue row has `verified_real_sku = false`, so a name reads "Prawns 500g x 13 (inferred)" (AGENTS.md, external product catalogue rule 3). Weight and volume stay order-level for capacity; the name is display only.
3. **Search by name or id** in the store's item picker (`q` matches either).
4. **No name, the label as today.** A product the warehouse has not named, or a catalogue not yet synced again, falls back to `productLabel`.

## Who owns what

| Piece | Layer |
| --- | --- |
| `name`, `category` columns (nullable, expand only) | `migrations/..._warehouse_product_names.sql` |
| Reading them from `GET /products`; in the catalogue's content version | `warehouse/infrastructure/WarehouseHttpClient`, `warehouse/domain/CatalogueEntry` |
| `ProductView.name`, `CatalogueQuery.names(ids)`, `GET /api/warehouse/catalogue/names` | `warehouse/contract`, `warehouse/application/WarehouseCatalogueQuery`, `warehouse/web/WarehouseController` |
| Catalogue reads for the loader and driver | `migrations/20261022T0900_warehouse_product_names.sql` (policy versions) |
| The names kept on the device, the label | `shared/wording/productNames.ts`, `shared/wording/terms.ts` (`productLabel`) |
| Reading the names a screen asks for | `shared/catalogue/useProductNames.ts`, mounted once in each role |
| Screens | store (picker, order, receive, report), loader manifest and issue sheet, driver delivered items, admin orders |

## Tests

Warehouse: the sync stores the name and category, a changed name is a new catalogue version, search finds by name, `names` leaves out unnamed and unknown products (`WarehouseIntegrationTest`, `WarehouseDomainTest`). Authorization: the driver reads names, no session is 401, more than 200 ids is 422 (`ExecutionIntegrationTest`). Frontend: `productLabel` with and without a name, punctuation, the picker's search (`tests/product-names.test.ts`); the store finds and orders by name, the loader's manifest shows names (`e2e-store/product-names.spec.ts`, `e2e-loader/product-names.spec.ts`).
