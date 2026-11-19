# vitruvocl

A domain-specific language for cross-metamodel OCL constraint evaluation in the Vitruvius framework.

## Overview

vitruvocl implements OCL# semantics for type-safe constraint evaluation across multiple EMF metamodels. It features a three-pass compiler architecture with smart metamodel loading, type checking, and support for cross-metamodel references.

**Key Features:**
- **OCL# Semantics**: Null-safe, everything-is-a-collection value model
- **Cross-Metamodel Constraints**: Reference entities across different metamodels in one expression
- **Type Safety**: Full static type checking before evaluation
- **EMF Integration**: Native support for Ecore metamodels and XMI instances
- **Vitruvius Framework Integration**: First-class VSUM support via `VsumWrapper`
- **Language Server**: LSP-based language server with diagnostics, completion, hover, and signature help
- **VSCode Extension**: Syntax highlighting, real-time error reporting, constraint explorer

Based on: Steinmann, F., Clarisó, R., Gogolla, M. (2025). ["Meet OCL#, a relational object constraint language"](https://link.springer.com/article/10.1007/s10270-025-01286-1).
Uses: [Vitruvius Framework](https://github.com/vitruv-tools)

---

## Quick Start

### VS Code Extension (Recommended)

The extension provides syntax highlighting, real-time diagnostics, code completion, and hover documentation.

1. Navigate to `vitruvocl-vscode-extension/` in the repository.
2. Locate the latest `.vsix` file.
3. In VS Code, open the **Extensions** view (`Ctrl+Shift+X`).
4. Click `···` → **Install from VSIX...** and select the file.

### Java Library (API)

To use vitruvocl in your own Java project, build and install it:

```bash
git clone https://github.com/vitruv-tools/VitruviusOCL.git
cd VitruviusOCL

# Build everything (JARs + VSCode extension) and install the JARs into the local Maven repository
./mvnw clean install
```

Add it to your `pom.xml`:

```xml
<dependency>
    <groupId>tools.vitruv</groupId>
    <artifactId>tools.vitruv.vitruvocl.language</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Snapshots are published to the Maven Central snapshot repository (`https://central.sonatype.com/repository/maven-snapshots/`). VitruviusOCL was part of [Vitruv-DSLs](https://github.com/vitruv-tools/Vitruv-DSLs) before; its former coordinates were `tools.vitruv.dsls:vitruvocl-language`. The Java packages (`tools.vitruv.dsls.vitruvocl.*`) are unchanged.

See [Methodologist-Template](https://github.com/vitruv-tools/Methodologist-Template/tree/vitruviusOCL) for complete working examples.

---

## Syntax

vitruvocl extends plain OCL with cross-metamodel support and follows OCL# semantics.

### Key Differences from Standard OCL

| plain OCL                      | vitruvocl               |
|---                             |---                      |
| `->` for collection navigation | `.` for all navigation  |
| `<>` for inequality            | `!=`                    |
| Null concept                   | Empty collection `[]`   |
| 0-based indexing               | 1-based indexing        |

## Type System

Everything-is-a-collection principle from OCL#:

| Notation | Meaning | Example |
|---|---|---|
| `¡T!` | Singleton (exactly 1) | `self.name` |
| `[]` | Empty / null | failed lookup |
| `{T}` | Set (unique, unordered) | |
| `[T]` | Sequence (ordered) | |
| `{{T}}` | Bag (non-unique, unordered) | |
| `⟨T⟩` | OrderedSet (unique, ordered) | |
| `?T?` | Optional (0 or 1) | null-safe ops |

`Type` is immutable; collection types are created via factory methods (`Type.set(T)`, `Type.sequence(T)`, etc.).


### Constraint Format

```ocl
-- Comments start with double dash
context MetamodelName::ClassName inv invariantName:
  expression
```

The invariant name is optional:

```ocl
context spaceMission::Spacecraft inv:
  self.mass > 0
```

### Constraint Annotations

Annotations appear between the `:` and the constraint body. Each annotation is optional and may appear at most once per constraint.

```ocl
context spaceMission::Spacecraft inv massCheck:
  @severity WARNING
  @message "Spacecraft mass must be positive"
  self.mass > 0
```

**`@severity`** — sets the severity level reported when the constraint is violated.

| Value | Meaning |
|---|---|
| `CRITICAL` | Blocker-level violation |
| `WARNING` | Default if omitted |
| `MAJOR` | Significant issue |
| `MINOR` | Minor issue |
| `INFO` | Informational only |

**`@message`** — custom violation message (string literal). If omitted, a default message is generated.

### Supported Operations

**Collection:**
- query: `includes(v)`, `excludes(v)`, `includesAll(c)`, `excludesAll(c)`, `count(v)`,
  `size()`, `isEmpty()`, `notEmpty()`, `first()`, `last()`, `at(i)` (1-based)
- modification: `including(v)`, `excluding(v)`, `union(c)`, `intersection(c)`,
  `symmetricDifference(c)`, `append(v)`, `prepend(v)`, `insertAt(i, v)`,
  `subSequence(start, end)`, `reverse()`, `flatten()`, `lift()`
- conversion: `asSet()`, `asBag()`, `asSequence()`, `asOrderedSet()`
- aggregation: `sum()`, `avg()`, `min()`, `max()`

**Iterators:** `select(x | cond)`, `reject(x | cond)`, `collect(x | expr)`,
`collectNested(x | expr)`, `forAll(x | cond)`, `exists(x | cond)`, `one(x | cond)`,
`any(x | cond)`, `isUnique(x | expr)`, `sortedBy(x | expr)`,
`iterate(x; acc : Type = init | expr)`

**Classifier:** `allInstances()` — all EMF instances of a type

**Arithmetic:** `+`, `-`, `*`, `/`, unary `-` (integer division and remainder: `div(n)`, `mod(n)`)

**Numeric:** `abs()`, `floor()`, `ceil()`, `ceiling()`, `round()`, `div(n)`, `mod(n)`

**Comparison:** `<`, `<=`, `>`, `>=`, `==`, `!=`

**Boolean:** `and`, `or`, `xor`, `not`, `implies`

**String:** `concat(s)`, `length()`, `toUpper()`, `toLower()`,
`substring(start, end)`, `indexOf(s)`, `equalsIgnoreCase(s)`, `toInteger()`, `toReal()`,
`characters()`, `matches(pattern)`, `substituteAll(pattern, replacement)`,
`substituteFirst(pattern, replacement)`, `tokenize(delimiters)`

**Transactions** (in `post` blocks): `self.attr@pre` reads the value before the transaction;
`OCLisNew`, `OCLisModified` and `OCLisDeleted` test what the transaction did to an object
(`OCLisNew(attr => value, ...)` also checks its values)

**Control Flow:**
```ocl
if condition then expr1 else expr2 endif
let x = expr in body
let x = 1, y = 2 in x + y    -- multiple bindings
```

**Type Operations:**
```ocl
oclIsKindOf(pkg::Type)   -- isinstance check
oclIsTypeOf(pkg::Type)   -- exact type check
oclAsType(pkg::Type)     -- cast
```

### Cross-Metamodel Constraints

Reference types from multiple metamodels in one expression using fully qualified names:

```ocl
context spaceMission::Spacecraft inv:
  satelliteSystem::Satellite.allInstances()
    .collect(sat | sat.massKg)
    .sum() > self.mass
```

### Correspondence Operator (`~`)

The `~` operator checks whether two objects are related by a Vitruvius correspondence. It always refers to `self` on one side and is used inside collection operations:

| Syntax | Meaning |
|---|---|
| `Collection.select(~)` | Elements that correspond to `self` |
| `Collection.reject(~)` | Elements that do NOT correspond to `self` |
| `Collection.exists(~)` | True if any element corresponds to `self` |
| `Collection.select(~, Tag = "x")` | Filter by correspondence tag |
| `Collection.select(~, Type = pkg::Class)` | Filter by type of corresponding object |
| `Collection.select(~, Tag = "x", Type = pkg::Class)` | Combined tag + type filter |

Requires a `correspondence.ecore` metamodel and at least one `.correspondence` XMI file.

```ocl
context family::Member inv:
  self.firstName == "Homer" implies
    persons::Person.allInstances().select(~, Tag = "Husband").notEmpty()
```

---

## Building from Source

### Prerequisites

- Java 21+
- Maven 3.9+ (or the included Maven wrapper `./mvnw`)
- Node.js + npm (for VSCode extension packaging)
- The Vitruvius framework with the constraint SPI (`tools.vitruv.framework.vsum` 4.0.2-SNAPSHOT or newer)

### Full Build

```bash
./mvnw clean package
```

This builds all four modules in order:
1. `vitruvocl-allinstances/` → efficient `allInstances()` computation used by the evaluator
2. `language/` → `language/target/tools.vitruv.vitruvocl.language-<version>.jar`, plus the standalone CLI `…-cli.jar` (compiler + evaluator)
3. `vitruvocl-language-server/` → `vitruvocl-language-server/target/tools.vitruv.vitruvocl.languageserver-<version>-shaded.jar`
4. `vitruvocl-vscode-extension/` → copies both JARs into `lib/`, then runs `vsce package` → `.vsix`

The root POM inherits from `tools.vitruv:parent` like the other Vitruvius repositories. All
artifacts have the group `tools.vitruv` and the prefix `tools.vitruv.vitruvocl`.

### Build without VSCode Extension

```bash
./mvnw clean package -pl vitruvocl-allinstances,language,vitruvocl-language-server
```

### Run Tests

```bash
./mvnw clean test
```

This skips the long-running tests tagged `slow` (e.g. the incremental oracle). To run them too:

```bash
./mvnw clean test -P all-tests
```

Coverage report: `language/target/site/jacoco/index.html`

---

## Adding an Operation

A new built-in operation touches these places; `OperationCoverageTest`, `ReadmeOperationsTest` and
`OclOperationDocsCoverageTest` fail until the wiring is complete:

1. **Grammar** (`language/src/main/antlr4/.../VitruvOCL.g4`): a labeled alternative in the rule of
   its family, e.g. `| 'trim' '(' ')' # trimOp` in `stringOpCS`, and the keyword in the fallback
   alternative `opMissingParens` of `operationCall` (and in `noArgOpWithArgs` if it takes no
   arguments), so that a misuse gets a helpful message. ANTLR generates a default `visitTrimOp`
   that only visits the children. The name becomes a keyword: a metamodel feature called `trim`
   can no longer be navigated by that name.
2. **Type rule:** a one-line `visitTrimOp` in `TypeCheckVisitor` delegating to the family's typing
   class (`StringOperationTyping`, `CollectionOperationTyping`, `NumericOperationTyping`,
   `IteratorOperationTyping`, `MetamodelOperationTyping`, ...), which holds the rule.
3. **Runtime semantics:** a one-line `visitTrimOp` in `EvaluationVisitor` delegating to the
   family's evaluation class (`StringOperationEvaluation`, ...), which holds the implementation.
4. **Catalog:** `add(STRING, "trim")` in `operations/OclOperations.java`; the catalog drives the
   "did you mean" suggestions.
5. **Documentation:** an entry in `OclOperationDocs` of the language server (hover, completion,
   signature help) and the operation in [Supported Operations](#supported-operations) above.

Without steps 2 and 3 the operation would still compile, but silently fall back to the generated
default, which is why `OperationCoverageTest` checks that every operation of the grammar is
declared in both visitors.

---

## Java API

```java
import java.nio.file.Path;
import tools.vitruv.dsls.vitruvocl.api.ConstraintFiles;
import tools.vitruv.dsls.vitruvocl.api.VitruvOclEngine;
import tools.vitruv.dsls.vitruvocl.result.BatchValidationResult;

// Evaluate all constraints of a project (model/src/main/{constraints.ocl,ecore,instances})
Path project = Path.of(".");
BatchValidationResult result =
    VitruvOclEngine.forProject(project).evaluateFile(ConstraintFiles.projectConstraints(project));

System.out.println("Satisfied: " + result.getSatisfiedConstraints().size());
System.out.println("Violated:  " + result.getViolatedConstraints().size());
```

See `tools.vitruv.dsls.vitruvocl.api.VitruvOclEngine` for the full API: engines for a
`ModelAccess`, a Vitruvius virtual model (`forVsum`), files (`forFiles`) or a project
(`forProject`), each evaluating single constraints, lists and `.ocl` files, with or without a
transaction, and opening incremental sessions.

### Constraints of a Vitruvius VSUM

`VitruvOclConstraintChecker` registers VitruvOCL constraints at a virtual model through the constraint SPI of the Vitruv framework (`tools.vitruv.framework.vsum.constraints`, available from Vitruv 4.0.2):

```java
import tools.vitruv.dsls.vitruvocl.vitruv.VitruvOclConstraintChecker;

VirtualModel vsum = new VirtualModelBuilder()
    // ...
    .withConstraintChecker(VitruvOclConstraintChecker.fromClasspath("constraints/prices.ocl"))
    .buildAndInitialize();

ConstraintValidationReport report = vsum.validateConstraints();
```

- The constraints are loaded with `fromClasspath(...)`, `fromFiles(...)` or `fromString(...)`.
- Every invariant is a constraint with the id `<metamodel>::<Class>::<invariantName>`, e.g. `shop::Item::positivePrice`; unnamed invariants are numbered per context (`shop::Item#1`). Contexts must name their metamodel.
- `@severity` and `@message` become the severity and message of the violations.
- Each constraint reports the metamodels it reads (`ConstraintDescriptor.metamodels()`), derived from the qualified type names of the invariant: the metamodel of the context first, then all others, e.g. `[shop, billing]` for a navigation from `shop::Item` to `billing::Invoice`. Enum literals compared with `==`/`!=` are not counted.
- The checker is an `EditableConstraintChecker` (language `vitruvocl`), so constraints can be created, updated and deleted while the VSUM is running, e.g. via the REST API of the [Vitruv-Server](https://github.com/vitruv-tools/Vitruv-Server). Invalid sources are rejected with diagnostics (line from 1, column from 0).

VSUMs generated by the Methodologist setup service or the [Vitruv-CLI](https://github.com/vitruv-tools/Vitruv-CLI) (`-c` / `-cs`) register their constraints this way.

---

## License

Eclipse Public License 2.0 — see [LICENSE](LICENSE).

See [NOTICE](NOTICE) for third-party dependency licenses.

## Acknowledgments

- Grammar derived from DeepOCL implementation by Ralph Gerbig and Arne Lange (University of Mannheim)
- Based on OCL# semantics by Steinmann, F., Clarisó, R., Gogolla, M.
