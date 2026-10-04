"use client";

import "leaflet/dist/leaflet.css";
import type * as Leaflet from "leaflet";
import { useEffect, useMemo, useRef, useState } from "react";
import { cluster, MAX_ZOOM, metres, MIN_ZOOM, SRI_LANKA, type LatLon } from "./geo.ts";
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

/**
 * A top-down truck, cab first, pointing north before it is turned (Figma
 * 189:21746): a dark cab with its windscreen, and a cargo box in the status
 * colour with darker bands, outlined white so it reads on any tile.
 */
function truckSvg(color: string, heading: number): string {
  return `<svg width="20" height="40" viewBox="0 0 20 40" aria-hidden="true" style="transform:rotate(${heading}deg);filter:drop-shadow(0 1px 2px rgba(0,0,0,.35))">
    <rect x="3" y="1" width="14" height="10" rx="3.5" fill="#031b08" stroke="white" stroke-width="1.5"/>
    <rect x="5.2" y="3" width="9.6" height="3.4" rx="1.2" fill="#bfe3ff"/>
    <rect x="7" y="11" width="6" height="2.2" fill="#031b08"/>
    <rect x="2" y="13" width="16" height="25.5" rx="2.2" fill="${color}" stroke="white" stroke-width="1.5"/>
    <rect x="4.2" y="16" width="11.6" height="4.6" rx="0.8" fill="rgba(0,0,0,.22)"/>
    <rect x="4.2" y="23.2" width="11.6" height="4.6" rx="0.8" fill="rgba(0,0,0,.22)"/>
    <rect x="4.2" y="30.4" width="11.6" height="4.6" rx="0.8" fill="rgba(0,0,0,.22)"/>
  </svg>`;
}

/**
 * @param dimmed another vehicle is chosen: this one steps back, as the
 *   unchosen trucks do in Figma 189:21746
 */
function iconHtml(m: MapMarker, selected: boolean, dimmed: boolean): { html: string; size: [number, number]; anchor: [number, number] } {
  const label = `<span class="pointer-events-none absolute left-1/2 top-full mt-0.5 -translate-x-1/2 whitespace-nowrap rounded-full bg-white px-1.5 text-[11px] leading-[17px] font-medium text-[#031b08] shadow">${esc(m.label)}</span>`;
  if (m.kind === "vehicle") {
    const color = COLOR[m.status ?? "on-time"];
    // The exact direction of travel (R-EXE-22), not a compass point; north when unknown.
    const heading = Number.isFinite(m.heading) ? ((m.heading! % 360) + 360) % 360 : 0;
    const halo = selected
      ? `background:color-mix(in srgb, ${color} 26%, transparent);box-shadow:0 0 0 2px color-mix(in srgb, ${color} 55%, transparent)`
      : `background:color-mix(in srgb, ${color} 16%, transparent)`;
    const tag = selected
      ? "bg-white font-semibold text-[#031b08] shadow"
      : "bg-white/75 font-medium text-[#6b7280]";
    const opacity = m.faded ? 0.55 : dimmed ? 0.5 : 1;
    return {
      html: `<span class="relative flex h-[56px] w-[56px] items-center justify-center" style="opacity:${opacity}">
        <span class="absolute inset-0 rounded-full" style="${halo}"></span>
        <span class="relative flex items-center justify-center">${truckSvg(color, heading)}</span>
        <span class="pointer-events-none absolute right-full top-1/2 mr-0.5 -translate-y-1/2 whitespace-nowrap rounded-full px-2 text-[12px] leading-[22px] ${tag}">${esc(m.label)}</span>
      </span>`,
      size: [56, 56],
      anchor: [28, 28],
    };
  }
  if (m.kind === "depot") {
    // A small GO pill with the depot's name beside it; grey when outside the depot filter.
    const pill = m.faded ? "bg-[#6b7280]" : "bg-[#031b08]";
    return {
      html: `<span class="relative flex h-6 w-10 items-center justify-center rounded-full ${pill} text-[12px] font-bold tracking-[0.02em] text-white">GO<span class="pointer-events-none absolute left-full top-1/2 ml-1.5 -translate-y-1/2 whitespace-nowrap rounded-full bg-white px-2.5 text-[13px] leading-[24px] font-medium text-[#031b08] shadow">${esc(m.label)}</span></span>`,
      size: [40, 24],
      anchor: [20, 12],
    };
  }
  if (m.kind === "start") {
    return {
      html: `<span class="block h-3 w-3 rounded-full border-[3px] border-[#031b08] bg-white shadow" title="${esc(m.ariaLabel)}"></span>`,
      size: [12, 12],
      anchor: [6, 6],
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
    // Not animated: Leaflet drops a new view asked for while a zoom is still
    // animating, so a vehicle's run path arriving a moment after the vehicle
    // was chosen would never be fitted.
    if (fit.length === 1) m.setView([fit[0].lat, fit[0].lon], 13, { animate: false });
    else m.fitBounds(lf.latLngBounds(fit.map((p) => [p.lat, p.lon] as [number, number])), { padding: [48, 48], maxZoom: 14, animate: false });
  }, [ready, props.fit]);

  // Redraw only when what is drawn changed, not on every parent render or poll
  // that returns the same positions: a redraw replaces each marker's element,
  // which drops keyboard focus from a vehicle the user is on.
  const drawn = useMemo(
    () => JSON.stringify([props.markers, props.lines ?? [], props.selectedId ?? null, Boolean(props.clusterVehicles)]),
    [props.markers, props.lines, props.selectedId, props.clusterVehicles],
  );

  // Keyed by id: a marker that moved glides to its new place, one whose look
  // changed gets a new icon, and nothing else is touched. Rebuilding every
  // marker on each pushed position would make the trucks jump and blink, and
  // drop keyboard focus from the vehicle the user is on.
  const drawnMarkers = useRef(new Map<string, Drawn>());
  const drawnLines = useRef(new Map<string, Leaflet.Polyline>());

  useEffect(() => {
    const m = map.current;
    const lf = L.current;
    const group = layer.current;
    const props = latest.current;
    if (!ready || !m || !lf || !group) return;

    const lines = (props.lines ?? []).filter((line) => line.points.length >= 2);
    for (const [id, polyline] of drawnLines.current) {
      if (!lines.some((line) => line.id === id)) {
        polyline.remove();
        drawnLines.current.delete(id);
      }
    }
    for (const line of lines) {
      const points = line.points.map((p) => [p.lat, p.lon] as [number, number]);
      const style = {
        color: "#0e766d",
        weight: line.style === "driven" ? 5 : 4,
        opacity: line.faded ? 0.45 : 0.9,
        dashArray: line.style === "planned" ? "6 5" : undefined,
        interactive: false,
      };
      const existing = drawnLines.current.get(line.id);
      if (existing) {
        existing.setLatLngs(points);
        existing.setStyle(style);
      } else {
        drawnLines.current.set(line.id, lf.polyline(points, style).addTo(group));
      }
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

    for (const [id, drawn] of drawnMarkers.current) {
      if (!markers.some((mk) => mk.id === id)) {
        cancelAnimationFrame(drawn.frame);
        drawn.marker.remove();
        drawnMarkers.current.delete(id);
      }
    }
    const someoneChosen = markers.some((mk) => mk.kind === "vehicle" && mk.id === props.selectedId);
    for (const mk of markers) {
      const selected = mk.id === props.selectedId;
      const icon = iconHtml(mk, selected, someoneChosen && !selected && mk.kind === "vehicle");
      const divIcon = () => lf.divIcon({ html: icon.html, className: "", iconSize: icon.size, iconAnchor: icon.anchor });
      const zIndexOffset = mk.kind === "vehicle" ? (selected ? 1100 : 1000) : mk.kind === "cluster" ? 900 : mk.kind === "depot" ? 600 : mk.kind === "start" ? 500 : 0;
      let drawn = drawnMarkers.current.get(mk.id);
      if (!drawn) {
        const marker = lf.marker([mk.lat, mk.lon], {
          icon: divIcon(),
          keyboard: mk.selectable !== false,
          interactive: mk.selectable !== false,
          zIndexOffset,
          title: mk.ariaLabel,
        }).addTo(group);
        drawn = { marker, html: icon.html, frame: 0, kind: mk.kind };
        drawnMarkers.current.set(mk.id, drawn);
        if (mk.selectable !== false) {
          const id = mk.id;
          marker.on("click", (e) => {
            lf.DomEvent.stopPropagation(e);
            if (drawnMarkers.current.get(id)?.kind === "cluster") {
              const at = marker.getLatLng();
              m.setView(at, Math.min(m.getZoom() + 2, MAX_ZOOM));
            } else latest.current.onSelect?.(id);
          });
          marker.on("mouseover", () => latest.current.onHover?.(id));
          marker.on("mouseout", () => latest.current.onHover?.(null));
        }
      } else {
        drawn.kind = mk.kind;
        if (drawn.html !== icon.html) {
          drawn.marker.setIcon(divIcon());
          drawn.html = icon.html;
        }
        drawn.marker.setZIndexOffset(zIndexOffset);
        glide(drawn, mk);
      }
      const el = drawn.marker.getElement();
      if (el && mk.selectable !== false) {
        el.setAttribute("role", "button");
        el.setAttribute("aria-label", mk.ariaLabel);
        el.setAttribute("aria-pressed", String(selected));
        el.setAttribute("title", mk.ariaLabel);
      }
    }
  }, [ready, drawn, zoom]);

  // The map is gone: so are its markers, and any glide still running.
  useEffect(
    () => () => {
      for (const drawn of drawnMarkers.current.values()) cancelAnimationFrame(drawn.frame);
      drawnMarkers.current.clear();
      drawnLines.current.clear();
    },
    [],
  );

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

type Drawn = { marker: Leaflet.Marker; html: string; frame: number; kind: MapMarker["kind"] };

/** How long a vehicle takes to move to its next fix on screen. */
const GLIDE_MS = 800;
/** Further than this is a jump (a reconnect, a new day), not driving: drawn there at once. */
const GLIDE_MAX_M = 3_000;

/** Moves a marker to its new place over {@link GLIDE_MS}, or at once under reduced motion. */
function glide(drawn: Drawn, to: LatLon): void {
  const from = drawn.marker.getLatLng();
  if (from.lat === to.lat && from.lng === to.lon) return;
  cancelAnimationFrame(drawn.frame);
  const still = typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
  if (still || metres({ lat: from.lat, lon: from.lng }, to) > GLIDE_MAX_M) {
    drawn.marker.setLatLng([to.lat, to.lon]);
    return;
  }
  const start = performance.now();
  const step = (now: number) => {
    const t = Math.min(1, (now - start) / GLIDE_MS);
    const eased = 1 - (1 - t) ** 3;
    drawn.marker.setLatLng([from.lat + (to.lat - from.lat) * eased, from.lng + (to.lon - from.lng) * eased]);
    if (t < 1) drawn.frame = requestAnimationFrame(step);
  };
  drawn.frame = requestAnimationFrame(step);
}

function pointOf(m: Leaflet.Map, at: LatLon): { x: number; y: number } {
  const p = m.latLngToLayerPoint([at.lat, at.lon]);
  return { x: p.x, y: p.y };
}
