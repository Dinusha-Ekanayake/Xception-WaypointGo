"use client";

import "leaflet/dist/leaflet.css";
import type * as Leaflet from "leaflet";
import { useEffect, useMemo, useRef, useState } from "react";
import { cluster, compass, MAX_ZOOM, MIN_ZOOM, SRI_LANKA, type LatLon } from "./geo.ts";
import type { LiveMapProps, MapMarker } from "./types.ts";

// The only file that touches Leaflet. Loaded client side only (see index.ts),
// because Leaflet reads `window` on import. Props in, events out: no fetching.
// Markers are our own HTML, coloured by design tokens; Leaflet's default chrome
// (zoom, attribution) is replaced by ours so it follows the theme.

const COLOR: Record<NonNullable<MapMarker["status"]>, string> = {
  "on-time": "var(--color-go-teal)",
  "at-risk": "var(--color-go-warning)",
  late: "var(--color-go-danger)",
  returning: "var(--color-go-info)",
  offline: "var(--color-go-offline)",
};

const esc = (text: string) => text.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

function iconHtml(m: MapMarker, selected: boolean): { html: string; size: [number, number]; anchor: [number, number] } {
  const label = `<span class="pointer-events-none absolute left-1/2 top-full mt-0.5 -translate-x-1/2 whitespace-nowrap rounded-full bg-white px-1.5 text-[11px] leading-[17px] font-medium text-[#031b08] shadow">${esc(m.label)}</span>`;
  if (m.kind === "vehicle") {
    const color = COLOR[m.status ?? "on-time"];
    const turn = { N: 0, NE: 45, E: 90, SE: 135, S: 180, SW: 225, W: 270, NW: 315 }[compass(m.heading ?? null)];
    const ring = selected ? "box-shadow:0 0 0 6px color-mix(in srgb, " + color + " 25%, transparent);" : "";
    return {
      html: `<span class="relative flex h-11 w-11 items-center justify-center" style="opacity:${m.faded ? 0.55 : 1}">
        <span class="flex h-7 w-7 items-center justify-center rounded-full border-2 border-white" style="background:${color};${ring}">
          <svg width="14" height="14" viewBox="0 0 14 14" style="transform:rotate(${turn}deg)" aria-hidden="true"><path d="M7 1 L12 12 L7 9.5 L2 12 Z" fill="white"/></svg>
        </span>${label}</span>`,
      size: [44, 44],
      anchor: [22, 22],
    };
  }
  if (m.kind === "depot") {
    return {
      html: `<span class="relative flex h-8 w-8 items-center justify-center rounded-[10px] bg-[#031b08] text-[11px] font-semibold text-white">GO${label}</span>`,
      size: [32, 32],
      anchor: [16, 16],
    };
  }
  if (m.kind === "cluster") {
    return {
      html: `<span class="flex items-center gap-1.5 whitespace-nowrap rounded-full bg-white py-1 pl-1 pr-3 text-[12px] text-[#031b08] shadow"><span class="flex h-6 min-w-6 items-center justify-center rounded-full bg-[#0e766d] px-1 text-[11px] font-semibold text-white">${esc(m.count ? String(m.count) : "")}</span><span><b class="font-medium">${esc(m.label)}</b>${m.sub ? `<br><span class="text-[11px] text-[#6b7280]">${esc(m.sub)}</span>` : ""}</span></span>`,
      size: [0, 0],
      anchor: [20, 18],
    };
  }
  // A stop: 22 px numbered circle, signal green with a tick once done.
  const done = m.done ? "background:var(--color-go-signal);color:white;border-color:white" : "background:white;color:#031b08;border-color:#031b08";
  return {
    html: `<span class="relative flex h-[22px] w-[22px] items-center justify-center rounded-full border-2 text-[10px] font-semibold" style="${done};opacity:${m.faded ? 0.6 : 1}">${esc(m.badge ?? "")}${m.label ? label.replace("bg-white", m.kind === "store" ? "bg-[#031b08] text-white" : "bg-white") : ""}</span>`,
    size: [22, 22],
    anchor: [11, 11],
  };
}

export default function MapCanvas(props: LiveMapProps): React.JSX.Element {
  const host = useRef<HTMLDivElement>(null);
  const map = useRef<Leaflet.Map | null>(null);
  const L = useRef<typeof Leaflet | null>(null);
  const layer = useRef<Leaflet.LayerGroup | null>(null);
  const fitted = useRef("");
  const [ready, setReady] = useState(false);
  const [base, setBase] = useState<"loading" | "ok" | "failed">("loading");
  const [zoom, setZoom] = useState(0);
  const latest = useRef(props);
  latest.current = props;

  useEffect(() => {
    let cancelled = false;
    void import("leaflet").then((mod) => {
      if (cancelled || !host.current) return;
      const lf = (mod as unknown as { default?: typeof Leaflet }).default ?? (mod as unknown as typeof Leaflet);
      L.current = lf;
      const m = lf.map(host.current, {
        zoomControl: false,
        attributionControl: false,
        minZoom: MIN_ZOOM,
        maxZoom: MAX_ZOOM,
        maxBounds: lf.latLngBounds([SRI_LANKA.south - 1, SRI_LANKA.west - 1], [SRI_LANKA.north + 1, SRI_LANKA.east + 1]),
        zoomAnimation: !window.matchMedia?.("(prefers-reduced-motion: reduce)").matches,
        markerZoomAnimation: false,
      });
      m.setView([7.6, 80.7], 8);
      const tiles = lf.tileLayer("/map-tiles/{z}/{x}/{y}.png", { minZoom: MIN_ZOOM, maxZoom: MAX_ZOOM, bounds: lf.latLngBounds([SRI_LANKA.south, SRI_LANKA.west], [SRI_LANKA.north, SRI_LANKA.east]) });
      let loaded = 0;
      tiles.on("tileload", () => { loaded++; setBase("ok"); });
      tiles.on("tileerror", () => { if (loaded === 0) setBase("failed"); });
      tiles.addTo(m);
      layer.current = lf.layerGroup().addTo(m);
      m.on("zoomend", () => setZoom(m.getZoom()));
      m.on("click", () => latest.current.onSelect?.(null));
      map.current = m;
      setReady(true);
    });
    return () => {
      cancelled = true;
      map.current?.remove();
      map.current = null;
    };
  }, []);

  // Fit once per distinct set of points to fit, never on every poll.
  useEffect(() => {
    const m = map.current;
    const lf = L.current;
    const fit = props.fit ?? [];
    const key = fit.map((p) => `${p.lat.toFixed(3)},${p.lon.toFixed(3)}`).join("|");
    if (!ready || !m || !lf || fit.length === 0 || key === fitted.current) return;
    fitted.current = key;
    if (fit.length === 1) m.setView([fit[0].lat, fit[0].lon], 13);
    else m.fitBounds(lf.latLngBounds(fit.map((p) => [p.lat, p.lon] as [number, number])), { padding: [48, 48], maxZoom: 14 });
  }, [ready, props.fit]);

  // Redraw only when what is drawn changed, not on every parent render or poll
  // that returns the same positions: a redraw replaces each marker's element,
  // which drops keyboard focus from a vehicle the user is on.
  const drawn = useMemo(
    () => JSON.stringify([props.markers, props.lines ?? [], props.selectedId ?? null, Boolean(props.clusterVehicles)]),
    [props.markers, props.lines, props.selectedId, props.clusterVehicles],
  );

  useEffect(() => {
    const m = map.current;
    const lf = L.current;
    const group = layer.current;
    const props = latest.current;
    if (!ready || !m || !lf || !group) return;
    group.clearLayers();
    for (const line of props.lines ?? []) {
      if (line.points.length < 2) continue;
      lf.polyline(line.points.map((p) => [p.lat, p.lon] as [number, number]), {
        color: "#0e766d",
        weight: line.style === "driven" ? 5 : 4,
        opacity: line.faded ? 0.45 : 0.9,
        dashArray: line.style === "planned" ? "6 5" : undefined,
        interactive: false,
      }).addTo(group);
    }
    let markers = props.markers;
    if (props.clusterVehicles) {
      const vehicles = markers.filter((mk) => mk.kind === "vehicle");
      const groups = cluster(vehicles.map((mk) => ({ ...mk, ...pointOf(m, mk) })));
      const merged: MapMarker[] = [];
      for (const g of groups) {
        if (g.length === 1 || g.some((mk) => mk.id === props.selectedId)) merged.push(...g);
        else merged.push(props.clusterVehicles(g));
      }
      markers = [...markers.filter((mk) => mk.kind !== "vehicle"), ...merged];
    }
    for (const mk of markers) {
      const icon = iconHtml(mk, mk.id === props.selectedId);
      const marker = lf.marker([mk.lat, mk.lon], {
        icon: lf.divIcon({ html: icon.html, className: "", iconSize: icon.size, iconAnchor: icon.anchor }),
        keyboard: mk.selectable !== false,
        interactive: mk.selectable !== false,
        zIndexOffset: mk.kind === "vehicle" ? 1000 : mk.kind === "cluster" ? 900 : 0,
        title: mk.ariaLabel,
      }).addTo(group);
      const el = marker.getElement();
      if (el && mk.selectable !== false) {
        el.setAttribute("role", "button");
        el.setAttribute("aria-label", mk.ariaLabel);
        el.setAttribute("aria-pressed", String(mk.id === props.selectedId));
      }
      if (mk.selectable !== false) {
        marker.on("click", (e) => {
          lf.DomEvent.stopPropagation(e);
          if (mk.kind === "cluster") m.setView([mk.lat, mk.lon], Math.min(m.getZoom() + 2, MAX_ZOOM));
          else latest.current.onSelect?.(mk.id);
        });
        marker.on("mouseover", () => latest.current.onHover?.(mk.id));
        marker.on("mouseout", () => latest.current.onHover?.(null));
      }
    }
  }, [ready, drawn, zoom]);

  const zoomBy = (d: number) => map.current?.setZoom(map.current.getZoom() + d);

  return (
    <div className={`relative isolate overflow-hidden ${props.className ?? ""}`} style={{ background: props.background ?? "#cfe5ea" }}>
      <div ref={host} role="group" aria-label="Map" className="absolute inset-0 z-0" style={{ background: "transparent" }} />
      {props.overlay && <div className="pointer-events-none absolute left-4 top-4 z-[500] flex flex-col items-start gap-2 [&>*]:pointer-events-auto">{props.overlay}</div>}
      {base === "failed" && (
        <p role="status" className="pointer-events-none absolute bottom-14 left-1/2 z-[500] -translate-x-1/2 rounded-full bg-go-card px-3 py-1 text-[12px] text-go-ink shadow">Base map unavailable · positions and trails still shown</p>
      )}
      <div className="absolute right-4 top-4 z-[500] flex flex-col overflow-hidden rounded-[10px] bg-go-card text-go-ink shadow">
        <button type="button" aria-label="Zoom in" onClick={() => zoomBy(1)} className="h-[26px] w-[30px] text-[16px] leading-none">+</button>
        <button type="button" aria-label="Zoom out" onClick={() => zoomBy(-1)} className="h-[26px] w-[30px] border-t border-go-rule text-[16px] leading-none">−</button>
      </div>
      {props.legend && <div className="absolute bottom-4 left-4 z-[500]">{props.legend}</div>}
      <p className="absolute bottom-1 right-1 z-[500] rounded-full bg-go-card/90 px-2 text-[10px] leading-[17px] text-go-ink">© OpenStreetMap contributors</p>
    </div>
  );
}

function pointOf(m: Leaflet.Map, at: LatLon): { x: number; y: number } {
  const p = m.latLngToLayerPoint([at.lat, at.lon]);
  return { x: p.x, y: p.y };
}
