# Brand directions

Three visual directions for Rawmark, made for the closed beta (2026-10-26). **Chosen: 3. Hobby energy (Zach, 2026-10-26).** Applied in `web/src/index.css`; fonts are self-hosted from `@fontsource-variable/bricolage-grotesque` and `@fontsource-variable/figtree` (SIL OFL). Compare them side by side, on a phone, on the Artifact page: https://claude.ai/artifact/JPNCXYRWjYVsLg7BC9zK7c (the card page and its offer panel at phone width, in light and dark).

The web app's look comes from the token block at the top of [web/src/index.css](../web/src/index.css). Each direction below lists the tokens it changes, under the same names. Applying one means:

1. Replace those tokens in `:root` (light) and in the `@media (prefers-color-scheme: dark)` block (dark). Tokens a direction doesn't list keep their current values.
2. Self-host its fonts as `woff2` in `web/public/fonts/` with `@font-face` rules, and set `--font-display` and `--font-sans`. The production CSP (`font-src 'self'`, PR #36) blocks Google Fonts and other font CDNs. All the fonts here are under the SIL Open Font License. Subset to Latin to keep each file small.
3. Replace the placeholder mark in `.brand::before` with the direction's wordmark treatment.

Every text pair below meets WCAG AA (4.5:1) on its surface and on its soft background, in both schemes. The lowest is 5.2:1 (Hobby energy's accent text on its soft accent). The warning and danger colours stay as they are in all three.

## 1. Trusted collector

Warm and confident: an oxblood binder and a brass hallmark, so a first offer feels like a dealer who has handled cards for years.

- **Type:** Source Serif 4 for headings and prices (600/700), Source Sans 3 for text (400/600).
- **Wordmark:** "Raw*mark*" in Source Serif 4 Bold, with "mark" in italic in the accent, after a round brass hallmark with a serif R.
- **Shape:** small radii (4 / 8 / 12 px), soft warm shadows.

```css
/* light */
--color-bg: #f5f2ee;
--color-surface: #fffdfa;
--color-surface-raised: #fffdfa;
--color-surface-sunken: #efe9e3;
--color-text: #231a17;
--color-muted: #6a5d57;
--color-border: #e2d9d1;
--color-border-strong: #b3a59b;
--color-accent: #7a2232;
--color-accent-hover: #5f1826;
--color-accent-contrast: #ffffff;
--color-accent-soft: #f6e6e8;
--color-accent-text: #7a2232;
--color-success-soft: #e6f1e7;
--color-success-text: #2b5f34;
--color-detail: #a8741a; /* hallmark brass: decoration only, not text */
--font-display: 'Source Serif 4', Georgia, 'Times New Roman', serif;
--font-sans: 'Source Sans 3', system-ui, sans-serif;
--font-weight-display: 700;
--radius-sm: 0.25rem;
--radius-md: 0.5rem;
--radius-lg: 0.75rem;
--shadow-1: 0 1px 2px rgb(60 30 20 / 0.08), 0 2px 6px rgb(60 30 20 / 0.06);

/* dark */
--color-bg: #16110f;
--color-surface: #211915;
--color-surface-raised: #261e1a;
--color-surface-sunken: #1a1411;
--color-text: #f1e9e3;
--color-muted: #b9aaa1;
--color-border: #3a2f2a;
--color-border-strong: #5c4d45;
--color-accent: #e8a0a8;
--color-accent-hover: #f2bcc2;
--color-accent-contrast: #2a0d12;
--color-accent-soft: #3a1f24;
--color-accent-text: #f0b7bd;
--color-success-soft: #172a1a;
--color-success-text: #9ed3a6;
--color-detail: #d9a64a;
```

## 2. Clean fintech

Crisp and neutral: a money green on white with a monospaced price, so the number reads like a settled quote you can act on.

- **Type:** IBM Plex Sans for headings and text (400/500/600), IBM Plex Mono for prices and other figures (500/600).
- **Wordmark:** lowercase "rawmark" in IBM Plex Sans SemiBold, tight tracking, after a small accent square with a check.
- **Shape:** tight radii (3 / 6 / 8 px), hairline shadows.

```css
/* light */
--color-bg: #f6f7f9;
--color-surface: #ffffff;
--color-surface-raised: #ffffff;
--color-surface-sunken: #eef1f4;
--color-text: #0f1419;
--color-muted: #56606b;
--color-border: #dfe3e8;
--color-border-strong: #a3acb7;
--color-accent: #0b6e4f;
--color-accent-hover: #08573e;
--color-accent-contrast: #ffffff;
--color-accent-soft: #e3f2ec;
--color-accent-text: #0a5f45;
--color-success-soft: #e3f2ec;
--color-success-text: #0a5f45;
--font-display: 'IBM Plex Sans', system-ui, sans-serif;
--font-sans: 'IBM Plex Sans', system-ui, sans-serif;
--font-mono: 'IBM Plex Mono', ui-monospace, monospace; /* also use for .offer-price */
--font-weight-display: 600;
--radius-sm: 0.1875rem;
--radius-md: 0.375rem;
--radius-lg: 0.5rem;
--shadow-1: 0 1px 2px rgb(15 20 25 / 0.06);

/* dark */
--color-bg: #0b0e11;
--color-surface: #13171b;
--color-surface-raised: #181d22;
--color-surface-sunken: #0f1215;
--color-text: #e9edf1;
--color-muted: #9aa5b1;
--color-border: #262d35;
--color-border-strong: #3d4752;
--color-accent: #4fd1a1;
--color-accent-hover: #74ddb6;
--color-accent-contrast: #04241a;
--color-accent-soft: #0f2a21;
--color-accent-text: #6fe0b5;
--color-success-soft: #0f2a21;
--color-success-text: #6fe0b5;
```

The success colour equals the accent here, so a "Passed" chip and an "Offer ready" chip look alike. If that matters, keep the current success tokens instead.

## 3. Hobby energy

Bold and playful: sticker outlines, a holo sheen and a hot orange, so it feels like the trade table at a card show without borrowing anyone's marks.

- **Type:** Bricolage Grotesque for headings, prices and buttons (600/800), Figtree for text (400/600/700).
- **Wordmark:** "RAWMARK" in Bricolage Grotesque ExtraBold, in an orange pill with an ink outline and a solid offset shadow, tilted 3 degrees.
- **Shape:** big radii (8 / 12 / 18 px), 2 px ink outlines, solid offset shadows (`4px 4px 0` in ink). Buttons are ink text on orange. Card photos can carry a holo sheen (a diagonal white-to-cyan gradient over the image).
- **No Pokémon IP:** no red-and-white ball, no franchise yellow-and-blue logo type, no official fonts or artwork.

```css
/* light */
--color-bg: #fdf6ee;
--color-surface: #ffffff;
--color-surface-raised: #ffffff;
--color-surface-sunken: #f6ede2;
--color-text: #1b1530;
--color-muted: #554d6b;
--color-border: #1b1530;
--color-border-strong: #1b1530;
--color-accent: #ff6a3d;
--color-accent-hover: #ff8459;
--color-accent-contrast: #1b1530;
--color-accent-soft: #ffe4d9;
--color-accent-text: #b2321a;
--color-success-soft: #dff5e6;
--color-success-text: #17602f;
--color-detail: #3aa0ff; /* holo blue: decoration only */
--font-display: 'Bricolage Grotesque', 'Arial Black', system-ui, sans-serif;
--font-sans: 'Figtree', system-ui, sans-serif;
--font-weight-display: 800;
--radius-sm: 0.5rem;
--radius-md: 0.75rem;
--radius-lg: 1.125rem;
--shadow-1: 4px 4px 0 #1b1530;

/* dark */
--color-bg: #141022;
--color-surface: #1e1932;
--color-surface-raised: #251f3c;
--color-surface-sunken: #181428;
--color-text: #f6f1ff;
--color-muted: #b9afd0;
--color-border: #8f84b0;
--color-border-strong: #f6f1ff;
--color-accent: #ff7a50;
--color-accent-hover: #ff9670;
--color-accent-contrast: #1b1530;
--color-accent-soft: #3a2030;
--color-accent-text: #ffa07f;
--color-success-soft: #14301f;
--color-success-text: #8fe3aa;
--color-detail: #5cb3ff;
--shadow-1: 4px 4px 0 #000000;
```

This one needs a little more than tokens: panels and buttons take a 2 px border (`border-width: 2px` on `.panel`, `.side`, `.tile` and buttons) so the outlines read, and the danger button keeps its own colours.
