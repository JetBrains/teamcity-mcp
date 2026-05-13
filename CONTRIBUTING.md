# Contributing to TeamCity MCP Plugin

Thanks for your interest in contributing! This document covers everything you
need to get a working dev environment and land a change.

## Reporting bugs, requesting features, and asking questions

- Open an issue on the
  [GitHub issue tracker](https://github.com/JetBrains/teamcity-mcp/issues) —
  bug reports, feature requests, and general "how do I…" questions all belong
  here.
- For bugs: include TeamCity version, plugin version, MCP client (Claude/
  Junie/…), the request that failed, and any relevant log output from
  `<DATA_DIR>/logs/teamcity-server.log`.
- For security vulnerabilities: **do not file a public issue**.
  See [SECURITY.md](SECURITY.md).

## Development setup

### Prerequisites

- JDK 21 (the build also accepts a `jdkVersion` Gradle property if you need a
  different toolchain).
- Git.
- A copy of TeamCity 2026.1+ for running integration tests
  ([download](https://www.jetbrains.com/teamcity/download/)).

### Building the plugin

```bash
./gradlew serverPlugin
```

The plugin zip is written to `build/distributions/mcp.zip`.

#### TeamCity SDK credentials

The plugin depends on TeamCity SDK artifacts hosted at
`https://packages.jetbrains.team/maven/p/tc/maven`. That repository currently
requires JetBrains Space credentials. Provide them via Gradle properties:

```properties
# ~/.gradle/gradle.properties  (NEVER check this in)
spaceUsername=your-username
spacePassword=your-personal-token
```

If you have a local Maven mirror of the TeamCity SDK, point Gradle at it
instead:

```bash
./gradlew serverPlugin -PTC_LOCAL_REPO_ABS=/absolute/path/to/local/maven/repo
```

We're tracking the rough edges around external contributor access to the
TeamCity SDK — please open an issue if you're blocked.

### Running tests

**Unit tests** (fast, no external dependencies):

```bash
./gradlew test
```

**Integration tests** (require a running TeamCity server with the plugin
installed):

```bash
# Full lifecycle: build plugin, start server from a TC distribution archive,
# run tests, tear down.
TC_DIST=~/Downloads/TeamCity-2026.1.tar.gz ./run_integration_tests.sh

# Or against an already-running server:
TC_SERVER_URL=http://localhost:8111 \
TC_SERVER_TOKEN=eyJ... \
TC_HOME=/path/to/teamcity-home \
./run_integration_tests.sh
```

**End-to-end tests** (drive real AI agents in Docker — require API keys):

```bash
./gradlew e2eTest \
  -DANTHROPIC_API_KEY=sk-ant-... \
  -DOPENAI_API_KEY=sk-...
```

E2E tests are slow and external-network-dependent; they're excluded from the
default `integrationTest` task and only run when you explicitly invoke
`e2eTest`.

### IDE setup

The project is a standard Gradle Kotlin project — open `build.gradle.kts` in
IntelliJ IDEA and let it import. The `idea` plugin is applied, so module
configuration should be automatic.
