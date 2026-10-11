# Conformance suite

One standard suite for every publisher library. It talks to a publisher only over HTTP, so it tests
any language the same way. Its requirements are in the protocol's
[§11](../spec/PROTOCOL.md#11-conformance).

**Status: not yet written.** The plan:

- **A runner, packaged as a container and a CLI.** It takes a publisher URL and a credential, and
  produces a JUnit XML report and a JSON report.
- **Fixture-free.** The suite reads the publisher's own manifest and data. For each declared
  capability, it compares what the publisher returns with what it should return.
- **A reference evaluator for the expression grammar.** This makes the operator semantics and null
  rules of §4.1 executable. Pushed results are compared with unfiltered fetches evaluated by the
  suite.
- **Embeddable on the JVM**, so the Java library's test support can run it in-process.
- **Versioned with the protocol.** A library is conformant at the protocol versions whose suite it
  passes.

## Fixtures

[`fixtures/petclinic/`](fixtures/petclinic/) holds real exchanges with Spring PetClinic published by the
Java starter ([embabel-worlds/spring-petclinic](https://github.com/embabel-worlds/spring-petclinic),
branch `embabel-publisher`), captured with curl from the running application. They are for building
and testing the world side against a real publisher before the suite exists. Each file is named like
the schema's examples, `<Def>.<label>.json`, and validates against that `$defs` entry; `VerbInput` and
`VerbOutput` validate against the `addVisit` verb's `input` and `output` in `Manifest.json`.

| File | What |
|---|---|
| `Manifest.json` | `GET /embabel` |
| `FetchRequest/FetchResponse.pets-by-owner.json` | pets of owners 1, 6 and 99 that are cats or dogs |
| `QueryRequest/QueryResponse.owners-in-madison.page-1/2.json` | owners in Madison by last name, two per page, walked by cursor |
| `VerbInput/VerbOutput.addVisit.json` | booking a visit |
