# Chart Lines in DOM - how it works (v0.1.0, MotiveWave 7.1.1)

## The problem
The DOM and the chart are separate components; the platform has no setting that draws a chart drawing in the
DOM. The SDK gives a study no access to the drawings of its chart.

## What was found (by reading the platform's classes and testing inside the running platform)

| Fact | How it was established |
|---|---|
| `DataContext.addNote(price, text, TextStyle)`, `updateNote`, `removeNote`, `removeAllNotes` are **public SDK API**. A note is stored by the chart (one list per price) and tagged with the study's id. | `javap` of the SDK; the spike added notes |
| The DOM shows the notes of the chart it is **linked** to, in its **Notes** column (a cell filled with the note's background colour and its text). Ticks per row > 1: the note appears on the row that contains its price. | disassembly of the DOM panel and its row renderer; the spike (5 neighbouring ticks) |
| That column is **off by default** (DOM header "+" -> Notes). | the DOM column menu |
| `removeAllNotes()` removes the notes of this study only (matched by study id, which survives a reload). | disassembly |
| Drawn lines are figure objects held by the chart. A horizontal line has two anchor points (`Coordinate` time/value) on one price and a `LineInfo` with its colour. Price and colour were checked against the saved workspace (31094.75 ...). | runtime dump from a spike study |
| The platform calls a study's `destroy()` when it is removed. | disassembly |

## Design
```
chart (CompositeGraph)                     SDK                          DOM (linked)
  figures --reflection--> ChartLines ---> NoteSync --addNote/update/remove--> Notes column
  (horizontal lines)      (price, colour)  (diff)
```
- **chart/ChartLines** - the only code that touches platform internals. Finds the chart through the data
  context, the drawings through "the collection that holds figure objects", a horizontal line by its shape,
  the colour through `LineInfo`. No obfuscated field or method *name* is relied on (only the shape of the
  data). Anything unexpected -> `Unavailable`, the DOM is left alone, the reason is logged once.
- **sync/NoteSync** - pure logic: makes the set of notes equal to the wanted set with the fewest calls (add,
  update colour/text, remove). One note per price. A refused call never corrupts its state; it retries.
- **DomLines** - the study: settings, a daemon timer (300 ms) that runs a pass on the platform's UI thread,
  `removeAllNotes()` at the start of every pass-zero and in `destroy()`, so nothing is left behind.

## Why polling
The platform sends a study no event when a drawing changes. Reading ~20 figures by reflection takes well under
a millisecond, so a 300 ms poll is invisible and keeps the DOM within a fraction of a second of the chart.

## Risks
- Reading the drawings depends on MotiveWave internals: a platform update can break it (the study then does
  nothing and says why in the log). Everything the DOM side uses is public SDK.
- Not yet handled: lines hidden on the chart; non-horizontal drawings; Fibonacci levels.

## Tests (21, run by build.sh)
NoteSync: add/no-op/update/move/remove, one note per price, refused add/update retried, clear, forget, float
round trip. ChartLines (with stand-in classes shaped like the platform's): horizontal lines found, sloped /
vertical / single-anchor / destroyed figures ignored, colours, lines added/removed between reads, no chart.
