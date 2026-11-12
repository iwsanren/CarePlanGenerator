/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
    // Unit tests mention class names as strings (e.g. toHaveClass('bg-amber-50')); do not
    // let those leak into the production stylesheet.
    "!./src/**/*.test.{js,ts,jsx,tsx}",
    "!./src/test/**",
  ],
  theme: {
    extend: {},
  },
  plugins: [],
}
