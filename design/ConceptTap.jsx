// ConceptTap.jsx — light, friendly, Material-ish direction
const TAP = {
  bg: '#fbfaf7',
  card: '#ffffff',
  text: '#1b2420',
  muted: '#6b7670',
  accent: '#16a974',
  accentDark: '#0f7a53',
  tint: '#e7f6ef',
};

function TapConcept({ debugPhase, debugT } = {}) {
  const steps = React.useMemo(() => ([
    { name: 'standby',  dur: 2800 },
    { name: 'detect',   dur: 1500 },
    { name: 'snap',     dur: 1200 },
    { name: 'transfer', dur: 3400 },
    { name: 'done',     dur: 1800 },
  ]), []);
  const _m = usePhaseMachine(steps);
  const phase = debugPhase || _m.phase;
  const t = debugT != null ? debugT : _m.t;
  const restart = _m.restart;

  const near = phase === 'detect';
  const linked = phase === 'snap' || phase === 'transfer' || phase === 'done';
  const progress = phase === 'transfer' ? ease.inOut(t)
                  : (phase === 'done' ? 1 : 0);

  const headline = {
    standby: 'Bring phones together',
    detect: 'Maya is nearby',
    snap: 'Connected!',
    transfer: 'Sending files…',
    done: 'Sent to Maya',
  }[phase];

  const confettiColors = ['#16a974', '#f4a259', '#5b8def', '#e76f8b', '#f2c14e'];

  return (
    <div style={{
      position: 'absolute', inset: 0, background: TAP.bg, color: TAP.text,
      fontFamily: "'Manrope', system-ui, sans-serif",
      display: 'flex', flexDirection: 'column', overflow: 'hidden',
    }}>
      {/* header */}
      <div style={{ padding: '18px 22px 4px', display: 'flex', alignItems: 'center', gap: 10 }}>
        <div style={{
          width: 30, height: 30, borderRadius: 9, background: TAP.tint, color: TAP.accent,
          display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 6,
        }}>{Icons.nfc(TAP.accent)}</div>
        <span style={{ fontSize: 19, fontWeight: 800, letterSpacing: -0.3 }}>Tap</span>
        <span style={{ marginLeft: 'auto', fontSize: 13, fontWeight: 600, color: TAP.muted }}>{PAYLOAD.count} selected</span>
      </div>

      {/* peer target zone */}
      <div style={{ position: 'relative', height: 220, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'flex-end', paddingTop: 30 }}>
        {/* pulsing rings when near */}
        {near && [0,1].map(i => (
          <div key={i} style={{
            position: 'absolute', top: 30, width: 96, height: 96, borderRadius: '50%',
            border: `2px solid ${TAP.accent}`, animation: `tapRing 1.6s ease-out ${i*0.8}s infinite`,
          }} />
        ))}
        {/* confetti */}
        {phase === 'done' && Array.from({ length: 14 }).map((_, i) => (
          <div key={i} style={{
            position: 'absolute', top: 70, left: '50%', width: 9, height: 9, borderRadius: 2,
            background: confettiColors[i % confettiColors.length],
            ['--dx']: `${(Math.random()*2-1)*120}px`, ['--dr']: `${Math.random()*360}deg`,
            animation: `tapConfetti 1s ${(i%5)*0.04}s ease-out forwards`,
          }} />
        ))}
        {/* avatar */}
        <div style={{
          width: 96, height: 96, borderRadius: '50%', position: 'relative', zIndex: 2,
          background: linked ? TAP.accent : TAP.tint,
          color: linked ? '#fff' : TAP.accentDark,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: 38, fontWeight: 800,
          boxShadow: linked ? `0 14px 30px rgba(22,169,116,0.35)` : '0 6px 18px rgba(0,0,0,0.06)',
          transform: phase === 'snap' ? 'scale(1.06)' : 'scale(1)',
          transition: 'background .35s, transform .45s cubic-bezier(.34,1.7,.5,1), box-shadow .35s',
        }}>
          {phase === 'done'
            ? <div style={{ width: 44, height: 44 }}>{Icons.check('#fff', 2.6)}</div>
            : 'M'}
        </div>
        <div style={{ marginTop: 14, fontSize: 14, fontWeight: 600, color: TAP.muted }}>{PAYLOAD.peer}</div>

        {/* down chevrons in standby */}
        {phase === 'standby' && (
          <div style={{ position: 'absolute', top: 6, width: 24, height: 24, color: TAP.accent, animation: 'tapBob 1.4s ease-in-out infinite' }}>
            {Icons.chevron(TAP.accent, 2.4)}
          </div>
        )}
      </div>

      {/* headline */}
      <div style={{ textAlign: 'center', padding: '4px 24px 0' }}>
        <div key={headline} style={{ fontSize: 26, fontWeight: 800, letterSpacing: -0.6, animation: 'fadeUp .45s ease both' }}>{headline}</div>
      </div>

      {/* progress */}
      <div style={{ padding: '16px 40px 0', height: 30 }}>
        {(phase === 'transfer' || phase === 'done') && (
          <div style={{ height: 6, borderRadius: 99, background: TAP.tint, overflow: 'hidden' }}>
            <div style={{ height: '100%', width: `${progress*100}%`, background: TAP.accent, borderRadius: 99, transition: 'width .2s linear' }} />
          </div>
        )}
      </div>

      {/* file deck */}
      <div style={{ flex: 1, position: 'relative' }}>
        {FILES.map((f, i) => {
          const threshold = (i + 0.7) / FILES.length;
          const flown = (phase === 'transfer' && progress > threshold) || phase === 'done';
          const baseRot = (i - 1) * 4;
          return (
            <div key={f.name} style={{
              position: 'absolute', left: '50%', bottom: 36 + i * 8,
              width: 230,
              transform: flown
                ? `translate(-50%, -340px) scale(0.15) rotate(0deg)`
                : `translate(-50%, 0) rotate(${baseRot}deg)`,
              opacity: flown ? 0 : 1,
              transition: 'transform .6s cubic-bezier(.5,-0.3,.3,1), opacity .5s ease',
              zIndex: 10 - i,
            }}>
              <div style={{
                background: TAP.card, borderRadius: 18, padding: 12,
                display: 'flex', alignItems: 'center', gap: 13,
                boxShadow: '0 10px 30px rgba(20,40,30,0.12)',
              }}>
                <PhotoTile hue={f.hue} kind={f.kind} size={52} radius={12} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 15, fontWeight: 700, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{f.name}</div>
                  <div style={{ fontSize: 13, color: TAP.muted, fontWeight: 600 }}>{f.size}</div>
                </div>
              </div>
            </div>
          );
        })}
      </div>

      <ReplayChip onClick={restart} color={TAP.accent} bg="rgba(22,169,116,0.12)" />
    </div>
  );
}

window.TapConcept = TapConcept;
