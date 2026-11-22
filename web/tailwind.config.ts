/** @type {import('tailwindcss').Config} */
const alpha = (variable: string) =>
  `color-mix(in srgb, var(${variable}) calc(<alpha-value> * 100%), transparent)`;

export default {
  darkMode: "class",
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        ink: alpha("--ink"),
        surface: alpha("--surface"),
        raised: alpha("--raised"),
        line: "var(--line)",
        linen: alpha("--linen"),
        muted: alpha("--muted"),
        faint: alpha("--faint"),
        persimmon: alpha("--persimmon"),
        teal: alpha("--teal"),
        sage: alpha("--sage"),
        danger: alpha("--danger"),
      },
      fontFamily: {
        display: ["Space Grotesk", "sans-serif"],
        sans: ["DM Sans", "sans-serif"],
        mono: ["JetBrains Mono", "monospace"],
      },
      keyframes: {
        fadeUp: {
          from: { opacity: "0", transform: "translateY(14px)" },
          to: { opacity: "1", transform: "translateY(0)" },
        },
        pulseDot: {
          "0%, 100%": { opacity: "1" },
          "50%": { opacity: "0.25" },
        },
        sweep: {
          from: { transform: "translateY(-100%)" },
          to: { transform: "translateY(1000%)" },
        },
      },
      animation: {
        "fade-up": "fadeUp 0.55s cubic-bezier(0.22,1,0.36,1) both",
        "pulse-dot": "pulseDot 1.6s ease-in-out infinite",
        sweep: "sweep 6s linear infinite",
      },
    },
  },
  plugins: [],
};
