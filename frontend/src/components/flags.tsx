/**
 * Small inline SVG flags (3:2). Emoji flags render as two letters on Windows, so these are drawn by hand
 * and simplified to stay recognisable at 20px wide.
 */

export type FlagCode = "gb" | "bd" | "in" | "pk" | "sa";

const W = 30;
const H = 20;

function starPoints(cx: number, cy: number, outer: number, inner: number, rotate = -90) {
  const pts: string[] = [];
  for (let i = 0; i < 10; i++) {
    const r = i % 2 === 0 ? outer : inner;
    const a = ((rotate + i * 36) * Math.PI) / 180;
    pts.push(`${(cx + r * Math.cos(a)).toFixed(2)},${(cy + r * Math.sin(a)).toFixed(2)}`);
  }
  return pts.join(" ");
}

function UnitedKingdom() {
  return (
    <>
      <rect width={W} height={H} fill="#012169" />
      <path d={`M0 0L${W} ${H}M${W} 0L0 ${H}`} stroke="#fff" strokeWidth={4} />
      <path d={`M0 0L${W} ${H}M${W} 0L0 ${H}`} stroke="#C8102E" strokeWidth={1.4} />
      <rect x={12} width={6} height={H} fill="#fff" />
      <rect y={7} width={W} height={6} fill="#fff" />
      <rect x={13.2} width={3.6} height={H} fill="#C8102E" />
      <rect y={8.2} width={W} height={3.6} fill="#C8102E" />
    </>
  );
}

function Bangladesh() {
  return (
    <>
      <rect width={W} height={H} fill="#006A4E" />
      <circle cx={13.5} cy={10} r={6} fill="#F42A41" />
    </>
  );
}

function India() {
  const spokes = Array.from({ length: 12 }, (_, i) => (i * Math.PI) / 12);
  return (
    <>
      <rect width={W} height={H / 3} fill="#FF9933" />
      <rect y={H / 3} width={W} height={H / 3} fill="#fff" />
      <rect y={(2 * H) / 3} width={W} height={H / 3} fill="#138808" />
      <circle cx={15} cy={10} r={2.7} fill="none" stroke="#000080" strokeWidth={0.6} />
      {spokes.map((a, i) => (
        <line
          key={i}
          x1={15 - 2.7 * Math.cos(a)}
          y1={10 - 2.7 * Math.sin(a)}
          x2={15 + 2.7 * Math.cos(a)}
          y2={10 + 2.7 * Math.sin(a)}
          stroke="#000080"
          strokeWidth={0.3}
        />
      ))}
      <circle cx={15} cy={10} r={0.6} fill="#000080" />
    </>
  );
}

function Pakistan() {
  return (
    <>
      <rect width={W} height={H} fill="#01411C" />
      <rect width={7.5} height={H} fill="#fff" />
      <circle cx={18.5} cy={10.5} r={5.6} fill="#fff" />
      <circle cx={20.3} cy={9.1} r={4.8} fill="#01411C" />
      <polygon points={starPoints(22.2, 7.6, 1.9, 0.8, -60)} fill="#fff" />
    </>
  );
}

function SaudiArabia() {
  return (
    <>
      <rect width={W} height={H} fill="#006C35" />
      {/* The Shahada, simplified to calligraphy-like strokes. */}
      <path d="M7.5 7.8q1.2-2.2 2.4 0t2.4 0t2.4 0t2.4 0t2.4 0t2.4 0" fill="none" stroke="#fff" strokeWidth={1} strokeLinecap="round" />
      <path d="M9 10.6h12M11 9.4v1.2M15 9v1.6M19 9.4v1.2" fill="none" stroke="#fff" strokeWidth={0.7} strokeLinecap="round" />
      {/* The sword. */}
      <path d="M7.5 14h14.2" stroke="#fff" strokeWidth={0.9} strokeLinecap="round" />
      <path d="M21.4 12.7v2.6" stroke="#fff" strokeWidth={0.8} strokeLinecap="round" />
      <path d="M21.8 14h2.2" stroke="#fff" strokeWidth={1.2} strokeLinecap="round" />
    </>
  );
}

const drawings: Record<FlagCode, () => React.JSX.Element> = {
  gb: UnitedKingdom,
  bd: Bangladesh,
  in: India,
  pk: Pakistan,
  sa: SaudiArabia,
};

const names: Record<FlagCode, string> = {
  gb: "United Kingdom",
  bd: "Bangladesh",
  in: "India",
  pk: "Pakistan",
  sa: "Saudi Arabia",
};

export function Flag({ code, className = "h-3.5 w-5" }: { code: FlagCode; className?: string }) {
  const Drawing = drawings[code];
  return (
    <svg
      viewBox={`0 0 ${W} ${H}`}
      className={`inline-block shrink-0 overflow-hidden rounded-[2px] ring-1 ring-black/10 ${className}`}
      role="img"
      aria-label={names[code]}
    >
      <Drawing />
    </svg>
  );
}

/** One or more flags side by side (Hindi shows India and Pakistan). */
export function Flags({ codes, className }: { codes: readonly FlagCode[]; className?: string }) {
  return (
    <span className="inline-flex shrink-0 items-center gap-1">
      {codes.map((c) => <Flag key={c} code={c} className={className} />)}
    </span>
  );
}
