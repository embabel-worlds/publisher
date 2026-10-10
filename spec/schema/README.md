# Wire schema

[`protocol.schema.json`](protocol.schema.json) is JSON Schema 2020-12 for every message of the
[protocol](../PROTOCOL.md), one `$defs` entry each: `Manifest`, `Index`, `FetchRequest`,
`FetchResponse`, `QueryRequest`, `QueryResponse`, `AggregateRequest`, `AggregateResponse`,
`ChangesResponse`, `DryRunResult` and `Problem`, with `Where` for the expression tree.

The schema checks shape. The prose specification is the contract for meaning: the batch contract,
null rules, operator semantics and limits a schema cannot express. The conformance suite checks
those.

`examples/valid/<Def>.<label>.json` must validate against `$defs/<Def>`, and
`examples/invalid/<Def>.<label>.json` must not. `python3 check.py` (with the `jsonschema` package)
checks them. A change to the schema comes with examples showing what it admits and what it refuses.
