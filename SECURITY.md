# Security Policy

## Scope

The plugin makes no network calls and collects nothing. Everything it knows
comes from files already open in your IDE, and it never shells out to `ansible`
or executes anything from the project it reads.

That leaves a small surface, but not an empty one. Worth reporting:

- Anything that causes the plugin to execute content from a project it opens.
- Anything that writes outside the project, or reads outside it and surfaces
  what it found.
- A crafted YAML or inventory file that hangs or exhausts memory in the IDE.
- Any path where a vault-encrypted or otherwise secret value is rendered,
  logged, cached, or indexed as plaintext. The plugin is meant to name vault
  values as unresolvable, never to show them.

## Supported versions

The latest release only. This is a single-maintainer project; fixes go into the
next version rather than into backports.

## Reporting

Email **support@yamlix.dev** — please don't open a public issue for a
vulnerability. Include the version, the IDE and build, and a project layout that
reproduces it (invented identifiers, as everywhere else in this repository).

Expect an acknowledgement within a week. If the report is valid you'll be
credited in the CHANGELOG unless you'd rather not be.
