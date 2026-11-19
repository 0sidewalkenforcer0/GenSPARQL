/* native-graph.js: the small selection API that app.js draws the knowledge graph with, and the
 * zoom, drag and force simulation behind it.
 *
 * When the page has loaded d3 (docs/vendor), zoom and drag are d3-zoom and d3-drag, and the
 * forces are d3-force: a velocity Verlet integration with a Barnes-Hut many-body force, in which
 * a dragged node pulls its neighbours along. Without d3 the fallbacks below take over, and
 * ?print keeps the built-in simulation, so the figures of the paper do not change.
 */
(() => {
  "use strict";

  const NS = "http://www.w3.org/2000/svg";
  const d3 = window.d3;
  const hasD3 = Boolean(d3 && d3.zoom && d3.drag && d3.select);
  const val = (v, e, i) => (typeof v === "function" ? v.call(e, e.__data__, i) : v);
  // A press that moves the pointer at most this many screen pixels is a click, not a drag or a
  // pan, so that a press that jitters by a pixel still clicks (d3 counts any movement).
  const CLICK_DISTANCE = 4;

  // ------------------------------------------------------------------ selection

  class Selection {
    constructor(elements, parents = []) {
      this.elements = (elements || []).filter(Boolean);
      this.parents = parents;
      this.bound = [];
    }
    selectAll(q) {
      return new Selection(this.elements.flatMap(e => [...e.querySelectorAll(q)]), this.elements);
    }
    select(q) {
      return new Selection(this.elements.map(e => {
        const child = e.querySelector(q);
        if (child && child.__data__ === undefined) child.__data__ = e.__data__;
        return child;
      }), this.elements);
    }
    data(d) { this.bound = [...(d || [])]; return this; }
    join(tag) {
      const p = this.parents[0];
      if (!p) return new Selection([]);
      this.elements.forEach(e => e.remove());
      return new Selection(this.bound.map(d => {
        const e = document.createElementNS(NS, tag);
        e.__data__ = d;
        p.append(e);
        return e;
      }), [p]);
    }
    append(tag) {
      return new Selection(this.elements.map(p => {
        const e = document.createElementNS(NS, tag);
        e.__data__ = p.__data__;
        p.append(e);
        return e;
      }), this.elements);
    }
    attr(n, v) {
      this.elements.forEach((e, i) => {
        const x = val(v, e, i);
        x == null ? e.removeAttribute(n) : e.setAttribute(n, String(x));
      });
      return this;
    }
    style(n, v, priority = "") {
      this.elements.forEach((e, i) => {
        const x = val(v, e, i);
        x == null ? e.style.removeProperty(n) : e.style.setProperty(n, String(x), priority);
      });
      return this;
    }
    text(v) { this.elements.forEach((e, i) => e.textContent = val(v, e, i) ?? ""); return this; }
    classed(n, v) { this.elements.forEach((e, i) => e.classList.toggle(n, !!val(v, e, i))); return this; }
    filter(fn) { return new Selection(this.elements.filter((e, i) => fn.call(e, e.__data__, i)), this.parents); }
    each(fn) { this.elements.forEach((e, i) => fn.call(e, e.__data__, i)); return this; }
    // A listener registered here replaces an earlier one of the same typed name. A null listener
    // also removes a d3 listener of that name, so that svg.on("dblclick.zoom", null) switches off
    // the double-click zoom of d3-zoom as it does in d3.
    on(type, fn) {
      const eventType = type.split(".")[0];
      this.elements.forEach(e => {
        const k = `__ng_${type}`;
        if (e[k]) e.removeEventListener(eventType, e[k]);
        if (fn) {
          e[k] = event => fn.call(e, event, e.__data__);
          e.addEventListener(eventType, e[k]);
        } else {
          delete e[k];
          if (hasD3 && type.includes(".")) d3.select(e).on(type, null);
        }
      });
      return this;
    }
    call(fn, ...args) { fn(this, ...args); return this; }
    remove() { this.elements.forEach(e => e.remove()); return this; }
    transition() { return this; }
    duration() { return this; }
    node() { return this.elements[0] || null; }
  }
  const select = q => new Selection([typeof q === "string" ? document.querySelector(q) : q]);

  // ------------------------------------------------------------------ zoom and drag

  // The pointer is in screen pixels, the graph in those of the page, which a zoom of the page
  // (styles.css) makes larger on screen.
  const pageScale = svg => (svg.clientWidth ? svg.getBoundingClientRect().width / svg.clientWidth : 1);
  const point = (event, svg) => {
    const r = svg.getBoundingClientRect(), z = pageScale(svg);
    return { x: (event.clientX - r.left) / z, y: (event.clientY - r.top) / z };
  };
  const transform = (x = 0, y = 0, k = 1) => ({
    x, y, k,
    translate(dx, dy) { return transform(this.x + dx, this.y + dy, this.k); },
    scale(s) { return transform(this.x, this.y, this.k * s); },
    toString() { return `translate(${this.x} ${this.y}) scale(${this.k})`; }
  });

  /** d3-drag on every element of a selection; the listeners get (event, datum) as in d3. */
  function d3Drag() {
    const behavior = sel => sel.elements.forEach(el => d3.select(el).call(behavior.d3));
    behavior.d3 = d3.drag().clickDistance(CLICK_DISTANCE);
    behavior.on = (name, fn) => { behavior.d3.on(name, fn); return behavior; };
    return behavior;
  }

  /** The fallback drag without d3: pointer events, positions in the zoomed coordinate system. */
  function nativeDrag() {
    const h = {};
    const behavior = sel => sel.elements.forEach(el => {
      el.style.touchAction = "none";
      el.addEventListener("pointerdown", down => {
        if (down.button !== 0) return;
        down.stopPropagation();
        const svg = el.ownerSVGElement;
        const emit = (name, event) => {
          const p = point(event, svg), t = svg.__ngTransform || transform();
          h[name]?.call(el, { x: (p.x - t.x) / t.k, y: (p.y - t.y) / t.k, active: name === "drag" ? 1 : 0, sourceEvent: event }, el.__data__);
        };
        emit("start", down);
        const move = e => emit("drag", e);
        const up = e => {
          emit("end", e);
          window.removeEventListener("pointermove", move);
          window.removeEventListener("pointerup", up);
        };
        window.addEventListener("pointermove", move);
        window.addEventListener("pointerup", up);
      });
    });
    behavior.on = (n, f) => (h[n] = f, behavior);
    return behavior;
  }

  /** d3-zoom with the calls app.js makes: scaleExtent, on("zoom"), transform and scaleBy. */
  function d3Zoom() {
    const z = d3.zoom().clickDistance(CLICK_DISTANCE);
    const behavior = sel => sel.elements.forEach(svg => d3.select(svg).call(z));
    behavior.d3 = z;
    behavior.scaleExtent = x => { z.scaleExtent(x); return behavior; };
    behavior.on = (name, fn) => { z.on(name, fn); return behavior; };
    behavior.transform = (sel, t) => sel.elements.forEach(svg => d3.select(svg).call(z.transform, t));
    behavior.scaleBy = (sel, s) => sel.elements.forEach(svg => d3.select(svg).call(z.scaleBy, s));
    return behavior;
  }

  /** The fallback zoom without d3: wheel to scale about the pointer, drag the canvas to pan. */
  function nativeZoom() {
    let limits = [.12, 6];
    const h = {};
    const apply = (svg, t) => { svg.__ngTransform = t; h.zoom?.({ transform: t }); };
    const behavior = sel => sel.elements.forEach(svg => {
      if (svg.__ngZoom) return;
      svg.__ngZoom = true;
      svg.__ngTransform = transform();
      svg.style.touchAction = "none";
      svg.addEventListener("wheel", e => {
        e.preventDefault();
        const old = svg.__ngTransform, p = point(e, svg);
        const k = Math.max(limits[0], Math.min(limits[1], old.k * (e.deltaY < 0 ? 1.15 : 1 / 1.15)));
        apply(svg, transform(p.x - (p.x - old.x) / old.k * k, p.y - (p.y - old.y) / old.k * k, k));
      }, { passive: false });
      svg.addEventListener("pointerdown", e => {
        if (e.button !== 0 || e.target.closest?.(".node")) return;
        const start = { x: e.clientX, y: e.clientY, t: svg.__ngTransform }, z = pageScale(svg);
        const move = m => apply(svg, transform(start.t.x + (m.clientX - start.x) / z, start.t.y + (m.clientY - start.y) / z, start.t.k));
        const up = () => {
          window.removeEventListener("pointermove", move);
          window.removeEventListener("pointerup", up);
        };
        window.addEventListener("pointermove", move);
        window.addEventListener("pointerup", up);
      });
    });
    behavior.scaleExtent = x => (limits = x, behavior);
    behavior.on = (n, f) => (h[n] = f, behavior);
    behavior.transform = (sel, t) => sel.elements.forEach(svg => apply(svg, t));
    behavior.scaleBy = (sel, s) => sel.elements.forEach(svg => {
      const old = svg.__ngTransform || transform();
      const p = { x: svg.clientWidth / 2, y: svg.clientHeight / 2 };
      const k = Math.max(limits[0], Math.min(limits[1], old.k * s));
      apply(svg, transform(p.x - (p.x - old.x) / old.k * k, p.y - (p.y - old.y) / old.k * k, k));
    });
    return behavior;
  }

  // ------------------------------------------------------------------ built-in forces

  function forceLink(links) {
    let id = n => n.id, d = () => 30, s = () => .7;
    return {
      links, idFn: () => id, distanceFn: () => d, strengthFn: () => s,
      id(v) { id = v; return this; },
      distance(v) { d = typeof v === "function" ? v : () => v; return this; },
      strength(v) { s = typeof v === "function" ? v : () => v; return this; }
    };
  }
  function forceManyBody() {
    let s = () => -80;
    return { strengthFn: () => s, strength(v) { s = typeof v === "function" ? v : () => v; return this; } };
  }
  const forceCenter = (x, y) => ({ x, y });
  function forceCollide() {
    let r = () => 8, it = 1;
    return {
      radiusFn: () => r, iterationsFn: () => it,
      radius(v) { r = typeof v === "function" ? v : () => v; return this; },
      iterations(v) { it = v; return this; }
    };
  }

  function forceSimulation(nodes) {
    // Listeners are kept by their typed name ("end.fit", "end.labels"), as in d3, and every
    // listener of an event is called.
    const forces = new Map(), listeners = new Map();
    const handlers = {
      tick: () => listeners.forEach((f, name) => { if (name.split(".")[0] === "tick") f(); }),
      end: () => listeners.forEach((f, name) => { if (name.split(".")[0] === "end") f(); })
    };
    let frame = 0, alpha = 1, target = 0, running = true, decay = .025, minAlpha = .01, velocity = .22, tickCount = 0;
    nodes.forEach((n, i) => {
      if (!Number.isFinite(n.x)) n.x = Math.cos(i * 2.399) * Math.sqrt(i + 1) * 9;
      if (!Number.isFinite(n.y)) n.y = Math.sin(i * 2.399) * Math.sqrt(i + 1) * 9;
      n.vx ||= 0;
      n.vy ||= 0;
    });

    const tick = () => {
      if (!running) return;
      tickCount++;
      alpha += (target - alpha) * .08;
      const lf = forces.get("link");
      if (lf) lf.links.forEach((l, i) => {
        if (!l.source || !l.target) return;
        const dx = l.target.x - l.source.x, dy = l.target.y - l.source.y, len = Math.hypot(dx, dy) || 1;
        const p = (len - lf.distanceFn()(l, i)) / len * lf.strengthFn()(l, i) * alpha * .08;
        if (l.source.fx == null) { l.source.vx += dx * p; l.source.vy += dy * p; }
        if (l.target.fx == null) { l.target.vx -= dx * p; l.target.vy -= dy * p; }
      });
      const charge = forces.get("charge"), collision = forces.get("collision");
      const windowSize = nodes.length > 800 ? 24 : nodes.length > 300 ? 36 : nodes.length;
      for (let i = 0; i < nodes.length; i++) {
        for (let j = i + 1; j < Math.min(nodes.length, i + windowSize); j++) {
          const a = nodes[i], b = nodes[j], dx = b.x - a.x, dy = b.y - a.y, d2 = dx * dx + dy * dy || .01, len = Math.sqrt(d2);
          if (charge) {
            const p = -charge.strengthFn()(a, i) * alpha * .025 / d2;
            if (a.fx == null) { a.vx -= dx * p; a.vy -= dy * p; }
            if (b.fx == null) { b.vx += dx * p; b.vy += dy * p; }
          }
          if (collision) {
            const min = collision.radiusFn()(a, i) + collision.radiusFn()(b, j);
            if (len < min) {
              const p = (min - len) / len * .12;
              if (a.fx == null) { a.vx -= dx * p; a.vy -= dy * p; }
              if (b.fx == null) { b.vx += dx * p; b.vy += dy * p; }
            }
          }
        }
      }
      const center = forces.get("center"), damping = 1 - velocity;
      if (center) nodes.forEach(n => {
        if (n.fx == null) n.vx += (center.x - n.x) * alpha * .002;
        if (n.fy == null) n.vy += (center.y - n.y) * alpha * .002;
      });
      nodes.forEach(n => {
        if (n.fx != null) { n.x = n.fx; n.vx = 0; } else { n.vx *= damping; n.x += n.vx; }
        if (n.fy != null) { n.y = n.fy; n.vy = 0; } else { n.vy *= damping; n.y += n.vy; }
      });
      if (nodes.length <= 300 || tickCount % 3 === 0 || alpha <= minAlpha * 1.2) handlers.tick?.();
      alpha *= 1 - decay;
      if (alpha > minAlpha || target > 0) frame = requestAnimationFrame(tick);
      else { running = false; frame = 0; handlers.tick?.(); handlers.end?.(); }
    };

    return {
      force(name, f) {
        forces.set(name, f);
        if (name === "link") {
          const map = new Map(nodes.map(n => [f.idFn()(n), n]));
          f.links.forEach(l => {
            if (typeof l.source !== "object") l.source = map.get(l.source);
            if (typeof l.target !== "object") l.target = map.get(l.target);
          });
        }
        return this;
      },
      on(n, f) {
        if (f) listeners.set(n, f); else listeners.delete(n);
        if (n.split(".")[0] === "tick" && !frame && running) frame = requestAnimationFrame(tick);
        return this;
      },
      stop() { running = false; if (frame) cancelAnimationFrame(frame); frame = 0; return this; },
      restart() { if (!running) { running = true; frame = requestAnimationFrame(tick); } return this; },
      alpha(v) { if (v === undefined) return alpha; alpha = v; return this; },
      alphaTarget(v) { target = v; if (v > 0) this.restart(); return this; },
      alphaDecay(v) { if (v === undefined) return decay; decay = Math.max(0, Math.min(1, v)); return this; },
      alphaMin(v) { if (v === undefined) return minAlpha; minAlpha = Math.max(0, v); return this; },
      velocityDecay(v) { if (v === undefined) return velocity; velocity = Math.max(0, Math.min(1, v)); return this; }
    };
  }

  // ------------------------------------------------------------------ the exported API

  window.NativeGraph = {
    select,
    drag: hasD3 ? d3Drag : nativeDrag,
    zoom: hasD3 ? d3Zoom : nativeZoom,
    zoomTransform: n => (hasD3 ? d3.zoomTransform(n) : (n?.__ngTransform || transform())),
    zoomIdentity: hasD3 ? d3.zoomIdentity : transform(),
    forceSimulation, forceLink, forceManyBody, forceCenter, forceCollide,
    min: (a, f = x => x) => Math.min(...a.map(f)),
    max: (a, f = x => x) => Math.max(...a.map(f)),
    interaction: hasD3 ? "d3" : "native",
    clickDistance: CLICK_DISTANCE
  };

  if (d3 && d3.forceSimulation && !document.documentElement.hasAttribute("data-print")) {
    Object.assign(window.NativeGraph, {
      forceSimulation: d3.forceSimulation, forceLink: d3.forceLink, forceManyBody: d3.forceManyBody,
      forceCenter: d3.forceCenter, forceCollide: d3.forceCollide, forceX: d3.forceX, forceY: d3.forceY,
      physics: "d3"
    });
  }
})();
