// shared.jsx — phase machine, tokens, sample data, primitives
// Shared across the three NFC transfer concepts.

const { useState, useEffect, useRef, useCallback } = React;

// ─────────────────────────────────────────────────────────────
// Phase machine — drives the looping demo through named phases.
// steps: [{ name, dur }]  (dur in ms)
// Returns { phase, index, t (0..1 local), restart, goTo }
// On the final step it holds for loopHold then restarts (if loop).
// ─────────────────────────────────────────────────────────────
// Driven by a wall-clock (Date.now), pumped by BOTH requestAnimationFrame
// and a setInterval fallback — so the timeline keeps advancing even when
// rAF is throttled/paused (e.g. in a backgrounded/preview iframe).
function usePhaseMachine(steps, { loop = true, loopHold = 2000 } = {}) {
  const [index, setIndex] = useState(0);
  const [t, setT] = useState(0);
  const startRef = useRef(Date.now());
  const idxRef = useRef(0);

  const durs = steps.map((s) => s.dur);
  const total = durs.reduce((a, b) => a + b, 0);
  const cycle = total + loopHold;

  const pump = useCallback(() => {
    let e = Date.now() - startRef.current;
    if (loop) e = ((e % cycle) + cycle) % cycle;
    let idx = steps.length - 1;
    let lt = 1;
    if (e < total) {
      let acc = 0;
      for (let i = 0; i < steps.length; i++) {
        if (e < acc + durs[i]) { idx = i; lt = (e - acc) / durs[i]; break; }
        acc += durs[i];
      }
    }
    // during loopHold (e >= total) park on final step at t=1
    setT(lt);
    if (idx !== idxRef.current) { idxRef.current = idx; setIndex(idx); }
  }, [steps, loop, loopHold, cycle, total]);

  useEffect(() => {
    let raf = 0;
    const loopRaf = () => { pump(); raf = requestAnimationFrame(loopRaf); };
    raf = requestAnimationFrame(loopRaf);
    const iv = setInterval(pump, 80); // fallback when rAF is paused/throttled
    return () => { cancelAnimationFrame(raf); clearInterval(iv); };
  }, [pump]);

  const restart = useCallback(() => {
    startRef.current = Date.now();
    idxRef.current = 0;
    setIndex(0);
    setT(0);
  }, []);

  return { phase: steps[index].name, index, t, restart };
}

// easing helpers
const ease = {
  out: (x) => 1 - Math.pow(1 - x, 3),
  inOut: (x) => (x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2),
  outBack: (x) => { const c1 = 1.70158, c3 = c1 + 1; return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2); },
  outElastic: (x) => { if (x === 0 || x === 1) return x; const c4 = (2 * Math.PI) / 3; return Math.pow(2, -10 * x) * Math.sin((x * 10 - 0.75) * c4) + 1; },
};
const clamp01 = (x) => Math.max(0, Math.min(1, x));
// map local t of a sub-window [a,b] to 0..1
const win = (t, a, b) => clamp01((t - a) / (b - a));

// ─────────────────────────────────────────────────────────────
// Sample payload
// ─────────────────────────────────────────────────────────────
const FILES = [
  { name: 'IMG_4821.HEIC', size: '4.2 MB', kind: 'photo', hue: 28 },
  { name: 'IMG_4822.HEIC', size: '5.1 MB', kind: 'photo', hue: 196 },
  { name: 'Rooftop.mp4',   size: '38.7 MB', kind: 'video', hue: 268 },
];
const PAYLOAD = { count: 3, size: '48 MB', peer: 'Maya · Pixel 9' };

// A subtle striped placeholder "photo" tile.
function PhotoTile({ hue = 200, kind = 'photo', size = 56, radius = 12, dark = false }) {
  const a = `oklch(0.62 0.12 ${hue})`;
  const b = `oklch(0.46 0.13 ${hue})`;
  return (
    <div style={{
      width: size, height: size, borderRadius: radius, flexShrink: 0,
      background: `linear-gradient(135deg, ${a}, ${b})`,
      position: 'relative', overflow: 'hidden',
      boxShadow: dark ? 'inset 0 0 0 1px rgba(255,255,255,0.08)' : 'inset 0 0 0 1px rgba(0,0,0,0.06)',
    }}>
      <div style={{
        position: 'absolute', inset: 0,
        backgroundImage: 'repeating-linear-gradient(135deg, rgba(255,255,255,0.10) 0 6px, transparent 6px 12px)',
      }} />
      {kind === 'video' && (
        <div style={{
          position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          <div style={{
            width: 0, height: 0, marginLeft: 2,
            borderTop: `${size*0.11}px solid transparent`,
            borderBottom: `${size*0.11}px solid transparent`,
            borderLeft: `${size*0.18}px solid rgba(255,255,255,0.92)`,
          }} />
        </div>
      )}
    </div>
  );
}

// minimal icons
const Icons = {
  check: (c = 'currentColor', w = 2.4) => (
    <svg viewBox="0 0 24 24" fill="none" width="100%" height="100%">
      <path d="M5 12.5l4.5 4.5L19 7" stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  ),
  chevron: (c = 'currentColor', w = 2) => (
    <svg viewBox="0 0 24 24" fill="none" width="100%" height="100%">
      <path d="M6 9l6 6 6-6" stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  ),
  nfc: (c = 'currentColor', w = 2) => (
    <svg viewBox="0 0 24 24" fill="none" width="100%" height="100%">
      <path d="M6.5 7.5a8 8 0 010 9M10 6a12 12 0 010 12" stroke={c} strokeWidth={w} strokeLinecap="round" opacity="0.55" />
      <path d="M14 4.5a16 16 0 010 15M17.5 3a20 20 0 010 18" stroke={c} strokeWidth={w} strokeLinecap="round" opacity="0.3" />
    </svg>
  ),
  arrowUp: (c = 'currentColor', w = 2.2) => (
    <svg viewBox="0 0 24 24" fill="none" width="100%" height="100%">
      <path d="M12 19V5M6 11l6-6 6 6" stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  ),
  replay: (c = 'currentColor', w = 2) => (
    <svg viewBox="0 0 24 24" fill="none" width="100%" height="100%">
      <path d="M4 12a8 8 0 108-8M4 12V6m0 6h6" stroke={c} strokeWidth={w} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  ),
};

// small replay chip used by every concept
function ReplayChip({ onClick, color = '#fff', bg = 'rgba(255,255,255,0.1)' }) {
  return (
    <button onClick={onClick} style={{
      position: 'absolute', bottom: 14, right: 14, zIndex: 30,
      width: 34, height: 34, borderRadius: 999, border: 'none', cursor: 'pointer',
      background: bg, color, padding: 8,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      backdropFilter: 'blur(8px)', WebkitBackdropFilter: 'blur(8px)',
    }} title="Replay">
      {Icons.replay(color)}
    </button>
  );
}

Object.assign(window, {
  usePhaseMachine, ease, clamp01, win,
  FILES, PAYLOAD, PhotoTile, Icons, ReplayChip,
});
