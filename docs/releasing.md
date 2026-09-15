# Releasing Camel Kit Knowledge

Release `0.0.1` is followed by development version `0.0.2-SNAPSHOT`.
Release tags contain fixed POM versions; `main` contains the next snapshot version.

## Prepare

1. Start from reviewed `main` in a clean release branch. Update README, changelog,
   documentation and the companion website. Keep Ship labeled Technology Preview.
2. Set the reactor version to `0.0.1` and the root SCM tag to `camel-kit-knowledge-0.0.1`.
3. Run the full release build with artifact signing (a local GPG key is required):

   ```bash
   ./mvnw -B -Prelease,sourcecheck -Deval.requireVectors=true clean install
   ```

4. Review the diff, commit with `git commit -S`, and tag that exact commit as `camel-kit-knowledge-0.0.1`.
   Record its full SHA. Keep the generated release artifacts for inspection.
5. Advance every reactor POM to `0.0.2-SNAPSHOT` and restore the root SCM tag to `HEAD`.
   Validate the development build, sign the next-development commit, and open a PR.
   Merge the reviewed PR and push the prepared tag before dispatching publication.
   The release workflow is manual: pushing a branch or tag does not publish artifacts.

## Publish the prepared tag

The repository needs these GitHub Actions secrets:

- `MVN_CENTRAL_USER` and `MVN_CENTRAL_PASSWORD`: Central Portal token credentials.
- `GPG_ID`: artifact-signing key ID.
- `GPG_KEY`: base64-encoded private signing key, using the existing empty-passphrase convention.

Run **Publish Maven Release** (`release.yml`) from `main`, with the prepared tag and its
full reviewed commit SHA in `expected_sha`. It checks the tag and all reactor versions,
then runs the full build, signs artifacts and waits for Central publication. It does
not create commits, move tags, merge branches, or create a GitHub release.

The release profile excludes `camel-kit-knowledge-index` from Central publication;
index data is distributed through GitHub Releases. The server and supporting Java libraries
include sources, Javadoc and GPG signatures.

After publication, verify the `camel-kit-knowledge-mcp` artifact's `runner` classifier,
MCP initialization version and `camel_docs_index_info` from a clean cache.
Dispatch **Index Release** from reviewed `main` to publish the committed corpus with
working vectors required; it does not rebuild the corpus.

Create the code GitHub release with reviewed changelog notes, `--verify-tag` and
**`--latest=false`**. The latest release must remain the index release, whose
`index.json` and `knowledge-index.zip` assets are used by default server installations.
Confirm both assets remain available through `releases/latest/download/`.
