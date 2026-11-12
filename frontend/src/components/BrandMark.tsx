export function BrandMark({ size = 28 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="none"
      aria-hidden="true"
      focusable="false"
    >
      <rect width="32" height="32" rx="9" fill="var(--accent)" />
      <path
        d="M10.5 9.5v10.25A3.25 3.25 0 0 0 13.75 23H22"
        stroke="var(--accent-contrast)"
        strokeWidth="2.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle cx="21.5" cy="10.5" r="2.5" fill="var(--accent-contrast)" />
    </svg>
  )
}
