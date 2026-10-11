# Publisher

Let an existing application publish itself to Embabel worlds as a realm.

An application adds a **publisher library** and declares what a world may see: its types, how to look
them up in batches, what it can filter and aggregate itself, the operations it allows and which of
them need approval, the events it raises, and skills that explain how to use it well. A world that is
given the application's URL installs all of that as a realm. Nobody writes the realm, and the world
sees only what the application's developers chose, through the application's own service layer and
authorization.

| | |
|---|---|
| [`spec/PROTOCOL.md`](spec/PROTOCOL.md) | The **Realm Publishing Protocol**: language-neutral HTTP and JSON. |
| [`conformance/`](conformance/) | The standard conformance suite. A publisher library in any language is conformant when it passes. |
| [`skill/embabel-realm-publisher/`](skill/embabel-realm-publisher/SKILL.md) | The adoption skill: an Agent Skill that lets a coding agent drop a publisher into an existing application. Every library ships it. |
| [`libs/`](libs/) | Publisher libraries. Java (with a Spring Boot starter) first; others welcome. |

**Status: proposal.** The protocol is being designed in the open before any library is released.
A first slice of the Java library exists and publishes [Spring PetClinic](conformance/README.md#fixtures),
but nothing here is released or ready to depend on yet.

## Turning it off

Every publisher library honours one switch: `EMBABEL_REALM_ENABLED=false` turns all publication off,
whatever the configuration files say.

## Licence and contributions

Everything in this repository — specification, schema, conformance suite, skill and libraries — is
licensed under the [Apache License 2.0](LICENSE). Anyone may implement either side of the protocol.

Contributions are welcome, including publisher libraries for new languages. See
[CONTRIBUTING.md](CONTRIBUTING.md). Every commit carries a
[Developer Certificate of Origin](https://developercertificate.org/) sign-off.
