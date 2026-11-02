# Releasing GenSPARQL to Maven Central

GenSPARQL is published to Maven Central through the [Sonatype Central Portal](https://central.sonatype.com) under the namespace `org.gensparql`. A release builds, signs and uploads the following components:

| Artifact | Packaging | Contents |
| --- | --- | --- |
| `gensparql-parent` | pom | Shared versions and metadata |
| `gensparql-libs` | pom | One dependency for all library modules |
| `gensparql-core` | jar | Data model, similarity and threshold registry |
| `gensparql-parser` | jar | SPARQL 1.1 grammar extended with `GENOP` |
| `gensparql-llm` | jar | LLM providers (OpenAI-compatible, Anthropic, OpenRouter, mock) |
| `gensparql-engine` | jar | Query engine with `GENOP` and the similarity join |
| `gensparql-functions` | jar | `gen:*` SPARQL functions |
| `gensparql-server` | jar | Embedded Fuseki server behind the web workbench |

`gensparql-integration-tests` and `gensparql-example` are built but not published. Each jar is accompanied by a sources jar, a javadoc jar, checksums and a GPG signature, as Central requires.

The publishing configuration lives in the `release` profile of the root `pom.xml`. Builds without `-Prelease` are unchanged.

## One-time setup

1. **Domain.** Register `gensparql.org`. Central verifies a namespace through a DNS TXT record on the domain that the group id reverses.
2. **Central Portal account.** Sign in at <https://central.sonatype.com>. Under *View Namespaces*, add the namespace `org.gensparql` and copy its verification key.
3. **Namespace verification.** Add a TXT record on `gensparql.org` whose value is the verification key. Check that it resolves (`dig -t txt gensparql.org`) *before* clicking *Verify Namespace* and *Confirm*: a lookup that fails is cached and delays the verification.
4. **User token.** In the portal, generate a user token and add it to `~/.m2/settings.xml`:

   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>TOKEN_USERNAME</username>
         <password>TOKEN_PASSWORD</password>
       </server>
     </servers>
   </settings>
   ```

5. **Signing key.** Create a GPG key with a passphrase (`gpg --full-generate-key`) and publish its public part, for example `gpg --keyserver keyserver.ubuntu.com --send-keys KEYID` and `gpg --keyserver keys.openpgp.org --send-keys KEYID`. Central verifies signatures against the primary key, so sign with the primary key rather than a signing subkey.

Central's publishing plugin does not support Maven 4. Use Maven 3.9.

## Making a release

The commands below release 0.1.0. Replace the version numbers for later releases.

1. Start from an up-to-date, clean `main` and run the tests:

   ```bash
   git pull --ff-only
   mvn -B test
   ```

2. Set the release version in all modules and commit it:

   ```bash
   mvn versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false -DprocessAllModules=true
   git commit -am "Release 0.1.0"
   git tag v0.1.0
   ```

3. Build the signed bundle without uploading it, and inspect `target/central-publishing/central-bundle.zip`:

   ```bash
   mvn -B -Prelease -DskipUpload=true clean deploy
   ```

4. Upload. The command waits until Central has validated the bundle:

   ```bash
   mvn -B -Prelease clean deploy
   ```

5. Open <https://central.sonatype.com/publishing/deployments>, review the deployment *GenSPARQL 0.1.0* and click *Publish*. The `release` profile does not publish automatically, so every release passes this review.

6. Push the release commit and tag, then move `main` to the next development version:

   ```bash
   git push github main --tags
   mvn versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false -DprocessAllModules=true
   git commit -am "Prepare 0.2.0 development"
   git push github main
   ```

A published version cannot be changed or deleted on Maven Central. Fix problems with a new version.
