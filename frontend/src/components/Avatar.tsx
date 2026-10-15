function hash(seed: string) {
  let value = 2166136261
  for (let index = 0; index < seed.length; index += 1) {
    value ^= seed.charCodeAt(index)
    value = Math.imul(value, 16777619)
  }
  return value >>> 0
}

const palettes = [
  ['#ff6a2a', '#1c1915', '#ffe8dc'],
  ['#1f6b4a', '#10241a', '#d9f2e4'],
  ['#6d4aff', '#1b1430', '#ece6ff'],
  ['#c45c26', '#2a160e', '#ffe7d6'],
  ['#0e6b62', '#0d2422', '#d7f3ef'],
  ['#b4536b', '#2a1218', '#ffdfe6'],
]

export function Avatar({ email, size = 40 }: { email: string; size?: number }) {
  const seed = email.trim().toLowerCase()
  const value = hash(seed || 'changegraph')
  const [ink, deep, paper] = palettes[value % palettes.length]
  const initials = seed
    .split(/[@.\s_-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('') || '?'
  const shift = (amount: number) => (value >> amount) & 31

  return (
    <svg className="avatar" width={size} height={size} viewBox="0 0 64 64" role="img" aria-label={`Avatar for ${email}`}>
      <rect width="64" height="64" rx="32" fill={deep} />
      <circle cx={14 + shift(3)} cy={18 + (shift(8) % 18)} r={10 + (shift(1) % 8)} fill={ink} opacity="0.95" />
      <circle cx={46 - (shift(5) % 10)} cy={44} r={12 + (shift(11) % 6)} fill={paper} opacity="0.9" />
      <rect x={28 + (shift(15) % 8)} y="6" width="10" height="28" rx="5" fill={paper} opacity="0.55" transform={`rotate(${shift(2) % 40} 32 20)`} />
      <text x="32" y="38" textAnchor="middle" fill="#f6f1e7" fontSize="18" fontFamily="IBM Plex Mono, ui-monospace, monospace">
        {initials}
      </text>
    </svg>
  )
}
