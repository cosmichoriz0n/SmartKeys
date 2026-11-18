# SmartKeys

A personal fork of [HeliBoard](https://github.com/Helium314/HeliBoard) (GPL-3.0) that adds iPhone-style typing accuracy features.

## Changes from HeliBoard

**Smart touch targets** (`keyboard/SmartTouch.kt`, hooked into `KeyDetector` and `InputLogic`)
- After every keystroke, the dictionary's current completions are turned into a probability for each possible next letter (and for space, when the word is already complete).
- When a tap lands near a key border, the keyboard picks the key that maximises `0.6·ln P(letter) − distance²/(2·0.45²)`, i.e. likely letters get a bigger invisible hit area. Visible keys never move.
- Anchoring: a tap in the central 60% of a key always returns that key, so unusual words can still be typed.
- Predictions are discarded on every new input or cursor move, so a slow suggestion update never biases the wrong letter.
- Learns your habitual tap offset (slow moving average, capped at 15% of a key).
- Off in password fields and non-letter layouts.

**Learn from undone corrections** (`latin/suggestions/RevertMemory.kt`)
- Backspacing right after an autocorrection restores what you typed (HeliBoard behaviour). SmartKeys now also remembers that word and never auto-corrects it again (up to 1000 words).

Both can be switched off under Settings → Text correction.

Also changed: app id `com.pj.smartkeys` (installs alongside HeliBoard), name, content provider authorities, CI builds on push.
