#!/usr/bin/env python3
"""
Cart Telemetry (T0) Analyzer

Parses JSON output from the CartTelemetry bot and answers, one by one, the questions the
cart-aware pathfinding design is blocked on. Each section prints a VERDICT line.

Usage:
    python analyze_carttest.py <path_to_carttest_json>
    python analyze_carttest.py            # uses most recent carttest_*.json in AppData
"""

import json
import sys
import os
import glob
import math
from collections import Counter


WALK_POSES = ("borka/walking", "borka/running", "borka/wading")


def find_latest_carttest():
    """Find the most recent carttest JSON file in AppData."""
    appdata = os.environ.get('APPDATA', '')
    if not appdata:
        # WSL fallback
        appdata = '/mnt/c/Users/imbecil/AppData/Roaming'

    hnh_dir = os.path.join(appdata, 'Haven and Hearth')
    files = glob.glob(os.path.join(hnh_dir, 'carttest_*.json'))
    if not files:
        files = glob.glob(os.path.join(hnh_dir, '..', 'carttest_*.json'))
    return max(files, key=os.path.getmtime) if files else None


def hdr(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


def verdict(text):
    print(f"  >> VERDICT: {text}")


def towed_bit(sample):
    """Derive tow state from the raw marker.

    Deliberately not the stored cartTowedBit: traces recorded before the sign fix computed that
    flag with a `marker >= 0` guard, which drops every marker past 127 -- i.e. every loaded cart.
    Recomputing here keeps old traces readable.
    """
    m = sample.get('cartMarker')
    if m is None or m == -1:
        return None
    return bool((m & 0xFF) & 2)


def moving_samples(samples):
    """Samples where the player was actually in motion."""
    return [s for s in samples if (s.get('plV') or 0) > 0.1]


def q_attachment(samples, events):
    hdr("Q1  Attachment model - what is the cart while towed?")

    present = [s for s in samples if s.get('cartPresent')]
    if not present:
        verdict("no vehicle seen at all. Was one in range? Nothing else can be answered.")
        return None

    kinds = Counter(s.get('cartMoving') or '<none>' for s in present)
    print("  cart Moving attribute, by sample count:")
    for k, n in kinds.most_common():
        print(f"    {k:<16} {n:>7}  ({100.0 * n / len(present):.1f}%)")

    following = [s for s in present if s.get('cartFollowing')]
    mine = [s for s in present if s.get('cartFollowIsMe')]
    print(f"\n  Following attr present : {len(following)}/{len(present)} samples")
    print(f"  Following.tgt == me    : {len(mine)}/{len(present)} samples")

    xf = Counter(s['cartFollowXfname'] for s in present if s.get('cartFollowXfname'))
    if xf:
        print(f"  Following.xfname       : {', '.join(sorted(xf))}")

    homing = [s for s in present if 'cartHomingTgt' in s]
    if homing:
        print(f"  Homing attr present    : {len(homing)}/{len(present)} samples")

    # A rigidly bone-attached gob cannot have an independent position: Following.getc()
    # returns the target's own coordinate. So a cart whose rc genuinely moves relative to
    # the player is its own moving body, whatever attribute it happens to carry.
    moved = [s for s in present if 'cartX' in s]
    independent = False
    if len(moved) > 2:
        ds = [s['d'] for s in moved if 'd' in s]
        if ds and (max(ds) - min(ds)) > 0.5:
            independent = True

    if len(mine) > 0.5 * len(present):
        verdict("cart carries Following pointing at the player -> NPFMap.addGob's skip DOES fire.")
    else:
        verdict("cart does NOT carry Following on the player -> NPFMap.addGob does NOT skip it. "
                "It is being rasterised as an obstacle. This is a bug to fix.")

    if independent:
        print("  note: cart-player distance varies over the run, so the cart is an independently")
        print("        positioned body (a trailer), not a rigid bone attachment.")
    return present


def q_pf_membership(probes):
    hdr("Q2  Is the cart inside my own pathfinding obstacle map?")
    if not probes:
        print("  no pf probes recorded.")
        return

    inmap = [p for p in probes if p.get('cartInPfMap')]
    print(f"  probes with the cart present in NPFMap : {len(inmap)}/{len(probes)}")
    cellcounts = [len(p.get('cartCells') or []) for p in inmap]
    if cellcounts:
        print(f"  cells occupied when present            : min {min(cellcounts)}, max {max(cellcounts)}")

    hasca = [p for p in probes if p.get('cartHasCA')]
    print(f"  probes where cart.ngob.getCA() != null : {len(hasca)}/{len(probes)}")

    # Timeline, because the exclusion only engages once ownership is established -- a manual
    # tie emits no Homing until the character first moves, so early probes legitimately still
    # show the cart. What matters is that it goes away and stays away.
    print("  timeline (t=seconds, X = cart present in pf map):")
    row = "".join('X' if p.get('cartInPfMap') else '.' for p in probes)
    for i in range(0, len(row), 60):
        chunk = row[i:i + 60]
        t = probes[i]['t'] / 1000.0
        print(f"    t={t:7.1f}s  {chunk}")

    if inmap and not probes[-1].get('cartInPfMap'):
        verdict("the cart appears early then drops out - consistent with the exclusion engaging "
                "once ownership is established. Check the leading X's line up with the pre-tie "
                "or pre-first-move window.")
    elif inmap:
        verdict("the towed cart IS an obstacle in the player's own pf map. Every plan is being "
                "made around a body attached to the planner. Must be excluded explicitly.")
    else:
        verdict("the towed cart never appears in the pf map - exclusion is working.")


def q_geometry(present):
    hdr("Q3  Tow geometry - distance and heading lag")
    if not present:
        return

    ds = [s['d'] for s in present if 'd' in s]
    if not ds:
        print("  no distance samples.")
        return

    ds_sorted = sorted(ds)
    n = len(ds_sorted)
    print(f"  tow distance d   : min {ds_sorted[0]:.2f}  "
          f"median {ds_sorted[n // 2]:.2f}  max {ds_sorted[-1]:.2f}   (world units)")

    mv = [s for s in present if (s.get('plV') or 0) > 0.1 and 'd' in s]
    if mv:
        mvd = sorted(s['d'] for s in mv)
        print(f"  ... while moving  : min {mvd[0]:.2f}  "
              f"median {mvd[len(mvd) // 2]:.2f}  max {mvd[-1]:.2f}")

    bearings = [abs(s['bearingRel']) for s in present if 'bearingRel' in s]
    if bearings:
        bs = sorted(bearings)
        print(f"  |bearing| to cart : median {bs[len(bs) // 2]:.1f} deg "
              f"(180 = directly behind the character)")

    # Heading lag is only meaningful while the cart is actually being pulled. A cart at rest
    # stays exactly where it is -- it does not reposition or swing -- so if you walk around a
    # parked cart its "lag" sweeps through 180 deg without the cart moving a millimetre.
    # Averaging those in produces a large fictitious swing and the wrong design conclusion.
    pulled = [s for s in present if s.get('cartMoving') == 'Homing' and 'aDelta' in s]
    idle = [s for s in present if s.get('cartMoving') is None and 'aDelta' in s]
    if pulled:
        ls = sorted(abs(s['aDelta']) for s in pulled)
        print(f"  |heading lag| while being pulled : median {ls[len(ls) // 2]:.1f} deg, "
              f"p95 {ls[int(0.95 * len(ls))]:.1f} deg, max {ls[-1]:.1f} deg  "
              f"(n={len(pulled)})")
    if idle:
        li = sorted(abs(s['aDelta']) for s in idle)
        print(f"  |heading lag| while stationary   : median {li[len(li) // 2]:.1f} deg, "
              f"max {li[-1]:.1f} deg  (n={len(idle)}) -- character orbiting a parked cart, "
              f"not cart movement")

    if pulled and idle:
        lp = sorted(abs(s['aDelta']) for s in pulled)
        li = sorted(abs(s['aDelta']) for s in idle)
        mp, mi = lp[len(lp) // 2], li[len(li) // 2]
        if mi > mp * 3:
            print(f"\n  The stationary figure is {mi / max(mp, 0.1):.0f}x the pulled one, which is the "
                  f"signature of a cart that\n  simply parks: it holds position until the character "
                  f"pulls away, then homes straight at them.")
            verdict("NO ORBITAL SWEEP. The cart never swings around the character, so there is no "
                    "swept-volume clearance to budget for and no turn-clearance disc. Its only "
                    "rotation is about its own centre while re-aiming, and since the footprint is "
                    "a square that costs just the difference between half-width 5.892 and "
                    "circumscribed radius 8.33 -- about 2.4 units. Plan against radius 8.33.")
            print("\n  Residual turn hazard is CORNER CUTTING, not sweeping: the cart aims at where "
                  "the character\n  is now, not where they walked, so it chords the inside of every "
                  "turn. The cart-to-character\n  line is what has to stay clear.")

    if ds:
        print(f"\n  planning implication: the cart homes straight at the character, so the corridor "
              f"that must stay clear\n  is the cart-to-character line -- up to ~{ds_sorted[n // 2]:.0f} "
              f"units long -- at the cart's 11.8-unit width.")


def q_pose(samples):
    hdr("Q4  Does the towing walk pose match what MovingCompleted/IsMoving test for?")

    mv = moving_samples(samples)
    if not mv:
        print("  no samples with the character in motion - walk around next run.")
        return

    towing = [s for s in mv if s.get('plCarryPose')]
    print(f"  moving samples          : {len(mv)}")
    print(f"  ... while in carry pose : {len(towing)}")

    poses = Counter(s.get('plPose') or '<none>' for s in mv)
    print("  poses seen while moving:")
    for p, n in poses.most_common(8):
        print(f"    {p:<40} {n:>7}")

    pool = towing if towing else mv
    matched = [s for s in pool if s.get('plWalkPoseMatch')]
    label = "while towing" if towing else "while moving"
    print(f"\n  {label}, pose matched {WALK_POSES}: {len(matched)}/{len(pool)}")

    if not pool:
        return
    frac = len(matched) / len(pool)
    if frac > 0.9:
        verdict("the walk-pose check holds - MovingCompleted and IsMoving keep working while towing.")
    elif frac < 0.1:
        verdict("the walk pose NEVER matches while towing. MovingCompleted returns true "
                "immediately, so GoTo reports arrival at the start of every leg. This breaks "
                "every towed leg and must be fixed before any cart bot can work.")
    else:
        verdict(f"the walk pose matches only {frac * 100:.0f}% of the time while towing - "
                "arrival detection is unreliable. Switch to distance + stall detection.")


def q_untie(events, samples):
    hdr("Q5  Untie signature - what does a snag look like, and how fast?")

    interesting = [e for e in events if e.get('kind') in
                   ('cartFollowTgt', 'playerCarryPose', 'cartPresent', 'cartMoving', 'error')]
    if not interesting:
        print("  no attachment transitions recorded (no untie happened this run).")
        print("  To capture one, drag the cart into a tree and let it snag.")
        return

    print("  transitions, in order:")
    for e in interesting[:60]:
        t = e['t'] / 1000.0
        frm = e.get('from', '-')
        print(f"    t={t:8.2f}s  {e['kind']:<18} {frm} -> {e.get('to')}")
    if len(interesting) > 60:
        print(f"    ... and {len(interesting) - 60} more")

    # A detach shows as carry pose dropping and/or the follow target clearing. Whichever
    # edge lands first is the lowest-latency oracle for T1.
    pose_off = [e['t'] for e in events
                if e.get('kind') == 'playerCarryPose' and e.get('to') == 'false'
                and e.get('from') == 'true']
    follow_off = [e['t'] for e in events
                  if e.get('kind') == 'cartFollowTgt' and e.get('to') == 'null'
                  and e.get('from') not in (None, 'null', '<init>')]
    errs = [e for e in events if e.get('kind') == 'error']

    print()
    print(f"  carry-pose drops   : {len(pose_off)}")
    print(f"  follow-target drops: {len(follow_off)}")
    print(f"  error messages     : {len(errs)}")
    for e in errs[:10]:
        print(f"    t={e['t'] / 1000.0:8.2f}s  {e.get('to')}")

    if pose_off and follow_off:
        # Pair each follow drop with the nearest pose drop to see which edge leads.
        deltas = []
        for f in follow_off:
            near = min(pose_off, key=lambda p: abs(p - f))
            deltas.append(near - f)
        avg = sum(deltas) / len(deltas)
        lead = "carry pose" if avg > 0 else "follow target"
        verdict(f"both signals fire; {lead} lags the other by {abs(avg):.0f} ms on average. "
                "Use the earlier one as the T1 oracle and the other as a cross-check.")
    elif pose_off:
        verdict("carry pose is the available untie signal.")
    elif follow_off:
        verdict("follow-target clearing is the available untie signal.")

    if not errs:
        print("  note: no error-class message accompanied the untie. UI.error() is the only channel")
        print("        captured here - UI.msg() info notices are invisible to getLastError(), so an")
        print("        untie notice may exist but not be visible to this trace.")


def q_marker(samples, events):
    hdr("Q6  Model marker - the tow-state signal")

    present = [s for s in samples if s.get('cartPresent') and 'cartMarker' in s]
    if not present:
        print("  no marker samples - trace predates marker logging. Re-run the bot.")
        return

    seen = Counter(s['cartMarker'] for s in present)
    print("  marker values seen (bit0=parked, bit1=towed, bits2+=cargo):")
    signed = False
    for m, n in sorted(seen.items()):
        u = m & 0xFF                     # calcMarker sign-extends anything past 127
        if m < 0:
            signed = True
        cargo = sum(1 for b in range(2, 8) if u & (1 << b))
        flags = []
        if u & 1:
            flags.append('PARKED')
        if u & 2:
            flags.append('TOWED')
        raw = f"{m:>5}" + (f" (={u})" if m < 0 else "      ")
        print(f"    {raw}  0b{u:08b}  {'+'.join(flags) or '-':<13} cargo={cargo}  ({n} samples)")
    if signed:
        print("\n  NOTE: negative markers above are sign-extended bytes, not errors -- "
              "calcMarker() reads sdt.rbuf[0] as a signed Java byte, so anything >= 128 goes")
        print("        negative. Mask tests still work; a `marker >= 0` guard does not.")

    towed = [s for s in present if towed_bit(s)]
    parked = [s for s in present if s.get('cartMarker') not in (None, -1)
              and (s['cartMarker'] & 0xFF) & 1]
    both = [s for s in towed if (s['cartMarker'] & 0xFF) & 1]
    print(f"\n  towed-bit set : {len(towed)}/{len(present)}")
    print(f"  parked-bit set: {len(parked)}/{len(present)}")
    if both:
        print(f"  WARNING: {len(both)} samples had BOTH bits set - the layout is not a clean pair.")

    # The decisive cross-check: while the cart is homing to us it is unambiguously under tow,
    # so the towed bit must be set in every one of those samples. If it is, the marker is a
    # sound at-rest tow-state signal -- which nothing else in the client provides.
    homing = [s for s in present if s.get('cartMoving') == 'Homing']
    if homing:
        agree = [s for s in homing if towed_bit(s)]
        print(f"\n  cross-check vs Homing: towed bit set in {len(agree)}/{len(homing)} homing samples")
        if len(agree) == len(homing):
            verdict("marker bit 1 agrees with every Homing sample -> it is a sound tow-state "
                    "signal, and unlike Homing it is still valid at rest. Use it for the towing "
                    "detector and for TakeVehicle's wait.")
        else:
            verdict("marker bit 1 disagrees with Homing on some samples - do not rely on it yet.")

    # And it should drop exactly when the cart is reported blocked.
    edges = [e for e in events if e.get('kind') == 'cartMarker']
    errs = [e for e in events if e.get('kind') == 'error']
    if edges:
        print("\n  marker transitions:")
        for e in edges[:40]:
            print(f"    t={e['t'] / 1000.0:8.2f}s  {e.get('from')} -> {e.get('to')}")
        if len(edges) > 40:
            print(f"    ... and {len(edges) - 40} more")
    for err in errs:
        near = [e for e in edges if abs(e['t'] - err['t']) < 1500]
        if near:
            dt = near[0]['t'] - err['t']
            print(f"\n  '{err.get('to')}' at t={err['t'] / 1000.0:.2f}s coincides with a marker "
                  f"change {dt:+d} ms away ({near[0].get('from')} -> {near[0].get('to')}).")
            verdict("the untie is visible in the marker as well as the message. The marker edge "
                    "is the better oracle: non-destructive, and readable at any time rather than "
                    "only in the instant the message arrives.")
        else:
            print(f"\n  '{err.get('to')}' at t={err['t'] / 1000.0:.2f}s has NO nearby marker change.")


def q_unties(data):
    hdr("Q7  Untie snapshots - what did the cart actually hit?")

    unties = data.get('unties') or []
    samples = data.get('samples') or []
    if not unties:
        print("  no untie snapshots recorded.")
        print("  For E3: tow the cart past a tree or boulder and reverse direction so the cart")
        print("  swings THROUGH it but ends up somewhere clear. Snag it a few times.")
        return

    for n, u in enumerate(unties, 1):
        print(f"\n  --- untie #{n} at t={u['t'] / 1000.0:.2f}s ---")
        print(f"    cart at ({u['cartX']}, {u['cartY']}) heading {u['cartA']}deg, "
              f"d={u['d']} from character, heading lag {u['aDelta']}deg")

        # Was the character turning? A swing failure and a straight-line width failure need
        # completely different fixes, and this is what tells them apart.
        idx = u.get('sampleIndex')
        turning = None
        if idx is not None and idx > 1:
            window = [x for x in samples[max(0, idx - 25):idx] if 'plA' in x]
            if len(window) > 1:
                headings = [x['plA'] for x in window]
                spread = max(headings) - min(headings)
                if spread > 180:
                    spread = 360 - spread
                turning = spread
                print(f"    character heading changed {spread:.0f}deg over the preceding ~1s")

        near = u.get('nearCart') or []
        if not near:
            print("    nothing with a hitbox within 33 units of the cart.")
            print("    -> suspicious: terrain, or an obstacle just outside the scan radius.")
            continue

        def edge_gap(o):
            """Centre distance minus both circumscribed radii: 0 means just touching.

            Circumscribed rather than axis-aligned, so it holds at any rotation. Slightly
            conservative for elongated boxes, which is the right way to be wrong here.
            """
            hw = abs(o['hbEndX'] - o['hbBeginX']) / 2.0
            hh = abs(o['hbEndY'] - o['hbBeginY']) / 2.0
            return o['distToCart'] - math.hypot(hw, hh) - 5.892

        near = sorted(near, key=lambda o: edge_gap(o))
        print(f"    {len(near)} obstacle(s) near the cart, closest surface first:")
        for o in near[:6]:
            print(f"      centre {o['distToCart']:6.2f}  edge gap {edge_gap(o):+6.2f}  {o['name']}")

        culprit = near[0]
        print(f"    likely culprit: {culprit['name']}, "
              f"resting {edge_gap(culprit):+.2f} units clear of contact")

        # The failure is not instantaneous: the cart drops from Homing to LinMove at reduced
        # speed when it fouls something, then stops, and only then does the tie break. Both
        # stages are visible well before the server's message, which is what makes recovery
        # possible rather than merely detectable.
        if idx is not None:
            win = [x for x in samples[max(0, idx - 120):idx] if 'cartMoving' in x or 'cartV' in x]
            lin = [x for x in win if x.get('cartMoving') == 'LinMove']
            if lin:
                lead = u['t'] - lin[0]['t']
                speeds = [x.get('cartV', 0) for x in lin]
                pv = [x.get('plV', 0) for x in lin]
                print(f"    deflected (LinMove) {lead} ms before the untie, "
                      f"cart {min(speeds):.0f}-{max(speeds):.0f} vs character {min(pv):.0f}-{max(pv):.0f}")
                stopped = [x for x in win if x['t'] > lin[0]['t']
                           and x.get('cartMoving') is None and x.get('cartV', 0) == 0]
                if stopped:
                    print(f"    cart fully stopped  {u['t'] - stopped[0]['t']} ms before the untie")

        if turning is not None and turning > 45:
            verdict("untied while turning through ~%.0f deg -> consistent with a SWEPT collision. "
                    "If this repeats, turn clearance is real and Phase 2 stays in the plan."
                    % turning)
        elif turning is not None:
            verdict("untied on a near-straight leg -> a width problem, not a swing problem. "
                    "Obstacle dilation alone should prevent this one.")
        else:
            verdict("not enough preceding track to say whether it was mid-turn.")

    if len(unties) > 1:
        print()
        print(f"  {len(unties)} unties captured. Look for the pattern: mostly-turning means the "
              "swept volume collides;")
        print("  mostly-straight means a wider corridor is enough.")


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else find_latest_carttest()
    if not path or not os.path.exists(path):
        print("No carttest JSON found. Pass one explicitly:")
        print("  python analyze_carttest.py <path>")
        return 1

    with open(path, encoding='utf-8') as f:
        data = json.load(f)

    info = data.get('runInfo', {})
    samples = data.get('samples', [])
    events = data.get('events', [])
    probes = data.get('pfProbes', [])

    print(f"Trace   : {path}")
    print(f"Started : {info.get('startTime')}")
    print(f"Samples : {len(samples)}   Events: {len(events)}   PF probes: {len(probes)}")
    if info.get('truncated'):
        print("WARNING : sample cap hit - the tail of the run is missing.")
    cart = data.get('cart')
    if cart:
        hb = cart.get('hitBox') or {}
        print(f"Vehicle : {cart.get('name')} (id {cart.get('id')})")
        if hb:
            print(f"Hitbox  : ({hb.get('beginX')}, {hb.get('beginY')}) .. "
                  f"({hb.get('endX')}, {hb.get('endY')})")
    if not samples:
        print("\nNo samples recorded.")
        return 1

    present = q_attachment(samples, events)
    q_pf_membership(probes)
    q_geometry(present or [])
    q_pose(samples)
    q_untie(events, samples)
    q_marker(samples, events)
    q_unties(data)
    print()
    return 0


if __name__ == '__main__':
    sys.exit(main())
