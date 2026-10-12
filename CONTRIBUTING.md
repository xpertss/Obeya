# Contributing to Obeya

## Build files

Build files (`pom.xml` in every module, `.gitignore`, `.editorconfig`, `LICENSE`,
`.github/workflows/`) are generated from `.projenrc.ts`. To add a module, a dependency, or a
plugin, edit `.projenrc.ts` and run `npx projen`, then commit both. Hand edits to generated files
are overwritten on the next run, and the drift check fails the pull request.

Dependency and plugin versions are exact (no ranges). Libraries that Spring Boot's BOM manages are
added without a version.

## Package naming

Every module's code lives under `org.xpertss.obeya.<module>`, where `<module>` is the module
name without the `obeya-` prefix and without hyphens:

| Module | Package |
| --- | --- |
| `obeya-model` | `org.xpertss.obeya.model` |
| `obeya-expr` | `org.xpertss.obeya.expr` |
| `obeya-api-client` | `org.xpertss.obeya.apiclient` |
| `obeya-harness` | `org.xpertss.obeya.harness` |
| `obeya-server` | `org.xpertss.obeya.server` |
| `obeya-runner` | `org.xpertss.obeya.runner` |
| `obeya-cli` | `org.xpertss.obeya.cli` |
| `tools/stub-model` | `org.xpertss.obeya.stubmodel` |

## Test layout

- Tests sit in `src/test/java`, in the same package as the code they test.
- **Unit tests** are named `*Test` and run in `mvn test` (surefire).
- **Integration tests** are named `*IT` and run in `mvn verify` (failsafe). A test is an
  integration test when it crosses a process or a container boundary.
- Golden files and other fixtures go in `src/test/resources`, under the test's package path.
- Tests that call a model use the stub model (`tools/stub-model`), never a real provider.

## Errors: `path + message`

Validation errors are returned as a list of structured `path + message` pairs: `path` locates
the offending element in the input (for example a workflow YAML path), and `message` says what is
wrong with it in plain words. Warnings use the same shape and don't block a save. Don't throw on
the first problem when the whole input can be checked: report every error found.

## Every mutation writes an audit row

Every card mutation, transition, job and step status change, approval, configuration change, role
override, runner change, and secret change writes an audit row with the actor, the timestamp, and
before/after JSON, in the same transaction as the change. The audit repository exposes only
`append`. A service method that changes state without appending an audit row is a bug.

## Definition of done

A task in `spec/M1/PLAN.md` is done when it has:

- unit tests for the logic;
- integration tests where it crosses a process or a container;
- an audit row for every mutation;
- every new API field readable through the Query API conventions;
- a green CI build.
