import { useEffect, useSyncExternalStore } from "react";
import { request } from "@shared/api/client";
import { forgetWanted, onProductNames, productNamesVersion, rememberNames, takeWantedNames } from "@shared/wording/productNames";

// Reads the warehouse's names of the products a screen labels, in batches of at
// most 200, and re-renders the role when they arrive. Mounted once per role. A
// failed read leaves the labels as they were (built from the id) and asks again
// later; offline the names already kept on the device are used.

const BATCH = 200;

export function useProductNames(): number {
  useEffect(() => {
    let timer: number | undefined;
    const read = (): void => {
      timer = undefined;
      const ids = takeWantedNames();
      for (let i = 0; i < ids.length; i += BATCH) {
        const batch = ids.slice(i, i + BATCH);
        request<Record<string, string>>(`/api/warehouse/catalogue/names?ids=${encodeURIComponent(batch.join(","))}`)
          .then((answer) => rememberNames(batch, answer ?? {}))
          .catch(() => forgetWanted(batch));
      }
    };
    const unsubscribe = onProductNames(() => {
      if (timer === undefined) timer = window.setTimeout(read, 50);
    });
    read();
    return () => {
      unsubscribe();
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, []);
  return useSyncExternalStore(onProductNames, productNamesVersion, () => 0);
}
