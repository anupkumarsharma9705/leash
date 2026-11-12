import { useSyncExternalStore } from 'react'

/**
 * One theme store for the whole console.
 *
 * The inline script in `index.html` applies the initial theme before React
 * mounts, which avoids a flash of the wrong palette. This module reads that
 * decision back, exposes a subscription for components that need to react to
 * it (AG Grid) and persists only the preference, never credentials.
 */

export type ThemeName = 'light' | 'dark'

const STORAGE_KEY = 'leash-theme'
const listeners = new Set<() => void>()

function isTheme(value: string | undefined | null): value is ThemeName {
  return value === 'light' || value === 'dark'
}

function readStoredTheme(): ThemeName | null {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    return isTheme(stored) ? stored : null
  } catch {
    return null
  }
}

function readInitialTheme(): ThemeName {
  const applied = document.documentElement.dataset.theme
  if (isTheme(applied)) {
    return applied
  }
  return (
    readStoredTheme() ??
    (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
  )
}

let currentTheme: ThemeName = readInitialTheme()

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

function getSnapshot(): ThemeName {
  return currentTheme
}

export function useTheme(): ThemeName {
  return useSyncExternalStore(subscribe, getSnapshot, () => 'light')
}

export function setTheme(theme: ThemeName): void {
  currentTheme = theme
  document.documentElement.dataset.theme = theme
  try {
    localStorage.setItem(STORAGE_KEY, theme)
  } catch {
    // A browser with storage disabled can still use the theme for this page.
  }
  for (const listener of listeners) {
    listener()
  }
}
