import { JavaSpringBootProject } from '@xpertss/projen-types';

// Obeya monorepo (spec/M1/PLAN.md §2). Every build file here is generated:
// edit this file, then run `npx projen`.
const project = new JavaSpringBootProject({
  name: 'obeya',
  groupId: 'org.xpertss.obeya',
  artifactId: 'obeya-parent',
  version: '0.1.0-SNAPSHOT',
  description: 'Self-hosted software factory: AI agents running kanban-shaped workflows',
  url: 'https://github.com/xpertss/Obeya',
  javaVersion: '21',
});

// Shared libraries.
const model = project.addModule({
  dir: 'obeya-model',
  artifactId: 'obeya-model',
  description: 'Workflow and card-template model: YAML parse, validation, JSON Schema compile',
});
const expr = project.addModule({
  dir: 'obeya-expr',
  artifactId: 'obeya-expr',
  description: '${{ }} expression evaluator, shared by the registry and the runner',
});
const apiClient = project.addModule({
  dir: 'obeya-api-client',
  artifactId: 'obeya-api-client',
  description: 'Client for the runner API and the job socket, shared by the runner and the CLI',
});
const harness = project.addModule({
  dir: 'obeya-harness',
  artifactId: 'obeya-harness',
  description: 'The Obeya agent harness: loop, sessions, tools, compaction, provider clients',
});

// Deployables.
// A plain module until its @SpringBootApplication class lands (P2): Boot's
// `repackage` fails on a module with no main class. Switch to
// `project.addSpringBootModule(...)` then.
const server = project.addModule({
  dir: 'obeya-server',
  artifactId: 'obeya-server',
  description: 'Spring Boot control plane: REST, runner API, scheduler, services',
});
server.addModuleDependency(model);
server.addModuleDependency(expr);

const runner = project.addModule({
  dir: 'obeya-runner',
  artifactId: 'obeya-runner',
  description: 'Runner host process: work loop, podman, built-in actions; hosts the harness',
});
runner.addModuleDependency(expr);
runner.addModuleDependency(apiClient);
runner.addModuleDependency(harness);

// The native-image profile (GraalVM CE, static musl) is added in P1.5.
const cli = project.addModule({
  dir: 'obeya-cli',
  artifactId: 'obeya-cli',
  description: 'The obeya CLI (picocli), built as a GraalVM CE native image',
});
cli.addModuleDependency(apiClient);

// Test tooling.
project.addModule({
  dir: 'tools/stub-model',
  artifactId: 'obeya-stub-model',
  description: 'Scripted Anthropic Messages model endpoint for CI and spikes',
});

project.synth();
