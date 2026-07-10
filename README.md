# cql-v5-probe

A minimal feasibility probe: **can the CQL engine 5.0.0 (the Kotlin Multiplatform line of
[cqframework/clinical_quality_language](https://github.com/cqframework/clinical_quality_language))
evaluate FHIR-typed CQL over the
[ohs-foundation/kotlin-fhir](https://github.com/ohs-foundation/kotlin-fhir) model — with no HAPI
anywhere in the process?**

**Result: yes, at small scale.** A live `dev.ohs.fhir:fhir-model-r4` Patient drives the released
`org.cqframework:engine:5.0.0` to evaluate `P.gender.value → 'female'`, through a hand-written
converter. Both libraries are consumed as released from Maven Central — no forks, no snapshots.

## Run it

Requires JDK 17+ (the Gradle wrapper handles the rest; Kotlin 2.3.10, Gradle 9.3.1).

```
./gradlew run
```

Expected output:

```
kotlin-fhir Patient.gender.value.getCode() = female
v5 engine evaluated P.gender.value    = 'female'   (expect: 'female')
v5 engine evaluated P.birthDate.value = @1985-03-14   (expect: 1985-03-14)
```

## The increments

The probe is built in small steps, each isolating one unknown. The current `Main.kt` is
increment 5c; the earlier steps are strict subsets of it.

| # | Question | Result / finding |
|---|----------|------------------|
| 1 | Does the released v5 engine run in a plain JVM consumer? | Yes — evaluated `1+1 → 2`. Gotchas: the consumer's Kotlin must be ≥ the engine's build Kotlin (2.3.10; older fails with a metadata "internal compiler error"), and SLF4J must be added explicitly (the engine logs via kotlin-logging → SLF4J, not pulled transitively). |
| 2 | Does FHIR-typed CQL *compile* without HAPI? | Yes — `using FHIR version '4.0.1'` compiles to ELM given only `fhir-modelinfo-4.0.1.xml` (spec-derived data, parsed by the KMP-native `parseModelInfoXml`). The FHIR ModelInfo is model-agnostic; HAPI was never needed at compile time. |
| 3 | Does the engine *evaluate* FHIR-shaped values without HAPI? | Yes — a hand-built `ClassInstance` tree (`Patient{gender: code{value: "female"}}`), passed as a parameter, evaluates `P.gender.value → 'female'`, including the FHIR-primitive `.value` unwrap. Confirms the ADR-005 convert-at-the-boundary design empirically: the engine only ever does map lookups on its own value types (`PropertyEvaluator` → `ClassInstance.elements[property]`). |
| 4 | Do engine + kotlin-fhir + a converter compose in one build? | Yes — a real `Patient(gender = Enumeration.of(AdministrativeGender.Female, null))` from `fhir-model-r4:1.0.0-beta05` is converted (`patientToClassInstance`) and evaluated. No Kotlin-metadata clash, no transitive-dependency clash. |
| 5a | Can the converter discover fields instead of naming them? | Yes — replaced the hand-written converter with a generic walker: `kotlin-reflect` enumerates `memberProperties` of any resource, a `when` maps each value by runtime type, unmapped types print a TODO work-list. Same output as increment 4 with zero field names in the converter. (JVM reflection is a deliberate bootstrap — isolated so a codegen'd navigation layer can replace it for non-JVM targets.) |
| 5c | Does date **precision** survive conversion? | Yes — the date row is one line, `Date(fhirDate.toString())`: every sealed `FhirDate` variant (Year / YearMonth / Date) has an ISO `toString()`, and the engine's string constructor infers precision from segment count. A year-only birthDate evaluates as `@1985`, **not** `1985-01-01` — no precision collapse, so downstream age logic keeps honest uncertainty semantics. (Trap found: three classes named `Date` are in play, and the engine's `Date(LocalDate, precision)` constructor takes its own multiplatform `LocalDate`, not `kotlinx.datetime.LocalDate` — the string route sidesteps the mismatch.) |

## What this probe is NOT

It is **not a DataProvider** — yet. The converter now walks arbitrary properties of any
resource (JVM reflection), but carries only two mapping rows (coded values, dates). The
precise remaining gap for on-device CQL on the post-HAPI stack is:

- **the remaining rows of a general `toCqlValue`** — lists/cardinality, choice types
  (`value[x]`), nested complex types, quantities, references — into `ClassInstance`s.
  This is the `engine-fhir` mechanism re-pointed from HAPI reflection to kotlin-fhir.
- **a `RetrieveProvider`** — resolve `[Condition: "some-valueset"]`-style retrieves against a
  kotlin-fhir store, returning converted values.
- (behind those: a `TerminologyProvider`, and — for `$apply` — a write-path twin of the
  navigation layer for `dynamicValue`.)

A scoping note on the converter: kotlin-fhirpath already *generates* reflection-free navigation
over kotlin-fhir (`getProperty` / `getAllChildren` / `unwrapChoiceValue`), but emits it all
`internal` — private engine guts, not reusable. So a CQL binding must currently re-derive child
enumeration itself: JVM reflection (simplest; Android-fine), a kotlinx.serialization tree-walk
(multiplatform, but JSON-shaped), or its own codegen from the FHIR StructureDefinitions (the
proven ecosystem pattern, most work). Whether there should instead be one *shared, public*
navigation layer over kotlin-fhir is an open ecosystem question.

## Notes

- `src/main/resources/fhir-modelinfo-4.0.1.xml` is the FHIR R4 ModelInfo from
  [cqframework/clinical_quality_language](https://github.com/cqframework/clinical_quality_language/blob/main/Src/java/quick/src/main/resources/org/hl7/fhir/fhir-modelinfo-4.0.1.xml)
  (Apache-2.0), included verbatim.
- Built and verified 2026-07-02 (increments 1–4) and 2026-07-08 (5a, 5c) against
  `engine`/`cql-to-elm` 5.0.0 and `fhir-model-r4` 1.0.0-beta05, all from Maven Central.

## Context

Author: I maintain an independent SMART-Guidelines-style DAK for cervical-cancer screening
(an L2→L4 encoding of WHO's 2021 screening recommendations — not a WHO publication; WHO has
published the narrative guideline but no official DAK for it yet), and run its CQL/ELM
on-device today via `PlanDefinition/$apply` on the android-fhir workflow stack. This probe
scopes what it would take for that content to run on the post-HAPI KMP stack.
