# Demo video script (about 3 minutes)

Record WebStorm and the in-repo demo browser window side by side. Use a 16:9 crop, a readable font size, and no real API keys on screen.

## 0:00–0:20 — Setup

- WebStorm is open on the `flagsmith-webstorm` project, with `demo/src/App.jsx` in the editor.
- The Flagsmith tool window is pinned on the right, Flags tab visible.
- The showcase demo is running at `http://localhost:5173` (`cd demo && npm install && npm run dev`, or the **Demo** run configuration).
- One line of voiceover: this is an unofficial community plugin that talks to self-hosted, open source Flagsmith from the IDE.

## 0:20–1:20 — Flags tab, live toggle

- Show the Flags tab already listing `dark_mode` and `new_checkout`.
- Type in the search box to filter, then clear it.
- Toggle `dark_mode` on. Keep the browser in frame. Within about two seconds the demo should switch to the dark theme, no refresh. The live flag board cards should flash and the last-updated clock should tick.
- Toggle `new_checkout` on. The checkout card should appear — v1 as a classic form, or v2 as the gradient treatment with the value as a badge, depending on the multivariate assignment.
- Flip both back off so the before-and-after is obvious, then leave them in a state that looks good for the next shot.

## 1:20–1:55 — In-editor gutters and the unknown-flag fix

- Keep `demo/src/App.jsx` open next to the running app.
- Show the gutter icon on `'dark_mode'` and `'new_checkout'` inside `useFlags`, and on `'dark_mode'` in `flagsmith.hasFeature`. Hover to show enabled/value.
- Click the `dark_mode` gutter, choose Toggle, and keep the browser in frame so the theme flips live.
- Type a new flag name into the `useFlags` array (for example `'summer_banner'`). Show the Unknown Flagsmith flag warning, then use the quick fix **Create flag "summer_banner" in Flagsmith**. After it appears in the Flags tab, the warning should clear.

[cat-generator](https://github.com/jasonetorres/cat-generator) is a second example of the same live-update pattern if you want another app in frame instead of `demo/`.

## 1:55–2:35 — Experiments tab

- In the demo, point at the Experiment panel: the persisted random identity, "You are in variant: X", and click **Complete checkout**. That writes the `exp_new_checkout_variant` and `exp_new_checkout_converted` traits.
- Switch to the Experiments tab and hit Refresh if needed.
- Select **New checkout v2** (status: running). Point out the hypothesis, the `new_checkout` flag, the control/variant split, the “Checkout completed” metric, and the 50% rollout.
- Scroll the results. Call out the trait-based A/B table: v2 at a higher conversion rate than v1, a winner, lift, and p-value. Mention that warehouse results are a separate Flagsmith endpoint and stay empty until a warehouse is connected.

## 2:35–2:50 — Settings and the password safe

- Open **Settings | Tools | Flagsmith**.
- Show the API URL (`http://localhost:8000/api/v1`) and the Environment key field.
- Point at the Organisation API key field and say it is stored in the IDE password safe (Keychain on macOS), not in the repo or a `.env` file. Do not paste or reveal a real key.

## 2:50–3:05 — Close

- Cut back to the split view: IDE plugin and the live `demo/` app.
- Close on: self-hosted Flagsmith, open source API, a plugin you can install and reuse. Built for WeMakeDevs Toggle Wars, Track 3.
