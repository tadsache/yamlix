# Releasing

Tagging is the release. `v0.2.0` on `main` runs the tests and the Plugin
Verifier, signs the plugin, publishes it to the JetBrains Marketplace, and
drafts a GitHub release holding the same signed zip.

Everything below the first section is one-time setup.

## Cutting a release

1. Move the `## [Unreleased]` entries in `CHANGELOG.md` under a new version
   heading with today's date.
2. Update `<change-notes>` in `src/main/resources/META-INF/plugin.xml`. This is
   what users read on the Marketplace listing and in the update dialog — it is
   not the changelog, and it should be shorter than one.
3. Set `pluginVersion` in `gradle.properties`. The workflow fails the release if
   the tag and this value disagree, because the Marketplace refuses to accept a
   version number twice and a mismatch costs you one.
4. Commit, then tag and push:

   ```bash
   git tag v0.2.0
   git push origin main --tags
   ```

5. If the `marketplace` environment has a required reviewer, approve the run.
6. JetBrains reviews the upload themselves — usually a day or two for a first
   submission, faster afterwards. Publish the drafted GitHub release once it
   clears.

A pre-release version goes to a named channel automatically: `0.2.0-beta.1`
publishes to `beta`, which only users who have added that channel in
**Settings → Plugins → Manage Plugin Repositories** will see. A plain version
goes to the default, stable channel.

## One-time: the first upload is manual

`publishPlugin` can only update a plugin that already exists. The first version
of a new plugin ID has to go through
[plugins.jetbrains.com/plugin/add](https://plugins.jetbrains.com/plugin/add) by
hand: build it locally with `./gradlew buildPlugin`, sign it (see below, with
the environment variables set locally for this one occasion), and upload
`build/distributions/*-signed.zip` through the form.

Every release after that is a tag.

## One-time: the signing certificate

The Marketplace has rejected unsigned plugins since 2021. Generate a key and a
self-signed chain — self-signed is what JetBrains expects here; the certificate
identifies *the plugin*, and continuity of the key across versions is what
matters, not a CA vouching for you.

```bash
openssl genpkey -aes-256-cbc -algorithm RSA -out private.pem -pkeyopt rsa_keygen_bits:4096
openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
```

The first command asks for a passphrase; that becomes `PRIVATE_KEY_PASSWORD`.

**Back both files up somewhere you will still have in three years, outside this
repository.** Losing the key does not lock you out of the Marketplace — JetBrains
can reset it — but it turns a release into a support ticket. Neither file ever
belongs in git; `.gitignore` does not know about them, so keep them out of the
working tree entirely.

## One-time: the secrets

**Settings → Secrets and variables → Actions**, four repository secrets:

| Secret | Value |
|---|---|
| `PRIVATE_KEY` | the whole of `private.pem`, `-----BEGIN` line through `-----END` line |
| `PRIVATE_KEY_PASSWORD` | the passphrase from `openssl genpkey` |
| `CERTIFICATE_CHAIN` | the whole of `chain.crt` |
| `PUBLISH_TOKEN` | a Marketplace token, from [your JetBrains profile](https://plugins.jetbrains.com/author/me/tokens) |

Paste the PEM files whole, including the header and footer lines and the
trailing newline. A chain that is missing its final newline fails signing with
an error that does not mention newlines.

## One-time: the approval gate

Without this, pushing a tag publishes to the Marketplace with nothing in the
way. That is a reasonable choice for a project where the tests and the Verifier
are the review — but it should be chosen rather than discovered.

To require a human: **Settings → Environments → New environment → `marketplace`**,
then tick **Required reviewers** and add yourself. The `release` job then waits
for approval, after the tests and the Verifier have passed and before anything
is signed.

## If a release goes wrong

A published version cannot be unpublished by you, and its number cannot be
reused. What you can do is hide it: on the plugin's Marketplace page, under
**Versions**, a version can be removed from the update channel so nobody new
receives it. Then fix forward with a new patch version. Ask JetBrains support
for anything stronger.
