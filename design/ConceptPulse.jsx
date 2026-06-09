// ConceptPulse.jsx — dark, futuristic radar/pulse direction
const PULSE = {
  bg: 'radial-gradient(ellipse 120% 90% at 50% 30%, #121925 0%, #0a0e16 45%, #05070c 100%)',
  text: '#eaf1f8',
  muted: '#7f8da0',
  accent: '#54d2f6',
  accent2: '#6a8bff',
  glow: 'rgba(84,210,246,0.55)',
};

function PulseConcept({ debugPhase, debugT } = {}) {
  const steps = React.useMemo(() => ([
    { name: 'standby',  dur: 3000 },
    { name: 'detect',   dur: 1500 },
    { name: 'ready',    dur: 1600 },
    { name: 'transfer', dur: 3400 },
    { name: 'done',     dur: 1800 },
  ]), []);
  const _m = usePhaseMachine(steps);
  const phase = debugPhase || _m.phase;
  const t = debugT != null ? debugT : _m.t;
  const restart = _m.restart;

  const connected = phase === 'detect' || phase === 'ready' || phase === 'transfer' || phase === 'done';
  const beaming = phase === 'transfer';
  const progress = phase === 'transfer' ? Math.round(ease.out(t) * 100)
                  : (phase === 'done' ? 100 : 0);

  const R = 64, C = 2 * Math.PI * R;
  const peerScale = phase === 'standby' ? 0 : 1;

  const headline = {
    standby: 'Searching nearby',
    detect: 'Device found',
    ready: 'Ready to send',
    transfer: 'Sending',
    done: 'Sent',
  }[phase];
  const sub = {
    standby: 'Hold devices back to back',
    detect: PAYLOAD.peer,
    ready: `${PAYLOAD.count} files · ${PAYLOAD.size}`,
    transfer: `${progress}%`,
    done: `${PAYLOAD.count} files · ${PAYLOAD.size}`,
  }[phase];

  return (
    <div style={{
      position: 'absolute', inset: 0, background: PULSE.bg, color: PULSE.text,
      fontFamily: "'Space Grotesk', system-ui, sans-serif",
      display: 'flex', flexDirection: 'column', overflow: 'hidden',
    }}>
      {/* top label */}
      <div style={{ padding: '20px 24px 0', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
          <div style={{ width: 18, height: 18, color: PULSE.accent }}>{Icons.nfc(PULSE.accent)}</div>
          <span style={{ fontSize: 13, letterSpacing: 2, textTransform: 'uppercase', fontFamily: "'DM Mono', monospace", color: PULSE.muted }}>Pulse</span>
        </div>
        <span style={{ fontSize: 12, fontFamily: "'DM Mono', monospace", color: PULSE.muted, letterSpacing: 1 }}>NFC · ACTIVE</span>
      </div>

      {/* stage */}
      <div style={{ height: 430, position: 'relative', flexShrink: 0 }}>
        {/* peer node (top) */}
        <div style={{
          position: 'absolute', left: '50%', top: 24, transform: `translate(-50%, 0) scale(${peerScale})`,
          transition: 'transform .6s cubic-bezier(.34,1.56,.64,1)',
          display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10, zIndex: 4,
        }}>
          <div style={{
            width: 56, height: 56, borderRadius: '50%',
            background: phase === 'done' ? PULSE.accent : 'rgba(106,139,255,0.16)',
            border: `1.5px solid ${PULSE.accent2}`,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            boxShadow: connected ? `0 0 24px ${PULSE.glow}` : 'none',
            transition: 'background .4s, box-shadow .4s',
          }}>
            {phase === 'done'
              ? <div style={{ width: 26, height: 26, color: '#06080c' }}>{Icons.check('#06080c', 3)}</div>
              : <div style={{ width: 22, height: 22, borderRadius: '50%', border: `2px solid ${PULSE.accent2}` }} />}
          </div>
          <span style={{ fontSize: 12, fontFamily: "'DM Mono', monospace", color: PULSE.muted }}>{PAYLOAD.peer}</span>
        </div>

        {/* connection beam */}
        <div style={{
          position: 'absolute', left: '50%', top: 80, transform: 'translateX(-50%)',
          width: 2, height: 190, zIndex: 1,
          background: `linear-gradient(${PULSE.accent}, ${PULSE.accent2})`,
          opacity: connected ? 0.55 : 0,
          transition: 'opacity .5s',
          maskImage: 'linear-gradient(transparent, #000 12%, #000 88%, transparent)',
          WebkitMaskImage: 'linear-gradient(transparent, #000 12%, #000 88%, transparent)',
        }}>
          {beaming && [0,1,2,3,4].map(i => (
            <div key={i} style={{
              position: 'absolute', left: -3, bottom: 0, width: 8, height: 8, borderRadius: '50%',
              background: PULSE.accent, boxShadow: `0 0 12px ${PULSE.glow}`,
              animation: `pulseParticle 1.1s linear ${i * 0.22}s infinite`,
            }} />
          ))}
        </div>

        {/* hub orb (center) */}
        <div style={{
          position: 'absolute', left: '50%', top: 320, transform: 'translate(-50%,-50%)',
          width: 220, height: 220, zIndex: 3,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          {/* radar rings (standby/detect only) */}
          {(phase === 'standby' || phase === 'detect') && [0,1,2].map(i => (
            <div key={i} style={{
              position: 'absolute', width: 120, height: 120, borderRadius: '50%',
              border: `1.5px solid ${PULSE.accent}`,
              animation: `pulseRing 3s cubic-bezier(.2,.6,.3,1) ${i * 1}s infinite`,
            }} />
          ))}

          {/* progress ring (transfer/done) */}
          {(phase === 'transfer' || phase === 'done') && (
            <svg width="200" height="200" style={{ position: 'absolute', transform: 'rotate(-90deg)' }}>
              <circle cx="100" cy="100" r={R} fill="none" stroke="rgba(255,255,255,0.08)" strokeWidth="3" />
              <circle cx="100" cy="100" r={R} fill="none" stroke={PULSE.accent} strokeWidth="3"
                strokeLinecap="round" strokeDasharray={C} strokeDashoffset={C * (1 - progress / 100)}
                style={{ filter: `drop-shadow(0 0 6px ${PULSE.glow})` }} />
            </svg>
          )}

          {/* burst on done */}
          {phase === 'done' && (
            <div style={{
              position: 'absolute', width: 140, height: 140, borderRadius: '50%',
              border: `2px solid ${PULSE.accent}`, animation: 'pulseBurst .9s ease-out forwards',
            }} />
          )}

          {/* core */}
          <div style={{
            width: 128, height: 128, borderRadius: '50%',
            background: 'radial-gradient(circle at 38% 32%, rgba(84,210,246,0.30), rgba(106,139,255,0.06) 70%)',
            border: `1px solid rgba(84,210,246,0.35)`,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            boxShadow: `0 0 50px ${PULSE.glow}, inset 0 0 30px rgba(84,210,246,0.15)`,
            animation: phase === 'standby' ? 'pulseBreathe 2.4s ease-in-out infinite' : 'none',
          }}>
            <div style={{ width: 40, height: 40, color: PULSE.accent }}>
              {phase === 'done'
                ? <div style={{ width: 40, height: 40 }}>{Icons.check(PULSE.accent, 2.4)}</div>
                : Icons.nfc(PULSE.accent)}
            </div>
          </div>
        </div>

      </div>

      {/* status text */}
      <div style={{ textAlign: 'center', padding: '4px 24px 0', flexShrink: 0 }}>
        <div key={headline} style={{ fontSize: 30, fontWeight: 600, letterSpacing: -0.5, animation: 'fadeUp .5s ease both' }}>{headline}</div>
        <div key={sub} style={{ fontSize: 14, color: PULSE.muted, marginTop: 6, fontFamily: "'DM Mono', monospace", animation: 'fadeUp .5s .05s ease both' }}>{sub}</div>
      </div>

      {/* bottom file rail */}
      <div style={{
        padding: '16px 18px 22px', display: 'flex', gap: 10, marginTop: 'auto',
        background: 'linear-gradient(transparent, rgba(8,11,18,0.6))',
      }}>
        {FILES.map((f, i) => {
          const sent = phase === 'done' || (phase === 'transfer' && progress > (i + 0.5) / FILES.length * 100);
          return (
            <div key={f.name} style={{
              flex: 1, aspectRatio: '1', position: 'relative', borderRadius: 14, overflow: 'hidden',
              opacity: sent ? 0.45 : 1, transition: 'opacity .4s',
            }}>
              <PhotoTile hue={f.hue} kind={f.kind} size={'100%'} radius={14} dark />
              {sent && (
                <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(6,8,12,0.45)' }}>
                  <div style={{ width: 22, height: 22, color: PULSE.accent }}>{Icons.check(PULSE.accent, 3)}</div>
                </div>
              )}
            </div>
          );
        })}
      </div>

      <ReplayChip onClick={restart} color={PULSE.accent} bg="rgba(84,210,246,0.12)" />
    </div>
  );
}

window.PulseConcept = PulseConcept;
