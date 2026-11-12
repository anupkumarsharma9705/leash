/**
 * Initialize theme from localStorage or system preference.
 * This runs before React hydration to prevent theme flash.
 */
export function initTheme(): void {
  try {
    const stored = localStorage.getItem('leash-theme')
    const theme =
      stored === 'light' || stored === 'dark'
        ? stored
        : window.matchMedia('(prefers-color-scheme: dark)').matches
          ? 'dark'
          : 'light'
    document.documentElement.dataset.theme = theme
  } catch {
    document.documentElement.dataset.theme = 'light'
  }
}