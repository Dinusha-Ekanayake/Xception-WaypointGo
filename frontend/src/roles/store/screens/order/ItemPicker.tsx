"use client";

import { useState } from "react";
import type { ProductView, Temperature } from "@shared/domain/types";
import { Icon } from "@shared/ui";
import { matches } from "../../data/lines.ts";

// "03c Place order: add item": the search names the catalogue's products of this
// order's class, and "+ Add" puts one at the foot of the list. Every product the
// warehouse has not confirmed is labelled inferred, since the catalogue is a
// reconstruction from order totals.

export default function ItemPicker({
  temperature,
  products,
  rows,
  loading,
  onAdd,
}: {
  temperature: Temperature;
  products: ProductView[];
  /** What the list already shows; those need no adding. */
  rows: readonly string[];
  loading: boolean;
  onAdd: (productId: string) => void;
}): React.JSX.Element {
  const [query, setQuery] = useState("");
  const found = matches(query, temperature, products, rows);
  const add = (productId: string) => {
    onAdd(productId);
    setQuery("");
  };

  return (
    <div className="relative">
      {/* The ring is on the field, not the bare input inside it. */}
      <label className="flex min-h-12 items-center gap-2 rounded-[18px] bg-white px-4 text-[15px] text-go-muted focus-within:ring-[3px] focus-within:ring-[#0a6b63] lg:bg-go-canvas">
        <Icon name="search" />
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Escape") setQuery("");
            const first = found.find((m) => !m.listed);
            if (e.key === "Enter" && first) add(first.product.productId);
          }}
          placeholder="Add an item · search by name or SKU"
          aria-label="Add an item"
          className="min-w-0 flex-1 bg-transparent text-black outline-none placeholder:text-go-muted focus-visible:outline-none! [&::-webkit-search-cancel-button]:hidden"
        />
        {query && (
          <button type="button" aria-label="Clear the search" onClick={() => setQuery("")} className="-mr-3 flex size-12 shrink-0 items-center justify-center text-black">
            <Icon name="close" />
          </button>
        )}
      </label>
      {query.trim() && (
        <ul aria-label="Catalogue matches" className="absolute inset-x-0 top-[calc(100%+6px)] z-20 flex flex-col gap-1 rounded-[18px] bg-white p-2 shadow-[0_8px_24px_rgba(0,0,0,0.12)]">
          {found.map(({ product, listed }) => (
            <li key={product.productId} className="flex items-center gap-3 rounded-[12px] py-1 pr-1 pl-3 hover:bg-go-canvas">
              <span className="flex min-w-0 flex-1 flex-col">
                <span className="truncate text-[15px] font-medium text-black">{product.productId}</span>
                <span className="text-[12px] text-go-muted">
                  {listed ? "In the list" : "Not ordered before"}
                  {!product.verifiedRealSku && (
                    <>
                      {" · "}
                      <span title="Reconstructed from order totals, not a confirmed product">inferred</span>
                    </>
                  )}
                </span>
              </span>
              {!listed && (
                <button type="button" aria-label={`Add ${product.productId}`} onClick={() => add(product.productId)} className="min-h-12 shrink-0 px-3 text-[14px] font-medium text-go-teal">
                  + Add
                </button>
              )}
            </li>
          ))}
          {found.length === 0 && (
            <li className="px-3 py-3 text-[14px] text-go-muted">
              {loading
                ? "Loading the catalogue…"
                : products.length === 0
                  ? "The catalogue cannot be read right now, so new items cannot be added."
                  : `Nothing in the ${temperature} catalogue matches "${query.trim()}".`}
            </li>
          )}
        </ul>
      )}
    </div>
  );
}
