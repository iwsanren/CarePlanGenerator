import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import tseslint from 'typescript-eslint'

// ESLint 9 "flat config": one array, later entries override earlier ones.
export default tseslint.config(
  // Build output and generated reports are never linted.
  { ignores: ['dist', 'coverage', 'test-results', 'node_modules'] },

  js.configs.recommended,
  tseslint.configs.recommended,

  // Application and unit-test code runs in the browser (jsdom for unit tests).
  {
    files: ['src/**/*.{ts,tsx}'],
    languageOptions: { globals: globals.browser },
    extends: [reactHooks.configs.flat.recommended],
    rules: {
      // Off on purpose: this rule only warns that React Compiler would skip
      // memoizing a component that calls react-hook-form's watch(). This project
      // does not use React Compiler, and watch() behaves correctly without it
      // (NewOrderPage relies on it for live NPI / ICD-10 validity icons).
      // Revisit if React Compiler is ever enabled.
      'react-hooks/incompatible-library': 'off',
    },
  },

  // Tooling files and Playwright specs run in Node.
  {
    files: ['*.config.{js,ts}', 'e2e/**/*.ts'],
    languageOptions: { globals: globals.node },
  },
)
