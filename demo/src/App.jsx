import { useEffect, useMemo, useRef, useState } from 'react'
import flagsmith from 'flagsmith'
import { useFlags, useFlagsmith } from 'flagsmith/react'
import { getOrCreateIdentity } from './identity.js'

function formatClock(date) {
  return date.toLocaleTimeString('en-US', {
    hour: 'numeric',
    minute: '2-digit',
    second: '2-digit',
    hour12: true,
  })
}

export default function App() {
  // Keep these literals visible — the plugin's gutter, completion, and inspection hook them.
  const flags = useFlags(['dark_mode', 'new_checkout'])
  const client = useFlagsmith()

  const darkMode = flags.dark_mode?.enabled || flagsmith.hasFeature('dark_mode')
  const checkoutOn = flags.new_checkout?.enabled
  const checkoutValue = flags.new_checkout?.value ?? 'v1'

  const [allFlags, setAllFlags] = useState({})
  const [updatedAt, setUpdatedAt] = useState(new Date())
  const [identity, setIdentity] = useState(null)
  const [identified, setIdentified] = useState(false)
  const [converted, setConverted] = useState(false)
  const [converting, setConverting] = useState(false)

  useEffect(() => {
    const id = getOrCreateIdentity()
    setIdentity(id)
    let cancelled = false
    client.identify(id).then(() => {
      if (!cancelled) setIdentified(true)
    }).catch(() => {
      if (!cancelled) setIdentified(true)
    })
    return () => { cancelled = true }
  }, [client])

  useEffect(() => {
    const tick = () => {
      setAllFlags(flagsmith.getAllFlags() ?? {})
      setUpdatedAt(new Date())
    }
    tick()
    const timer = window.setInterval(tick, 1000)
    return () => window.clearInterval(timer)
  }, [flags, identified])

  const variant = checkoutOn ? String(checkoutValue || 'v1') : 'off'
  const entries = useMemo(
    () => Object.entries(allFlags).sort(([a], [b]) => a.localeCompare(b)),
    [allFlags],
  )

  const completeCheckout = async () => {
    if (converting || converted) return
    setConverting(true)
    try {
      const assigned = checkoutOn ? String(flagsmith.getValue('new_checkout') ?? checkoutValue ?? 'v1') : 'off'
      await flagsmith.setTrait('exp_new_checkout_variant', assigned)
      await flagsmith.setTrait('exp_new_checkout_converted', true)
      setConverted(true)
    } finally {
      setConverting(false)
    }
  }

  return (
    <div className={darkMode ? 'page dark' : 'page'}>
      <header className="hero">
        <p className="eyebrow">WeMakeDevs Toggle Wars · Track 3</p>
        <h1>Flagsmith × WebStorm live demo</h1>
        <p className="lede">
          Toggle flags in the IDE plugin. This page listens every two seconds
          and updates in place — no refresh.
        </p>
        <p className="clock" aria-live="polite">
          Last updated <time>{formatClock(updatedAt)}</time>
        </p>
      </header>

      <section className="board" aria-labelledby="board-title">
        <div className="section-head">
          <h2 id="board-title">Live flag board</h2>
          <p>Every flag from <code>flagsmith.getAllFlags()</code></p>
        </div>
        {entries.length === 0 ? (
          <p className="empty">Waiting for Flagsmith… is the local server running on port 8000?</p>
        ) : (
          <div className="board-grid">
            {entries.map(([name, state]) => (
              <FlagCard key={name} name={name} state={state} />
            ))}
          </div>
        )}
      </section>

      <div className="showcase">
        <CheckoutCard
          enabled={checkoutOn}
          variant={variant}
        />
        <ExperimentPanel
          identity={identity}
          variant={variant}
          checkoutOn={checkoutOn}
          converted={converted}
          converting={converting}
          onConvert={completeCheckout}
        />
      </div>
    </div>
  )
}

function FlagCard({ name, state }) {
  const enabled = Boolean(state?.enabled)
  const value = state?.value
  const valueText = value === null || value === undefined || value === '' ? '—' : String(value)
  const prev = useRef({ enabled, valueText })
  const [flash, setFlash] = useState(false)

  useEffect(() => {
    if (prev.current.enabled !== enabled || prev.current.valueText !== valueText) {
      setFlash(true)
      prev.current = { enabled, valueText }
      const timer = window.setTimeout(() => setFlash(false), 700)
      return () => window.clearTimeout(timer)
    }
  }, [enabled, valueText])

  return (
    <article className={`flag-card${flash ? ' flash' : ''}${enabled ? ' is-on' : ''}`}>
      <div className="flag-card-top">
        <h3>{name}</h3>
        <span className={enabled ? 'pill on' : 'pill off'}>{enabled ? 'ON' : 'OFF'}</span>
      </div>
      <p className="flag-value">
        <span className="muted">value</span>
        {valueText}
      </p>
    </article>
  )
}

function CheckoutCard({ enabled, variant }) {
  const isV2 = enabled && variant === 'v2'
  return (
    <section className={`checkout ${isV2 ? 'v2' : 'v1'}`} aria-labelledby="checkout-title">
      <div className="section-head">
        <h2 id="checkout-title">Checkout</h2>
        <p>
          Driven by <code>new_checkout</code>
          {enabled && <span className="version-badge">{variant}</span>}
        </p>
      </div>
      {!enabled ? (
        <div className="checkout-body muted-block">
          <p>The new checkout flag is off. Toggle <code>new_checkout</code> in WebStorm to reveal the flow.</p>
        </div>
      ) : isV2 ? (
        <div className="checkout-body">
          <p className="checkout-kicker">Redesigned checkout · v2</p>
          <ul>
            <li>One-tap express pay</li>
            <li>Order summary on the right</li>
            <li>Fewer fields, same total</li>
          </ul>
          <div className="price-row">
            <span>JetBrains Mono hoodie</span>
            <strong>$64.00</strong>
          </div>
          <button type="button" className="pay-btn v2" disabled>Pay $64.00</button>
        </div>
      ) : (
        <div className="checkout-body">
          <p className="checkout-kicker">Classic checkout · {variant}</p>
          <label>
            Name
            <input readOnly value="Jason Torres" />
          </label>
          <label>
            Card
            <input readOnly value="•••• 4242" />
          </label>
          <div className="price-row">
            <span>Total</span>
            <strong>$64.00</strong>
          </div>
          <button type="button" className="pay-btn v1" disabled>Place order</button>
        </div>
      )}
    </section>
  )
}

function ExperimentPanel({ identity, variant, checkoutOn, converted, converting, onConvert }) {
  return (
    <section className="experiment" aria-labelledby="exp-title">
      <div className="section-head">
        <h2 id="exp-title">Experiment</h2>
        <p>New checkout v2 · trait-based results</p>
      </div>
      <dl className="exp-meta">
        <div>
          <dt>Identity</dt>
          <dd><code>{identity ?? 'assigning…'}</code></dd>
        </div>
        <div>
          <dt>You are in variant</dt>
          <dd className="variant-line">{checkoutOn ? variant : 'n/a (flag off)'}</dd>
        </div>
      </dl>
      <p className="exp-copy">
        This browser was given a random Flagsmith identity (kept in localStorage),
        so the multivariate <code>new_checkout</code> flag can assign v1 or v2.
        Completing checkout writes <code>exp_new_checkout_variant</code> and
        <code>exp_new_checkout_converted</code> — the same traits the plugin's
        Experiments tab reads.
      </p>
      <button
        type="button"
        className="convert-btn"
        onClick={onConvert}
        disabled={converted || converting || !checkoutOn}
      >
        {converted ? 'Checkout recorded' : converting ? 'Recording…' : 'Complete checkout'}
      </button>
      {!checkoutOn && (
        <p className="hint">Turn <code>new_checkout</code> on to participate.</p>
      )}
    </section>
  )
}
