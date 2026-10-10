# Contributing

## Sign your commits (DCO)

This project uses the [Developer Certificate of Origin](https://developercertificate.org/). Every
commit must carry a sign-off line certifying that you wrote the change, or otherwise have the right
to submit it, under the project's licence:

```
Signed-off-by: Your Name <you@example.com>
```

`git commit -s` adds it. A pull request with unsigned commits cannot be merged.

## The protocol leads

A change to what publishers or worlds must do starts in [`spec/PROTOCOL.md`](spec/PROTOCOL.md).
Within the same pull request it also updates:
- the JSON Schema;
- the conformance suite;
- the adoption skill's core, if the workflow or its rules change;
- every library in this repository that the change affects, or an issue for each that cannot be
  updated in the same pull request.

A library never gains a protocol feature that the specification does not describe.

## Adding a publisher library for a new language

A new library lives in `libs/<language>/` and brings:
1. **The library**, implementing the protocol for that language's common stacks, and honouring
   `EMBABEL_REALM_ENABLED`.
2. **CI that runs the conformance suite** against a sample application built with the library.
   A library is described as conformant only at the protocol versions whose suite it passes.
3. **`skill/embabel-realm-publisher/references/<stack>.md`**: the adoption skill's reference for that
   stack. It maps the skill's workflow onto the library and lists the stack's pitfalls.
4. **A release** in that ecosystem's usual registry. The release bundles the adoption skill and
   offers one command that installs it into a project.

Open an issue first to say which language and stacks you intend to cover, so that two people do not
build the same library. A library maintained in its own repository can also be listed as conformant
when it passes the suite.

## Licence

By contributing you agree that your contribution is licensed under the
[Apache License 2.0](LICENSE).
