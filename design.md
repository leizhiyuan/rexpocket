<!-- Hallmark v2.0.0 · redesign of me.leaver.rexpocket · audit + rebuild of the three screens · 2026-10-30 -->
<!-- Critique: the app was three screens of defaults: an eyebrow in tracked caps over a slogan, meta
     strings joined by middle dots, decorative dots before labels, and a dozen near-identical font
     sizes. It now has one paper, one signal, one scale, and labels that say what they are. -->

# RexPocket design system

Every colour, font size and corner radius on screen comes from `app/src/main/java/me/leaver/rexpocket/ui/Theme.kt`.
A screen that needs a value the tokens do not have is a broken screen, not a special case.

Two deliberate exceptions: the toolbar keys use a 14 sp mono face sized to fit a key, and
spacing inside a component (a chip's padding, a gap of 2 dp) is still written where it is used.

## Material

One anchor hue (a terminal green) on a green-tinted near-black paper. No gradients, no glass, no
glow. Surfaces are told apart by lightness, and a hairline is decoration, never a control edge.

| Token | Value | Job | Contrast on `bg` / `surface` |
| --- | --- | --- | --- |
| `bg` | `#10120F` | paper | — |
| `surface` | `#1C1F1A` | a row or a sheet | — |
| `line` | `#2C3129` | hairline, inactive edge | 1.4 (decoration) |
| `ink` | `#E7E9E4` | titles, body | 15.4 / 13.6 |
| `muted` | `#8E9488` | labels, secondary detail | 6.0 / 5.4 |
| `accent` | `#00FF41` | live, joined, running | 13.8 / 12.2 |
| `onAccent` | `#10120F` | text on `accent` | 13.8 |
| `amber` | `#F0C674` | waiting on a person | 11.7 / 10.3 |
| `danger` | `#FF5D5D` | failure, removal | 6.3 / 5.5 |

## Type

Four sizes, four jobs, nothing else: display 26, title 20, body 15 (leading 21), label 12
(leading 17 when it wraps).
Mono is used for figures and addresses only — never for a label, a control or a heading.

## Rules the screens follow

- No eyebrow above a heading, and no tracked or uppercase labels. A label is the body face, one
  step smaller, sentence case.
- No middle-dot meta strings. Two facts are two runs of text, spaced, or two lines.
- No decorative dots. A chip carries no status dot: the header already says what the program does.
- The tailnet is a line of state, not a destination: it has no card, no border and a chevron.
- A list row is one card: its whole surface opens the thing, and the label on the right is only its
  own target when it does something else (edit).
- One primary action per screen, at the foot, full width, in `accent` with `onAccent` text.
- Motion respects the platform's animation scale: scrolling jumps instead of animating when the
  user has asked for less motion.

## Not applicable (web-only gates)

`overflow-x`, `vh`/`dvh`, `prefers-reduced-motion` media queries, `:focus-visible` CSS, font files,
image slots and the node/sloplint sweep are web concerns. The Android equivalents are: no horizontal
overflow (Compose clips), reduced motion via `rememberReducedMotion()`, focus and press feedback from
Compose's indication, and the system font stack.
