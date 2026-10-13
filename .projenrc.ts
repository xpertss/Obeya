import { JavaSpringBootProject } from '@xpertss/projen-types';
import { github } from 'projen';

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
// A shaded jar so spikes and CI can run it with `java -jar` (P0.3).
const stubModel = project.addModule({
  dir: 'tools/stub-model',
  artifactId: 'obeya-stub-model',
  description: 'Scripted Anthropic Messages model endpoint for CI and spikes',
});
stubModel.addDependency('tools.jackson.core/jackson-databind');
stubModel.addPlugin('org.apache.maven.plugins/maven-shade-plugin@3.6.0', {
  configuration: {
    createDependencyReducedPom: false,
    transformers: {
      transformer: {
        '@implementation': 'org.apache.maven.plugins.shade.resource.ManifestResourceTransformer',
        'mainClass': 'org.xpertss.obeya.stubmodel.StubModelServer',
      },
    },
  },
  executions: [{ id: 'shade', phase: 'package', goals: ['shade'] }],
});

// CI jobs beside the Maven build (PLAN P0.2). Until the code they build lands,
// each one checks its toolchain: native image in P1.5, the SPA in P6.1.
project.buildVerifyWorkflow.addJob('native', {
  name: 'CLI native image',
  runsOn: ['ubuntu-latest'],
  permissions: { contents: github.workflows.JobPermission.READ },
  steps: [
    { name: 'Checkout', uses: 'actions/checkout@v7' },
    {
      name: 'Setup GraalVM CE',
      uses: 'graalvm/setup-graalvm@v1',
      with: {
        'java-version': '21',
        'distribution': 'graalvm-community',
        'native-image-musl': 'true',
        'github-token': '${{ secrets.GITHUB_TOKEN }}',
      },
    },
    { name: 'Native image toolchain', run: 'native-image --version' },
  ],
});

project.buildVerifyWorkflow.addJob('spa', {
  name: 'SPA build',
  runsOn: ['ubuntu-latest'],
  permissions: { contents: github.workflows.JobPermission.READ },
  steps: [
    { name: 'Checkout', uses: 'actions/checkout@v7' },
    { name: 'Setup Node', uses: 'actions/setup-node@v7', with: { 'node-version': '24' } },
    {
      name: 'Build web/',
      run: [
        'if [ ! -f web/package.json ]; then',
        '  echo "::notice::web/package.json not found; the SPA lands in P6.1"',
        '  exit 0',
        'fi',
        'cd web',
        'npm ci',
        'npm run build',
      ].join('\n'),
    },
  ],
});

// Container integration tests need rootless Podman with cgroup v2 delegation (C-4).
project.buildVerifyWorkflow.addJob('integration', {
  name: 'Fedora integration tests',
  runsOn: ['self-hosted', 'linux', 'fedora', 'podman'],
  permissions: { contents: github.workflows.JobPermission.READ },
  steps: [
    { name: 'Checkout', uses: 'actions/checkout@v7' },
    ...project.ciSetupSteps,
    {
      name: 'Check rootless Podman',
      run: [
        'podman --version',
        'test "$(podman info --format \'{{.Host.Security.Rootless}}\')" = "true"',
        'test "$(podman info --format \'{{.Host.CgroupsVersion}}\')" = "v2"',
      ].join('\n'),
    },
    { name: 'Integration tests', run: 'mvn -B verify' },
  ],
});

project.synth();
