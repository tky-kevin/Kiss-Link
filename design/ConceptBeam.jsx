// ConceptBeam.jsx — minimal, premium, editorial direction
const BEAM = {
  bg: '#f4f2ec',
  ink: '#23201b',
  muted: '#9a948a',
  track: '#dcd8cf',
  accent: '#b07a32',
};

function BeamConcept({ debugPhase, debugT } = {}) {
  const steps = React.useMemo(() => ([
    { name: 'ready',    dur: 3000 },
    { name: 'align',    dur: 1700 },
    { name: 'link',     dur: 1400 },
    { name: 'transfer', dur: 3600 },
    { name: 'done',     dur: 2000 },
  ]), []);
  const _m = usePhaseMachine(steps);
  const phase = debugPhase || _m.phase;
  const t = debugT != null ? debugT : _m.t;
  const restart = _m.restart;

  // geometry
  const TOP = 30, BOT = 250, LEN = BOT - TOP;
  const showPeer = phase !== 'ready';
  const drawT = phase === 'align' ? ease.inOut(t) : (phase === 'ready' ? 0 : 1);
  const lineTop = BOT - LEN * drawT; // neutral line draws upward
  const progress = phase === 'transfer' ? ease.inOut(t) : (phase === 'done' ? 1 : 0);
  const accTop = BOT - LEN * progress; // accent fill tip
  const pct = Math.round(progress * 100);

  const label = {
    ready: 'NFC · READY', align: 'ALIGNING', link: 'LINKED',
    transfer: 'TRANSFERRING', done: 'COMPLETE',
  }[phase];

  const activeFile = phase === 'transfer' ? Math.min(FILES.length - 1, Math.floor(progress * FILES.length)) : -1;

  return (
    <div style={{
      position: 'absolute', inset: 0, background: BEAM.bg, color: BEAM.ink,
      fontFamily: "'Hanken Grotesk', system-ui, sans-serif",
      display: 'flex', flexDirection: 'column', overflow: 'hidden',
    }}>
      {/* label */}
      <div style={{ padding: '26px 28px 0', display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <span key={label} style={{ fontSize: 12, letterSpacing: 4, fontWeight: 600, color: BEAM.muted, animation: 'fadeUp .4s ease both' }}>{label}</span>
        <span style={{ fontSize: 12, letterSpacing: 1, fontWeight: 600, color: BEAM.muted }}>Beam</span>
      </div>
      <div style={{ height: 1, background: BEAM.track, margin: '16px 28px 0' }} />

      {/* stage: beam */}
      <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: 0 }}>
        <svg width="60" height="280" viewBox="0 0 60 280" style={{ overflow: 'visible' }}>
          {/* peer dot (top) */}
          <circle cx="30" cy={TOP} r="6" fill="none" stroke={phase === 'done' ? BEAM.accent : BEAM.ink}
            strokeWidth="1.5" opacity={showPeer ? 1 : 0} style={{ transition: 'opacity .5s, stroke .4s' }} />
          {phase === 'done' && <path d="M27 30l2 2 4-4" stroke={BEAM.accent} strokeWidth="1.6" fill="none" strokeLinecap="round" strokeLinejoin="round" />}
          {/* neutral track (drawn during align) */}
          {phase !== 'ready' && (
            <line x1="30" y1={BOT} x2="30" y2={lineTop} stroke={BEAM.track} strokeWidth="1.5" />
          )}
          {/* accent progress fill */}
          {(phase === 'transfer' || phase === 'done') && (
            <line x1="30" y1={BOT} x2="30" y2={accTop} stroke={BEAM.accent} strokeWidth="1.5" />
          )}
          {/* traveling bead */}
          {phase === 'transfer' && (
            <circle cx="30" cy={accTop} r="4" fill={BEAM.accent} style={{ filter: 'drop-shadow(0 0 5px rgba(176,122,50,0.6))' }} />
          )}
          {/* you dot (bottom) */}
          <circle cx="30" cy={BOT} r="6" fill={BEAM.ink} />
        </svg>
      </div>

      {/* headline */}
      <div style={{ padding: '0 28px', textAlign: 'center', minHeight: 96 }}>
        {(phase === 'transfer' || phase === 'done') ? (
          <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'center', gap: 2 }}>
            <span style={{ fontSize: 76, fontWeight: 200, letterSpacing: -2, lineHeight: 1, fontVariantNumeric: 'tabular-nums' }}>{pct}</span>
            <span style={{ fontSize: 24, fontWeight: 300, marginTop: 8, color: BEAM.muted }}>%</span>
          </div>
        ) : (
          <div key={phase} style={{ fontSize: 40, fontWeight: 200, letterSpacing: -1, animation: 'fadeUp .5s ease both' }}>
            {{ ready: 'Ready to beam', align: 'Hold steady', link: 'Devices linked' }[phase]}
          </div>
        )}
        <div style={{ fontSize: 14, color: BEAM.muted, marginTop: 10, fontWeight: 500 }}>
          {phase === 'done' ? `Sent to ${PAYLOAD.peer}` : `${PAYLOAD.count} files · ${PAYLOAD.size}`}
        </div>
      </div>

      {/* file rows */}
      <div style={{ padding: '14px 28px 30px' }}>
        {FILES.map((f, i) => {
          const done = phase === 'done' || (phase === 'transfer' && i < activeFile);
          const active = i === activeFile;
          return (
            <div key={f.name} style={{
              display: 'flex', alignItems: 'center', gap: 14, padding: '11px 0',
              borderTop: i === 0 ? 'none' : `1px solid ${BEAM.track}`,
              color: active ? BEAM.accent : BEAM.ink,
              opacity: (done || active || phase === 'ready' || phase === 'align' || phase === 'link') ? 1 : 0.4,
              transition: 'color .3s, opacity .3s',
            }}>
              <span style={{ width: 6, height: 6, borderRadius: '50%', background: done ? BEAM.accent : (active ? BEAM.accent : BEAM.track), flexShrink: 0 }} />
              <span style={{ flex: 1, fontSize: 15, fontWeight: 500, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{f.name}</span>
              <span style={{ fontSize: 13, color: active ? BEAM.accent : BEAM.muted, fontWeight: 500 }}>
                {done ? '✓' : f.size}
              </span>
            </div>
          );
        })}
      </div>

      <ReplayChip onClick={restart} color={BEAM.accent} bg="rgba(176,122,50,0.1)" />
    </div>
  );
}

window.BeamConcept = BeamConcept;
