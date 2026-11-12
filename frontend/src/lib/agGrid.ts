import {
  AllCommunityModule,
  ModuleRegistry,
  colorSchemeDark,
  themeQuartz,
} from 'ag-grid-community'

/**
 * AG Grid 36 setup.
 *
 * Module registration and theming both changed across majors. The installed
 * 36.3.0 package documents `ModuleRegistry.registerModules` plus a theme object
 * passed to the grid, so that is what this console uses. `AllCommunityModule`
 * keeps the demo simple; the Community build has no license key.
 *
 * The colour values use CSS variables that reference the design tokens.
 * AG Grid compiles theme parameters into CSS variables, so we reference
 * the same design tokens defined in tokens.css / designTokens.ts.
 */
ModuleRegistry.registerModules([AllCommunityModule])

const sharedParams = {
  fontFamily: ['var(--font-sans)', 'system-ui', 'sans-serif'],
  fontSize: 13,
  dataFontSize: 13,
  headerFontSize: 11,
  headerFontWeight: 650,
  headerHeight: 42,
  rowHeight: 46,
  borderWidth: 1,
  borderRadius: 'var(--radius-sm)',
  wrapperBorder: false,
  wrapperBorderRadius: 0,
  cellHorizontalPadding: 14,
  spacing: 8,
}

export const gridThemeLight = themeQuartz.withParams({
  ...sharedParams,
  browserColorScheme: 'light',
  accentColor: 'var(--accent)',
  backgroundColor: 'var(--surface)',
  foregroundColor: 'var(--text)',
  textColor: 'var(--text)',
  subtleTextColor: 'var(--text-subtle)',
  borderColor: 'var(--border)',
  chromeBackgroundColor: 'var(--surface-muted)',
  headerBackgroundColor: 'var(--surface-muted)',
  headerTextColor: 'var(--text-muted)',
  rowHoverColor: 'var(--surface-muted)',
  selectedRowBackgroundColor: 'var(--accent-soft)',
  invalidColor: 'var(--danger)',
})

export const gridThemeDark = themeQuartz.withPart(colorSchemeDark).withParams({
  ...sharedParams,
  accentColor: 'var(--accent)',
  backgroundColor: 'var(--surface)',
  foregroundColor: 'var(--text)',
  textColor: 'var(--text)',
  subtleTextColor: 'var(--text-subtle)',
  borderColor: 'var(--border)',
  chromeBackgroundColor: 'var(--surface-muted)',
  headerBackgroundColor: 'var(--surface-muted)',
  headerTextColor: 'var(--text-muted)',
  rowHoverColor: 'var(--surface-strong)',
  selectedRowBackgroundColor: 'var(--accent-soft)',
  invalidColor: 'var(--danger)',
})