/**
 *
 */
package io.quarkus.bootstrap.workspace.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Parent;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalProject;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalWorkspace;
import io.quarkus.bootstrap.util.IoUtils;
import io.quarkus.bootstrap.workspace.SourceDir;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.paths.PathTree;

public class LocalWorkspaceDiscoveryTest {

    private static Dependency newDependency(String artifactId) {
        return newDependency(MvnProjectBuilder.DEFAULT_GROUP_ID, artifactId, MvnProjectBuilder.DEFAULT_VERSION);
    }

    private static Dependency newDependency(String groupId, String artifactId, String version) {
        final Dependency dep = new Dependency();
        dep.setGroupId(groupId);
        dep.setArtifactId(artifactId);
        dep.setVersion(version);
        return dep;
    }

    protected static Path workDir;

    private static Properties systemPropertiesBackup;

    @BeforeAll
    public static void setup() throws Exception {
        workDir = IoUtils.createRandomTmpDir();

        systemPropertiesBackup = (Properties) System.getProperties().clone();

        final Parent parent = new Parent();
        parent.setGroupId(MvnProjectBuilder.DEFAULT_GROUP_ID);
        parent.setArtifactId("parent");
        parent.setVersion(MvnProjectBuilder.DEFAULT_VERSION);
        parent.setRelativePath(null);

        final Parent parentWithEmptyRelativePath = new Parent();
        parent.setGroupId(MvnProjectBuilder.DEFAULT_GROUP_ID);
        parent.setArtifactId("parent-empty-path");
        parent.setVersion(MvnProjectBuilder.DEFAULT_VERSION);
        parent.setRelativePath("");

        MvnProjectBuilder.forArtifact("root")
                .setParent(parent)

                .addModule("module1", "root-no-parent-module", false)
                .addDependency(newDependency("root-module-not-direct-child"))
                .getParent()

                .addModule("module2", "root-module-with-parent", true)
                .addDependency(newDependency("root-no-parent-module"))
                .addDependency(newDependency("external-dep"))
                .addDependency(newDependency(LocalProject.PROJECT_GROUPID, "root-module-not-direct-child",
                        MvnProjectBuilder.DEFAULT_VERSION))
                .getParent()

                .addModule("other/module3", "root-module-not-direct-child", true)
                .getParent()

                .addModule("module4", "empty-parent-relative-path-module").setParent(parentWithEmptyRelativePath)
                .getParent()

                .build(workDir.resolve("root"));

        final Parent rootParent = new Parent();
        rootParent.setGroupId(MvnProjectBuilder.DEFAULT_GROUP_ID);
        rootParent.setArtifactId("root");
        rootParent.setVersion(MvnProjectBuilder.DEFAULT_VERSION);
        rootParent.setRelativePath(null);

        MvnProjectBuilder.forArtifact("non-module-child")
                .setParent(rootParent)
                .addModule("module1", "another-child", true)
                .getParent()
                .build(workDir.resolve("root").resolve("non-module-child"));

        // independent project in the tree
        MvnProjectBuilder.forArtifact("independent")
                .addDependency(newDependency("root-module-with-parent"))
                .build(workDir.resolve("root").resolve("independent"));
    }

    @AfterEach
    public void restoreSystemProperties() {
        if (systemPropertiesBackup != null) {
            System.setProperties((Properties) systemPropertiesBackup.clone());
        }
    }

    @AfterAll
    public static void cleanup() {
        IoUtils.recursiveDelete(workDir);
    }

    private static Path getModuleDir(String name) {
        final URL moduleUrl = Thread.currentThread().getContextClassLoader().getResource(name);
        assertNotNull(moduleUrl);
        try {
            return Path.of(moduleUrl.toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void customResourceDirectories() throws Exception {
        var project = LocalProject.loadWorkspace(getModuleDir("custom-resource-dirs"));
        assertThat(project).isNotNull();

        var module = project.toWorkspaceModule();
        assertThat(module).isNotNull();
        var mainSources = module.getMainSources();
        var resourceDirs = mainSources.getResourceDirs().iterator();

        assertThat(resourceDirs).hasNext();
        SourceDir resourceDir = resourceDirs.next();
        assertThat(resourceDir.getDir()).isEqualTo(project.getDir().resolve("relative-base-dir"));
        assertThat(resourceDir.getOutputDir()).isEqualTo(project.getDir().resolve("target/classes/META-INF/resources"));

        assertThat(resourceDirs).hasNext();
        resourceDir = resourceDirs.next();
        assertThat(resourceDir.getDir()).isEqualTo(project.getDir().resolve("src/main/custom"));
        assertThat(resourceDir.getOutputDir()).isEqualTo(project.getDir().resolve("target/processed-custom"));

        assertThat(resourceDirs).hasNext();
        resourceDir = resourceDirs.next();
        assertThat(resourceDir.getDir()).isEqualTo(project.getDir().resolve("src/main/other"));
        assertThat(resourceDir.getOutputDir()).isEqualTo(project.getDir().resolve("target/classes"));

        assertThat(resourceDirs).isExhausted();
    }

    /**
     * This test is making sure the current module isn't overridden by another module
     * from the workspace that happens to have the same group and artifact IDs
     *
     * @throws Exception
     */
    @Test
    public void workspaceWithDuplicateModuleGroupIdAndArtifactId() throws Exception {
        final Path moduleDir = getModuleDir("duplicate-ga/test/case");

        final LocalWorkspace ws = LocalProject.loadWorkspace(moduleDir).getWorkspace();

        LocalProject project = ws.getProject("org.acme", "acme-lib");
        assertNotNull(project);
        assertThat(project.getDir()).isEqualTo(moduleDir);

        assertNotNull(ws.getProject("org.acme", "acme-parent"));
        assertEquals(2, ws.getProjects().size());
    }

    @Test
    public void moduleWithDifferentParentPomRawModel() throws Exception {
        final Path moduleDir = getModuleDir("workspace-module-with-different-parent");

        final LocalWorkspace ws = LocalProject.loadWorkspace(moduleDir).getWorkspace();

        assertNotNull(ws.getProject("org.acme", "acme-runtimes"));
        assertNotNull(ws.getProject("org.acme", "acme-parent"));
        assertNotNull(ws.getProject("org.acme", "acme-build-no-bom-parent"));
        assertNotNull(ws.getProject("org.acme", "acme-build-parent"));
        assertNotNull(ws.getProject("org.acme", "acme-dependencies-bom"));
        assertEquals(5, ws.getProjects().size());
    }

    @Test
    public void moduleWithDifferentParentPomEffectiveModel() throws Exception {
        final Path moduleDir = getModuleDir("workspace-module-with-different-parent");

        final LocalWorkspace ws = new BootstrapMavenContext(BootstrapMavenContext.config()
                .setEffectiveModelBuilder(true)
                .setCurrentProject(moduleDir.toString()))
                .getWorkspace();

        assertNotNull(ws.getProject("org.acme", "acme-runtimes"));
        assertNotNull(ws.getProject("org.acme", "acme-parent"));
        assertNotNull(ws.getProject("org.acme", "acme-build-no-bom-parent"));
        assertNotNull(ws.getProject("org.acme", "acme-build-parent"));
        assertNotNull(ws.getProject("org.acme", "acme-dependencies-bom"));
        assertEquals(5, ws.getProjects().size());
    }

    @Test
    public void nonParentAggregator() throws Exception {
        final Path moduleDir = getModuleDir("non-parent-aggregator/service-extension/deployment");
        final Path aggregatorDir = getModuleDir("non-parent-aggregator/aggregator");

        final String topLevelBaseDirProp = "maven.top-level-basedir";
        final String originalBaseDir = System.getProperty(topLevelBaseDirProp);
        final LocalProject module1;
        try {
            System.setProperty(topLevelBaseDirProp, aggregatorDir.toString());
            module1 = new BootstrapMavenContext(BootstrapMavenContext.config()
                    .setEffectiveModelBuilder(true)
                    .setCurrentProject(moduleDir.toString()))
                    .getCurrentProject();
        } finally {
            if (originalBaseDir == null) {
                System.clearProperty(topLevelBaseDirProp);
            } else {
                System.setProperty(topLevelBaseDirProp, originalBaseDir);
            }
        }
        final LocalWorkspace ws = module1.getWorkspace();

        assertNotNull(ws.getProject("org.example", "service-extension-deployment"));
        assertNotNull(ws.getProject("org.example", "service-extension"));
        assertNotNull(ws.getProject("org.example", "service-extension-parent"));
        assertNotNull(ws.getProject("org.example", "model-extension-deployment"));
        assertNotNull(ws.getProject("org.example", "model-extension"));
        assertNotNull(ws.getProject("org.example", "model-extension-parent"));
        assertNotNull(ws.getProject("org.example", "aggregator"));
        assertEquals(7, ws.getProjects().size());
    }

    @Test
    public void loadEffectiveModelBuilderModulesInProfiles() throws Exception {
        final Path moduleDir = getModuleDir("modules-in-profiles/integration-tests/rest-tests");

        final LocalProject module1 = new BootstrapMavenContext(BootstrapMavenContext.config()
                .setEffectiveModelBuilder(true)
                .setCurrentProject(moduleDir.toString()))
                .getCurrentProject();
        final LocalWorkspace ws = module1.getWorkspace();

        assertNotNull(ws.getProject("org.acme", "quarkus-quickstart-multimodule-parent"));
        assertNotNull(ws.getProject("org.acme", "quarkus-quickstart-multimodule-html"));
        assertNotNull(ws.getProject("org.acme", "quarkus-quickstart-multimodule-main"));
        assertNotNull(ws.getProject("org.acme", "quarkus-quickstart-multimodule-rest"));
        assertNotNull(ws.getProject("org.acme", "acme-integration-tests"));
        assertNotNull(ws.getProject("org.acme", "acme-rest-tests"));
        assertNotNull(ws.getProject("org.acme", "other"));
        assertEquals(7, ws.getProjects().size());
    }

    @Test
    public void loadModulesInProfiles() throws Exception {
        final Path moduleDir = getModuleDir("modules-in-profiles/integration-tests/rest-tests");

        final LocalProject module1 = new BootstrapMavenContext(BootstrapMavenContext.config()
                .setCurrentProject(moduleDir.toString()))
                .getCurrentProject();
        final LocalWorkspace ws = module1.getWorkspace();

        assertNotNull(ws.getProject("org.acme", "quarkus-quickstart-multimodule-parent"));
        assertNotNull(ws.getProject("org.acme", "quarkus-quickstart-multimodule-html"));
        assertNotNull(ws.getProject("org.acme", "quarkus-quickstart-multimodule-main"));
        assertNotNull(ws.getProject("org.acme", "quarkus-quickstart-multimodule-rest"));
        assertNotNull(ws.getProject("org.acme", "acme-integration-tests"));
        assertNotNull(ws.getProject("org.acme", "acme-rest-tests"));
        assertNotNull(ws.getProject("org.acme", "other"));
        assertEquals(7, ws.getProjects().size());
    }

    @Test
    public void loadOverlappingWorkspaceLayout() throws Exception {
        final Path moduleDir = getModuleDir("overlapping-workspace-layout/root/root/module1");

        final LocalProject module1 = new BootstrapMavenContext(BootstrapMavenContext.config()
                .setCurrentProject(moduleDir.toString()))
                .getCurrentProject();
        final LocalWorkspace ws = module1.getWorkspace();

        final LocalProject wsModule1 = ws.getProject("org.acme", "module1");
        assertNotNull(wsModule1);
        assertEquals(module1.getDir().toAbsolutePath(), wsModule1.getDir().toAbsolutePath());
        assertTrue(module1 == wsModule1);
        assertNotNull(ws.getProject("org.acme", "root"));
        assertEquals(2, ws.getProjects().size());
    }

    @Test
    public void loadWorkspaceWithDirBreaks() throws Exception {
        final Path rootProjectDir = getModuleDir("workspace-with-dir-breaks/root");
        assertTrue(Files.exists(rootProjectDir));
        final Path nestedProjectDir = rootProjectDir.resolve("module1/break/nested-project/module1");
        assertTrue(Files.exists(nestedProjectDir));

        final LocalWorkspace ws = new BootstrapMavenContext(BootstrapMavenContext.config()
                .setRootProjectDir(rootProjectDir)
                .setCurrentProject(nestedProjectDir.toString()))
                .getWorkspace();

        assertNotNull(ws.getProject("org.acme", "nested-project-module1"));
        assertNotNull(ws.getProject("org.acme", "nested-project-parent"));
        assertNotNull(ws.getProject("org.acme", "root-module1"));
        assertNotNull(ws.getProject("org.acme", "root"));
        assertEquals(4, ws.getProjects().size());
    }

    @Test
    public void loadWorkspaceWithMissingModule() throws Exception {
        final Path rootProjectDir = getModuleDir("workspace-missing-module/root");
        assertTrue(Files.exists(rootProjectDir));
        final Path nestedProjectDir = rootProjectDir.resolve("module1");
        assertTrue(Files.exists(nestedProjectDir));

        final LocalWorkspace ws = new BootstrapMavenContext(BootstrapMavenContext.config()
                .setCurrentProject(nestedProjectDir.toString()))
                .getWorkspace();

        assertNotNull(ws.getProject("org.acme", "module1"));
        assertNotNull(ws.getProject("org.acme", "root"));
        assertEquals(2, ws.getProjects().size());
    }

    @Test
    public void loadWorkspaceRootWithNoModules() throws Exception {
        final Path rootProjectDir = getModuleDir("workspace-root-no-module/root");
        assertTrue(Files.exists(rootProjectDir));
        final Path nestedProjectDir = rootProjectDir.resolve("module1/module2");
        assertTrue(Files.exists(nestedProjectDir));

        final LocalWorkspace ws = new BootstrapMavenContext(BootstrapMavenContext.config()
                .setCurrentProject(nestedProjectDir.toString()))
                .getWorkspace();

        assertNotNull(ws.getProject("org.acme", "module3"));
        assertNotNull(ws.getProject("org.acme", "module2"));
        assertNotNull(ws.getProject("org.acme", "module1"));
        assertNotNull(ws.getProject("org.acme", "root"));
        assertEquals(4, ws.getProjects().size());
    }

    @Test
    public void loadWorkspaceFromRootDirWithParentInChildDir() throws Exception {
        final Path projectDir = getModuleDir("workspace-parent-is-not-root-dir");
        assertTrue(Files.exists(projectDir));
        final LocalProject project = LocalProject.loadWorkspace(projectDir);

        assertEquals("acme", project.getArtifactId());
        assertWorkspaceWithParentInChildDir(project);
        assertParents(project, "acme-parent", "acme-dependencies");
    }

    @Test
    public void loadWorkspaceFromRootDirWithParentInChildDirEffectiveModel() throws Exception {
        final Path projectDir = getModuleDir("workspace-parent-is-not-root-dir");
        assertTrue(Files.exists(projectDir));

        final LocalProject module1 = new BootstrapMavenContext(BootstrapMavenContext.config()
                .setEffectiveModelBuilder(true)
                .setCurrentProject(projectDir.toString()))
                .getCurrentProject();
        final LocalWorkspace ws = module1.getWorkspace();
        final LocalProject project = ws.getProject("org.acme", "acme");
        assertNotNull(project);

        assertEquals("acme", project.getArtifactId());
        assertWorkspaceWithParentInChildDir(project);
        assertParents(project, "acme-parent", "acme-dependencies");
    }

    @Test
    public void loadWorkspaceFromModuleDirWithParentInChildDir() throws Exception {
        final Path projectDir = getModuleDir("workspace-parent-is-not-root-dir/acme-application");
        assertTrue(Files.exists(projectDir));
        final LocalProject project = LocalProject.loadWorkspace(projectDir);

        assertEquals("acme-application", project.getArtifactId());
        assertWorkspaceWithParentInChildDir(project);

        assertParents(project, "acme-parent", "acme-dependencies");
    }

    @Test
    public void loadWorkspaceFromModuleDirWithParentInSiblingDir() throws Exception {
        final Path projectDir = getModuleDir("workspace-parent-is-not-root-dir/acme-backend/acme-backend-lib");
        assertTrue(Files.exists(projectDir));
        final LocalProject project = LocalProject.loadWorkspace(projectDir);

        assertEquals("acme-backend-lib", project.getArtifactId());
        assertWorkspaceWithParentInChildDir(project);

        assertParents(project, "acme-backend", "acme-backend-parent", "acme-parent", "acme-dependencies");
    }

    private void assertWorkspaceWithParentInChildDir(final LocalProject project) {
        final LocalWorkspace workspace = project.getWorkspace();
        assertNotNull(workspace.getProject("org.acme", "acme"));
        assertNotNull(workspace.getProject("org.acme", "acme-parent"));
        assertNotNull(workspace.getProject("org.acme", "acme-dependencies"));
        assertNotNull(workspace.getProject("org.acme", "acme-backend"));
        assertNotNull(workspace.getProject("org.acme", "acme-backend-parent"));
        assertNotNull(workspace.getProject("org.acme", "acme-backend-lib"));
        assertNotNull(workspace.getProject("org.acme", "acme-backend-rest-api"));
        assertNotNull(workspace.getProject("org.acme", "acme-application"));
        assertEquals(8, workspace.getProjects().size());
    }

    @Test
    public void loadWorkspaceWithAlternatePomDefaultPom() throws Exception {
        final Path projectDir = getModuleDir("workspace-alternate-pom/root/module1");
        assertTrue(Files.exists(projectDir));
        final LocalProject project = LocalProject.loadWorkspace(projectDir);
        assertParents(project, "root");

        assertEquals("root-module1", project.getArtifactId());
        final LocalWorkspace workspace = project.getWorkspace();
        LocalProject rootProject = workspace.getProject("org.acme", "root");
        assertNotNull(rootProject);
        assertNull(rootProject.getLocalParent());
        LocalProject module1 = workspace.getProject("org.acme", "root-module1");
        assertNotNull(module1);
        assertParents(module1, "root");
        LocalProject bom = workspace.getProject("org.acme", "acme-bom");
        assertNotNull(bom);
        assertParents(bom, "root");
        LocalProject parent = workspace.getProject("org.acme", "acme-parent");
        assertNotNull(parent);
        assertParents(parent, "root");
        assertNull(workspace.getProject("org.acme", "root-module2"));
        LocalProject submodule = workspace.getProject("org.acme", "root-submodule");
        assertNotNull(submodule);
        assertParents(submodule, "root-module1", "root");
        assertEquals(5, workspace.getProjects().size());
    }

    @Test
    public void loadWorkspaceWithAlternatePom() throws Exception {
        final Path projectDir = getModuleDir("workspace-alternate-pom/root/module1/pom2.xml");
        assertTrue(Files.exists(projectDir));
        final LocalProject project = LocalProject.loadWorkspace(projectDir);
        assertParents(project, "root");

        assertEquals("root-module1", project.getArtifactId());
        final LocalWorkspace workspace = project.getWorkspace();
        LocalProject root = workspace.getProject("org.acme", "root");
        assertNotNull(root);
        assertNull(root.getLocalParent());
        LocalProject module1 = workspace.getProject("org.acme", "root-module1");
        assertNotNull(module1);
        assertParents(module1, "root");
        LocalProject module2 = workspace.getProject("org.acme", "root-module2");
        assertNotNull(module2);
        assertParents(module2, "acme-parent", "root");
        LocalProject bom = workspace.getProject("org.acme", "acme-bom");
        assertNotNull(bom);
        assertParents(bom, "root");
        LocalProject parent = workspace.getProject("org.acme", "acme-parent");
        assertNotNull(parent);
        assertParents(parent, "root");
        assertNull(workspace.getProject("org.acme", "root-submodule"));
        assertEquals(5, workspace.getProjects().size());
    }

    @Test
    public void loadIndependentProjectInTheWorkspaceTree() throws Exception {
        final LocalProject project = LocalProject
                .loadWorkspace(workDir.resolve("root").resolve("independent").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertNotNull(project.getWorkspace());
        assertEquals(MvnProjectBuilder.DEFAULT_GROUP_ID, project.getGroupId());
        assertEquals("independent", project.getArtifactId());
        assertEquals(MvnProjectBuilder.DEFAULT_VERSION, project.getVersion());
        final Map<ArtifactKey, LocalProject> projects = project.getWorkspace().getProjects();
        assertEquals(6, projects.size());
        assertTrue(projects.containsKey(ArtifactKey.ga(MvnProjectBuilder.DEFAULT_GROUP_ID, "independent")));

        assertNull(project.getLocalParent());
    }

    @Test
    public void loadModuleProjectWithoutParent() throws Exception {
        final LocalProject project = LocalProject
                .load(workDir.resolve("root").resolve("module1").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertNull(project.getWorkspace());
        assertEquals(MvnProjectBuilder.DEFAULT_GROUP_ID, project.getGroupId());
        assertEquals("root-no-parent-module", project.getArtifactId());
        assertEquals(MvnProjectBuilder.DEFAULT_VERSION, project.getVersion());
        assertNull(project.getLocalParent());
    }

    @Test
    public void loadWorkspaceForModuleWithoutParent() throws Exception {
        final LocalProject project = LocalProject
                .loadWorkspace(workDir.resolve("root").resolve("module1").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertEquals(MvnProjectBuilder.DEFAULT_GROUP_ID, project.getGroupId());
        assertEquals("root-no-parent-module", project.getArtifactId());
        assertEquals(MvnProjectBuilder.DEFAULT_VERSION, project.getVersion());
        assertNotNull(project.getWorkspace());
        final Map<ArtifactKey, LocalProject> projects = project.getWorkspace().getProjects();
        assertEquals(5, projects.size());
        assertTrue(projects.containsKey(ArtifactKey.ga(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-no-parent-module")));

        assertParents(project);
    }

    @Test
    public void loadModuleProjectWithParent() throws Exception {
        final LocalProject project = LocalProject
                .load(workDir.resolve("root").resolve("module2").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertNull(project.getWorkspace());
        assertEquals(MvnProjectBuilder.DEFAULT_GROUP_ID, project.getGroupId());
        assertEquals("root-module-with-parent", project.getArtifactId());
        assertEquals(MvnProjectBuilder.DEFAULT_VERSION, project.getVersion());

        assertParents(project);
    }

    @Test
    public void loadWorkspaceForModuleWithParent() throws Exception {
        final LocalProject project = LocalProject
                .loadWorkspace(workDir.resolve("root").resolve("module2").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertNotNull(project.getWorkspace());
        assertEquals(MvnProjectBuilder.DEFAULT_GROUP_ID, project.getGroupId());
        assertEquals("root-module-with-parent", project.getArtifactId());
        assertEquals(MvnProjectBuilder.DEFAULT_VERSION, project.getVersion());

        assertCompleteWorkspace(project);
        assertParents(project, "root");
    }

    @Test
    public void loadWorkspaceForModuleWithNotDirectParentPath() throws Exception {
        final LocalProject project = LocalProject.loadWorkspace(
                workDir.resolve("root").resolve("other").resolve("module3").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertNotNull(project.getWorkspace());
        assertEquals(MvnProjectBuilder.DEFAULT_GROUP_ID, project.getGroupId());
        assertEquals("root-module-not-direct-child", project.getArtifactId());
        assertEquals(MvnProjectBuilder.DEFAULT_VERSION, project.getVersion());

        assertCompleteWorkspace(project);
        assertParents(project, "root");
    }

    @Test
    public void loadNonModuleChildProject() throws Exception {
        final LocalProject project = LocalProject
                .loadWorkspace(IoUtils
                        .mkdirs(workDir.resolve("root").resolve("non-module-child").resolve("target").resolve("classes")));
        assertNotNull(project);
        assertNotNull(project.getWorkspace());
        assertEquals("non-module-child", project.getArtifactId());
        final Map<ArtifactKey, LocalProject> projects = project.getWorkspace().getProjects();
        assertTrue(projects.containsKey(ArtifactKey.ga(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-no-parent-module")));
        assertTrue(projects.containsKey(ArtifactKey.ga(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-module-with-parent")));
        assertTrue(
                projects.containsKey(ArtifactKey.ga(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-module-not-direct-child")));
        assertTrue(projects.containsKey(ArtifactKey.ga(MvnProjectBuilder.DEFAULT_GROUP_ID, "root")));
        assertTrue(projects.containsKey(ArtifactKey.ga(MvnProjectBuilder.DEFAULT_GROUP_ID, "non-module-child")));
        assertTrue(projects.containsKey(ArtifactKey.ga(MvnProjectBuilder.DEFAULT_GROUP_ID, "another-child")));
        assertTrue(projects
                .containsKey(ArtifactKey.ga(MvnProjectBuilder.DEFAULT_GROUP_ID, "empty-parent-relative-path-module")));
        assertEquals(7, projects.size());

        assertParents(project, "root");
    }

    /**
     * Empty relativePath is a hack sometimes used to always resolve parent from repository and skip default "../" lookup
     */
    @Test
    public void loadWorkspaceForModuleWithEmptyRelativePathParent() throws Exception {
        final LocalProject project = LocalProject.loadWorkspace(
                workDir.resolve("root").resolve("module4").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertNotNull(project.getWorkspace());
        assertEquals(MvnProjectBuilder.DEFAULT_GROUP_ID, project.getGroupId());
        assertEquals("empty-parent-relative-path-module", project.getArtifactId());
        assertEquals(MvnProjectBuilder.DEFAULT_VERSION, project.getVersion());

        assertCompleteWorkspace(project);

        assertParents(project);
    }

    @Test
    public void testVersionRevisionPropertyEffectiveModel() throws Exception {
        final Path projectDir = getModuleDir("workspace-revision/root/module1");
        assertTrue(Files.exists(projectDir));

        final LocalProject module1 = new BootstrapMavenContext(BootstrapMavenContext.config()
                .setEffectiveModelBuilder(true)
                .setCurrentProject(projectDir.toString()))
                .getCurrentProject();
        final LocalWorkspace ws = module1.getWorkspace();
        var project = ws.getProject("org.acme", "root-module1");
        assertNotNull(project);
        assertEquals("1.2.3", project.getVersion());

        project = ws.getProject("org.acme", "root");
        assertNotNull(project);
        assertEquals("1.2.3", project.getVersion());
    }

    @Test
    public void testVersionRevisionProperty() throws Exception {
        testMavenCiFriendlyVersion("${revision}", "workspace-revision", "1.2.3", true);
    }

    @Test
    public void testVersionRevisionPropertyOverridenWithSystemProperty() throws Exception {
        final String expectedResolvedVersion = "build123";
        System.setProperty("revision", expectedResolvedVersion);

        testMavenCiFriendlyVersion("${revision}", "workspace-revision", expectedResolvedVersion, false);
    }

    @Test
    public void testVersionSha1Property() throws Exception {
        testMavenCiFriendlyVersion("${sha1}", "workspace-sha1", "1.2.3", true);
    }

    @Test
    public void testVersionSha1PropertyOverridenWithSystemProperty() throws Exception {
        final String expectedResolvedVersion = "build123";
        System.setProperty("sha1", expectedResolvedVersion);

        testMavenCiFriendlyVersion("${sha1}", "workspace-sha1", expectedResolvedVersion, false);
    }

    @Test
    public void testVersionChangelistProperty() throws Exception {
        testMavenCiFriendlyVersion("${changelist}", "workspace-changelist", "1.2.3", true);
    }

    @Test
    public void testVersionChangelistPropertyOverridenWithSystemProperty() throws Exception {
        final String expectedResolvedVersion = "build123";
        System.setProperty("changelist", expectedResolvedVersion);

        testMavenCiFriendlyVersion("${changelist}", "workspace-changelist", expectedResolvedVersion, false);
    }

    @Test
    public void testVersionMultipleProperties() throws Exception {
        testMavenCiFriendlyVersion("${revision}${sha1}${changelist}", "workspace-multiple", "1.2.3", true);
    }

    @Test
    public void testVersionMultiplePropertiesOverridenWithSystemProperty() throws Exception {
        final String expectedResolvedVersion = "build123";
        System.setProperty("revision", "build");
        System.setProperty("sha1", "12");
        System.setProperty("changelist", "3");

        testMavenCiFriendlyVersion("${revision}${sha1}${changelist}", "workspace-multiple", expectedResolvedVersion, false);
    }

    @Test
    public void testBuildDirs() throws Exception {
        final Path runnerDir = getModuleDir("build-directories/multimodule/runner");
        assertTrue(Files.exists(runnerDir));
        final LocalProject project = LocalProject.loadWorkspace(runnerDir);
        assertNotNull(project);
        assertEquals(runnerDir.resolve("custom-target"), project.getOutputDir());
        assertEquals(runnerDir.resolve("src/main/other"), project.getSourcesSourcesDir());
        assertEquals(runnerDir.resolve("custom-target").resolve("other-classes"), project.getClassesDir());
        assertEquals(runnerDir.resolve("custom-target").resolve("test-classes"), project.getTestClassesDir());

        final LocalProject parent = project.getLocalParent();
        final Path parentDir = parent.getDir();
        assertEquals(parentDir.resolve("custom-target"), parent.getOutputDir());
        assertEquals(parentDir.resolve("src/main/other"), parent.getSourcesSourcesDir());
        assertEquals(parentDir.resolve("custom-target").resolve("custom-classes"), parent.getClassesDir());
        assertEquals(parentDir.resolve("custom-target").resolve("test-classes"), parent.getTestClassesDir());
    }

    @Test
    public void warnOnFailingWorkspaceModules() throws Exception {
        final Path moduleDir = getModuleDir("invalid-module");

        final LocalWorkspace ws = new BootstrapMavenContext(BootstrapMavenContext.config()
                .setOffline(true)
                .setEffectiveModelBuilder(true)
                .setWarnOnFailedWorkspaceModules(true)
                .setCurrentProject(moduleDir.toString()))
                .getWorkspace();

        assertNotNull(ws.getProject("io.playground", "asm"));
        assertNotNull(ws.getProject("io.playground", "module"));
        assertEquals(2, ws.getProjects().size());
    }

    private void testMavenCiFriendlyVersion(String placeholder, String testResourceDirName, String expectedResolvedVersion,
            boolean resolvesFromWorkspace) throws Exception {
        final Path module1Dir = getModuleDir(testResourceDirName + "/root/module1");
        assertTrue(Files.exists(module1Dir));

        final LocalProject module1 = LocalProject.load(module1Dir);

        assertEquals(expectedResolvedVersion, module1.getAppArtifact().getVersion());
        assertEquals(expectedResolvedVersion, module1.getVersion());
        if (resolvesFromWorkspace) {
            assertNotNull(module1.getWorkspace()); // the property must have been resolved from the workspace
        } else {
            assertNull(module1.getWorkspace()); // the workspace was not necessary to resolve the property
        }

        final LocalWorkspace localWorkspace = resolvesFromWorkspace ? module1.getWorkspace()
                : LocalProject.loadWorkspace(module1Dir).getWorkspace();
        final File root = localWorkspace
                .findArtifact(new DefaultArtifact(module1.getGroupId(), "root", null, "pom", placeholder));
        assertNotNull(root);
        assertTrue(root.exists());
        final URL rootPomUrl = Thread.currentThread().getContextClassLoader()
                .getResource(testResourceDirName + "/root/pom.xml");
        assertEquals(new File(rootPomUrl.toURI()), root);

        final WorkspaceModule wsModule = module1.toWorkspaceModule();
        assertThat(wsModule.getModuleDir()).isEqualTo(module1Dir.toFile());
        assertThat(wsModule.getBuildDir()).isEqualTo(module1Dir.resolve("target").toFile());

        final Iterator<SourceDir> resourcesIterator = wsModule.getMainSources().getResourceDirs().iterator();
        assertThat(resourcesIterator).hasNext();
        SourceDir src = resourcesIterator.next();
        PathTree sourceTree = src.getSourceTree();
        assertThat(sourceTree).isNotNull();
        Collection<Path> roots = sourceTree.getRoots();
        assertThat(roots).hasSize(1);
        assertThat(roots.iterator().next()).isEqualTo(module1Dir.resolve("build"));
        assertThat(src.getOutputDir()).isEqualTo(module1Dir.resolve("target/classes/META-INF/resources"));
    }

    private void assertCompleteWorkspace(final LocalProject project) {
        final Map<ArtifactKey, LocalProject> projects = project.getWorkspace().getProjects();
        assertEquals(5, projects.size());
        assertTrue(projects.containsKey(ArtifactKey.ga(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-no-parent-module")));
        assertTrue(projects.containsKey(ArtifactKey.ga(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-module-with-parent")));
        assertTrue(
                projects.containsKey(ArtifactKey.ga(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-module-not-direct-child")));
        assertTrue(projects
                .containsKey(ArtifactKey.ga(MvnProjectBuilder.DEFAULT_GROUP_ID, "empty-parent-relative-path-module")));
        assertTrue(projects.containsKey(ArtifactKey.ga(MvnProjectBuilder.DEFAULT_GROUP_ID, "root")));
    }

    private static void assertParents(LocalProject project, String... parentArtifactId) {
        LocalProject parent = project.getLocalParent();
        int i = 0;
        while (parent != null) {
            if (i == parentArtifactId.length) {
                fail("Unexpected parent " + parent.getKey());
            }
            assertEquals(parentArtifactId[i++], parent.getArtifactId());
            parent = parent.getLocalParent();
        }
        if (i != parentArtifactId.length) {
            fail("Missing parent " + parentArtifactId[i]);
        }
    }
}
