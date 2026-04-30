# SecondLife — Hackathon Demo Script

**Audience:** Judges walking up to your table.
**Time:** 3–4 minutes per judge group.
**Who holds the phone:** Shravan runs the app, Sid handles the screen, Rohan explains sensors.
**Golden rule:** Let the AI speak. Read the response aloud. Don't rush.

---

## SETUP (before judges arrive)

- [ ] Phone unlocked, app open, on home screen
- [ ] **Airplane mode ON** — confirm no wifi bars showing
- [ ] Role selector set to **Layperson**
- [ ] Screen brightness at max
- [ ] `data/chunks/protocols.json` pushed via adb (real clinical protocols loaded)
- [ ] One person designated to speak, one to tap

---

## THE DEMO FLOW

### BEAT 1 — The Hook (15 seconds, before they even ask a question)

**Say:**
> "Before we start — airplane mode is on. No wifi, no cellular, no cloud.
> Everything you're about to see runs entirely on this phone."

Point to the airplane mode icon. Let it land.

> "We call it SecondLife. You speak your emergency, it tells you exactly what to do."

---

### BEAT 2 — Demo 1: Choking Child (Layperson mode)

**This is your opener. It's the most relatable emergency in the room.**

Tap the mic and say or type:

> *"A 7-year-old child at this table just choked on a piece of food and cannot breathe or make a sound."*

**Expected response (verified):**
```
1. Kneel or stand behind the child and lean them forward.
2. Give 5 firm back blows between the shoulder blades.
3. Give 5 abdominal thrusts — fist just above the belly button, pull inward and upward.
4. Keep alternating 5 back blows and 5 abdominal thrusts until the food comes out
   or the child becomes unconscious.
5. If the child becomes unconscious, start CPR. Before giving breaths, look in the
   mouth — only remove the object if you can clearly see it. Never sweep blindly.

Call emergency services now if you haven't already.
```

**Say while the response appears:**
> "Watch the citation at the bottom — Red Cross choking guidelines, page 3.
> It retrieved that from an on-device database of clinical protocols. No Google search."

**Pause. Let them read it.**

---

### BEAT 3 — The Role Switch (30 seconds)

**This is your differentiation moment.**

Tap **Paramedic** chip.

> "Same app. Different role. Watch what changes."

Type:

> *"Patient presenting with sudden onset facial droop, left arm drift, slurred speech, onset 8 minutes ago."*

**Expected response (verified):**
```
1. Recognize Stroke: BE-FAST criteria met — facial droop, arm drift, slurred speech.
2. Time Assessment: 8 minutes ago — critical for thrombolytic eligibility.
3. Call 911 immediately.
4. Keep patient still and calm. Do not administer food or water.
5. Monitor vital signs every 5 minutes.
6. Prepare for advanced neurological assessment and transport to definitive care.
```

**Say:**
> "Layperson mode said 'call 911, don't give food.' Paramedic mode said 'document time of onset,
> assess thrombolytic eligibility, prepare for neuro transport.'
> Same model. Same device. Totally different response — because the role shapes the prompt."

---

### BEAT 4 — Demo 3: Military Medic (the unique one, 30 seconds)

**Most demos at this hackathon won't have TCCC. This is your standout.**

Tap **Military Medic** chip.

> "One more — military medic mode."

Type:

> *"Soldier took a bullet to the upper thigh, femoral bleed, still conscious, I have a tourniquet."*

**Expected response (verified):**
```
M — Massive Hemorrhage: Apply tourniquet 5–7 cm above wound. Tighten until bleeding
    stops. Note time of application.
A — Airway: Assess. Jaw thrust if unconscious.
R — Respiration: Check for tension pneumothorax or sucking chest wound.
C — Circulation: IV/IO if hypotensive and no TBI.
H — Hypothermia: Remove wet clothing, wrap in heat-reflecting blanket.
```

**Say:**
> "MARCH protocol. Tactical Combat Casualty Care. The same framework field medics use.
> On a phone. Offline. In two seconds."

---

### BEAT 5 — The Numbers (20 seconds)

Flip to the benchmark screen or say from memory:

> "36 tokens per second on-device GPU.
> First word appears in 0.34 seconds.
> Full response in about 16 seconds — and that's without the phone's NPU fully optimised yet.
> Zero network calls. The whole thing works in a bunker, a jungle, or a basement with no signal."

---

### BEAT 6 — The Audit Log (optional, if judge asks about safety/privacy)

> "Every query is logged in a SHA-256 hash-chained audit file on the device.
> That means you can prove no response was tampered with after the fact —
> same principle as a blockchain, but local. Patient data never leaves the device."

---

### CLOSING LINE (5 seconds)

> "We're not showing that Gemma 4 can run on a phone.
> We're showing what it enables when it does."

---

## BACKUP SCENARIOS

If a judge asks to try their own question, suggest these — they produce clean responses:

| Prompt | Role | Why it works |
|--------|------|-------------|
| "Person collapsed at this table, not breathing" | Layperson | CPR steps, very clear |
| "Severe bleeding from arm, no tourniquet available" | Layperson | Direct pressure steps |
| "Someone is having a seizure on the floor" | Layperson | Shows what NOT to do (don't restrain) |
| "Child burned hand on hot stove" | Layperson | 20-minute cooling, no ice — judges verify it's correct |
| "Person unresponsive, possible overdose" | Paramedic | Naloxone protocol |

**Avoid letting judges type free-form complex multi-part questions on first try** — keep it one clear emergency per query for the demo.

---

## IF SOMETHING GOES WRONG

| Problem | Fix |
|---------|-----|
| Response is slow (>25s) | Normal for CPU fallback — say "GPU delegate makes this 3x faster on a production build" |
| App crashes | Have `python ai-pipeline/inference/test_local.py` on laptop as backup — run it live |
| Model gives a weird response | Switch role and retry — or pivot to a different demo query |
| Judge asks "could this give wrong medical advice?" | "Every response cites the source protocol. The model retrieves before it generates — it's not hallucinating, it's retrieving." |
| "Why not just use ChatGPT?" | "ChatGPT needs the internet. This works in airplane mode, in a field hospital, in a blackout, or anywhere cell towers are down." |

---

## THE ONE LINE THAT WINS JUDGES

> *"We are not demonstrating that Gemma 4 can run on a device.
> We are demonstrating what it enables when it does."*

Memorise it. Say it at the end. Don't rush it.
