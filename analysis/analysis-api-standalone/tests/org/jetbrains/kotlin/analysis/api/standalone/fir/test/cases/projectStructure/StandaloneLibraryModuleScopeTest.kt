/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.standalone.fir.test.cases.projectStructure

import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopeUtil
import org.jetbrains.kotlin.analysis.api.fir.utils.LibraryUtils
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaGlobalSearchScopeMerger
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScopeProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneWorkaroundApi
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.fir.projectStructure.StandaloneProjectFactory
import org.jetbrains.kotlin.analysis.api.standalone.fir.test.AbstractStandaloneTest
import org.jetbrains.kotlin.analysis.api.standalone.fir.test.cases.session.builder.compileCommonKlib
import org.jetbrains.kotlin.analysis.api.standalone.fir.test.cases.session.builder.compileToJar
import org.jetbrains.kotlin.analysis.api.standalone.projectStructure.StandaloneLibraryScopeConstructionMode
import org.jetbrains.kotlin.analysis.project.structure.builder.KaModuleContainerBuilder
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.library.components.KlibMetadataConstants.KLIB_METADATA_FILE_EXTENSION
import org.jetbrains.kotlin.platform.CommonPlatforms
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.test.MockLibraryUtil
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText

@OptIn(StandaloneWorkaroundApi::class)
class StandaloneLibraryModuleScopeTest : AbstractStandaloneTest() {
    override val suiteName: Path
        get() = Paths.get("projectStructure", "libraryModuleScope")

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun testLibraryModuleScopeUsesParentTraversalByDefault() {
        val libraryJar = compileToJar(testDataPath("library"), tempDir)
        val otherLibraryJar = compileToJar(testDataPath("otherLibrary"), tempDir)

        lateinit var libraryModule: KaLibraryModule

        val session = buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                platform = JvmPlatforms.defaultJvmPlatform
                libraryModule = addLibraryModule(libraryJar, "library")
                addLibraryModule(otherLibraryJar, "otherLibrary")
            }
        }

        val files = collectLibraryFiles(libraryJar, otherLibraryJar, session)

        assertLibraryScope(libraryModule, "Parent-traversal library search scope", files)
    }

    @Test
    fun testLibraryModuleScopeRespectsProviderDefaultAndModuleOverride() {
        val libraryJar = compileToJar(testDataPath("library"), tempDir)
        val otherLibraryJar = compileToJar(testDataPath("otherLibrary"), tempDir)

        lateinit var inheritedModule: KaLibraryModule
        lateinit var parentTraversalModule: KaLibraryModule
        lateinit var trieModule: KaLibraryModule
        lateinit var enumerationModule: KaLibraryModule

        val session = buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                platform = JvmPlatforms.defaultJvmPlatform

                // A provider-wide default that differs from the global default ([StandaloneLibraryScopeConstructionMode.ParentTraversal]).
                libraryScopeConstructionMode = StandaloneLibraryScopeConstructionMode.Trie

                // Inherits the provider-wide default.
                inheritedModule = addLibraryModule(libraryJar, "inherited")

                // The following modules each override the provider-wide default with a specific mode.
                parentTraversalModule =
                    addLibraryModule(libraryJar, "parentTraversalOverride", StandaloneLibraryScopeConstructionMode.ParentTraversal)
                trieModule = addLibraryModule(libraryJar, "trieOverride", StandaloneLibraryScopeConstructionMode.Trie)
                enumerationModule = addLibraryModule(libraryJar, "enumerationOverride", StandaloneLibraryScopeConstructionMode.Enumeration)

                addLibraryModule(otherLibraryJar, "otherLibrary")
            }
        }

        val files = collectLibraryFiles(libraryJar, otherLibraryJar, session)

        assertLibraryScope(inheritedModule, "Trie-based library search scope", files)
        assertLibraryScope(parentTraversalModule, "Parent-traversal library search scope", files)
        assertLibraryScope(trieModule, "Trie-based library search scope", files)
        assertLibraryScope(enumerationModule, "Enumeration-based library search scope", files)
    }

    @Test
    fun testUnpackedKlibLibraryModuleScope() {
        // An unpacked KLIB in a directory whose name ends with `klib`, like the name of a KLIB archive: `library.klib`.
        val unpackedKlib = compileCommonKlib(testDataPath("library"), tempDir)

        lateinit var libraryModule: KaLibraryModule

        buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                platform = CommonPlatforms.defaultCommonPlatform
                libraryModule = addLibraryModule(unpackedKlib, "library")
            }
        }

        val klibRoot = getLocalVirtualFile(unpackedKlib)
        val klibFiles = LibraryUtils.getAllVirtualFilesFromRoot(klibRoot, includeRoot = true)

        Assertions.assertTrue(klibRoot.isDirectory, "The KLIB should be unpacked.")
        Assertions.assertTrue(
            klibFiles.any { it.extension == KLIB_METADATA_FILE_EXTENSION },
            "The unpacked KLIB should contain at least one metadata file.",
        )

        val nonContainedFiles = listOf(klibRoot.parent, getLocalVirtualFile(testDataPath("library").resolve("library.kt")))
        assertLibraryScope(libraryModule, "Parent-traversal library search scope", LibraryFiles(klibFiles, nonContainedFiles))
    }

    @Test
    fun testEmptyLibraryModuleScopeReturnsEmptyScope() {
        val libraryJar = compileToJar(testDataPath("library"), tempDir)

        lateinit var emptyModule: KaLibraryModule
        lateinit var missingRootModule: KaLibraryModule

        val session = buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                platform = JvmPlatforms.defaultJvmPlatform
                emptyModule = addModule(
                    buildKtLibraryModule {
                        platform = JvmPlatforms.defaultJvmPlatform
                        libraryName = "empty"
                    }
                )
                missingRootModule = addModule(
                    buildKtLibraryModule {
                        addBinaryRoot(tempDir.resolve("nonexistentClassesDir"))
                        platform = JvmPlatforms.defaultJvmPlatform
                        libraryName = "missing"
                    }
                )
            }
        }

        Assertions.assertSame(GlobalSearchScope.EMPTY_SCOPE, emptyModule.baseContentScope)
        Assertions.assertSame(GlobalSearchScope.EMPTY_SCOPE, emptyModule.contentScope)
        Assertions.assertSame(GlobalSearchScope.EMPTY_SCOPE, missingRootModule.baseContentScope)

        val jarRoot = getJarRootVirtualFile(libraryJar, session)
        val localSource = getLocalVirtualFile(testDataPath("library").resolve("library.kt"))
        Assertions.assertFalse(emptyModule.baseContentScope.contains(jarRoot))
        Assertions.assertFalse(emptyModule.baseContentScope.contains(localSource))
    }

    @Test
    fun testParentTraversalProtocolFastRejectionAndArchiveRootWalk() {
        val jar1 = compileToJar(testDataPath("library"), tempDir)
        val jar2 = compileToJar(testDataPath("otherLibrary"), tempDir)
        val jar3 = compileInlineSourceToJar("third", "package third\nclass Third")
        val session = createMinimalSession()

        val jar1Root = getJarRootVirtualFile(jar1, session)
        val jar2Root = getJarRootVirtualFile(jar2, session)
        val jar3Root = getJarRootVirtualFile(jar3, session)

        // Single-JAR scope (also exercises duplicate-root deduplication back to a single-root scope).
        val singleJarScope = StandaloneProjectFactory.createLibraryModuleSearchScope(
            binaryRoots = listOf(jar1, jar1),
            binaryVirtualFiles = listOf(jar1Root),
            mode = StandaloneLibraryScopeConstructionMode.ParentTraversal,
            environment = session.coreApplicationEnvironment,
            project = session.project,
        )
        Assertions.assertEquals("Parent-traversal library search scope over [$jar1Root]", singleJarScope.toString())

        // Mismatched VFS protocols reject immediately without touching getParent(), getPath(), or hashCode().
        val localProbe = CountingVirtualFile("/src/Foo.kt", TestProtocolFileSystem(StandardFileSystems.FILE_PROTOCOL))
        val jrtProbe = CountingVirtualFile("/jdk!/modules/java.base/java/lang/Object.class", TestProtocolFileSystem(StandardFileSystems.JRT_PROTOCOL))
        val customProbe = CountingVirtualFile("/mem/Foo.class", TestProtocolFileSystem("custom"))
        for (probe in listOf(localProbe, jrtProbe, customProbe)) {
            Assertions.assertFalse(singleJarScope.contains(probe))
            Assertions.assertEquals(0, probe.parentCalls.get())
            Assertions.assertEquals(0, probe.pathCalls.get())
            Assertions.assertEquals(0, probe.hashCodeCalls.get())
        }

        // Intermediate JAR directories walk directly to the archive root without hashing intermediate entries.
        val jarFs = TestProtocolFileSystem(StandardFileSystems.JAR_PROTOCOL)
        val intermediateDir = CountingVirtualFile("${jar1Root.path}pkg/sub", jarFs, parentFile = jar1Root, directory = true)
        val leafInJar1 = CountingVirtualFile("${jar1Root.path}pkg/sub/A.class", jarFs, parentFile = intermediateDir)
        Assertions.assertTrue(singleJarScope.contains(leafInJar1))
        Assertions.assertEquals(0, leafInJar1.hashCodeCalls.get())
        Assertions.assertEquals(0, intermediateDir.hashCodeCalls.get())

        // Multi-JAR archive scope likewise walks to the archive root without intermediate hashing.
        val multiJarScope = StandaloneProjectFactory.createLibraryModuleSearchScope(
            binaryRoots = listOf(jar1, jar2),
            binaryVirtualFiles = emptyList(),
            mode = StandaloneLibraryScopeConstructionMode.ParentTraversal,
            environment = session.coreApplicationEnvironment,
            project = session.project,
        )
        for (file in LibraryUtils.getAllVirtualFilesFromRoot(jar1Root, includeRoot = true)) {
            Assertions.assertTrue(multiJarScope.contains(file))
        }
        for (file in LibraryUtils.getAllVirtualFilesFromRoot(jar2Root, includeRoot = true)) {
            Assertions.assertTrue(multiJarScope.contains(file))
        }
        for (file in LibraryUtils.getAllVirtualFilesFromRoot(jar3Root, includeRoot = true)) {
            Assertions.assertFalse(multiJarScope.contains(file))
        }

        val leafInJar2 = CountingVirtualFile("${jar2Root.path}other/B.class", jarFs, parentFile = CountingVirtualFile("${jar2Root.path}other", jarFs, parentFile = jar2Root, directory = true))
        Assertions.assertTrue(multiJarScope.contains(leafInJar2))
        Assertions.assertEquals(0, leafInJar2.hashCodeCalls.get())
        Assertions.assertFalse(multiJarScope.contains(localProbe))
        Assertions.assertEquals(0, localProbe.parentCalls.get())
    }

    @Test
    fun testNestedJarDirectoryRoots() {
        val packagedJar = compileInlineSourceToJar(
            "nested",
            "package alpha.sub\nclass InSub",
            "package beta\nclass InBeta",
        )
        val wholeJar = compileToJar(testDataPath("otherLibrary"), tempDir)
        val session = createMinimalSession()

        val packagedJarRoot = getJarRootVirtualFile(packagedJar, session)
        val wholeJarRoot = getJarRootVirtualFile(wholeJar, session)
        val alphaSubDir = packagedJarRoot.findFileByRelativePath("alpha/sub")
            ?: error("Expected `alpha/sub` directory inside `$packagedJarRoot`.")
        val inSubClass = alphaSubDir.findChild("InSub.class")
            ?: error("Expected `InSub.class` inside `$alphaSubDir`.")
        val alphaDir = packagedJarRoot.findChild("alpha")
            ?: error("Expected `alpha` directory inside `$packagedJarRoot`.")
        val inBetaClass = packagedJarRoot.findFileByRelativePath("beta/InBeta.class")
            ?: error("Expected `beta/InBeta.class` inside `$packagedJarRoot`.")

        // Single nested JAR root.
        val singleNestedScope = StandaloneProjectFactory.createLibraryModuleSearchScope(
            binaryRoots = emptyList(),
            binaryVirtualFiles = listOf(alphaSubDir),
            mode = StandaloneLibraryScopeConstructionMode.ParentTraversal,
            environment = session.coreApplicationEnvironment,
            project = session.project,
        )
        Assertions.assertTrue(singleNestedScope.contains(alphaSubDir))
        Assertions.assertTrue(singleNestedScope.contains(inSubClass))
        Assertions.assertFalse(singleNestedScope.contains(alphaDir))
        Assertions.assertFalse(singleNestedScope.contains(packagedJarRoot))
        Assertions.assertFalse(singleNestedScope.contains(inBetaClass))
        Assertions.assertFalse(singleNestedScope.contains(wholeJarRoot))

        // Composite JAR scope combining a whole JAR archive root and a nested JAR directory root.
        val mixedJarScope = StandaloneProjectFactory.createLibraryModuleSearchScope(
            binaryRoots = listOf(wholeJar),
            binaryVirtualFiles = listOf(alphaSubDir),
            mode = StandaloneLibraryScopeConstructionMode.ParentTraversal,
            environment = session.coreApplicationEnvironment,
            project = session.project,
        )
        Assertions.assertTrue(mixedJarScope.contains(alphaSubDir))
        Assertions.assertTrue(mixedJarScope.contains(inSubClass))
        Assertions.assertFalse(mixedJarScope.contains(packagedJarRoot))
        Assertions.assertFalse(mixedJarScope.contains(inBetaClass))
        for (file in LibraryUtils.getAllVirtualFilesFromRoot(wholeJarRoot, includeRoot = true)) {
            Assertions.assertTrue(mixedJarScope.contains(file))
        }

        // Composite scope with a single nested JAR root and a local directory root uses direct equality without hashing JAR entries.
        val localClassesDir = compileInlineSourceToDirectory("nestedCompositeDir", "package localpkg\nclass LocalClass")
        val singleNestedPlusDirScope = StandaloneProjectFactory.createLibraryModuleSearchScope(
            binaryRoots = listOf(localClassesDir),
            binaryVirtualFiles = listOf(alphaSubDir),
            mode = StandaloneLibraryScopeConstructionMode.ParentTraversal,
            environment = session.coreApplicationEnvironment,
            project = session.project,
        )
        Assertions.assertTrue(singleNestedPlusDirScope.contains(inSubClass))
        Assertions.assertTrue(singleNestedPlusDirScope.contains(getLocalVirtualFile(localClassesDir)))
        val jarFs = TestProtocolFileSystem(StandardFileSystems.JAR_PROTOCOL)
        val unrelatedNestedJarLeaf = CountingVirtualFile(
            "${wholeJarRoot.path}unrelated/Miss.class",
            jarFs,
            parentFile = CountingVirtualFile("${wholeJarRoot.path}unrelated", jarFs, parentFile = wholeJarRoot, directory = true),
        )
        Assertions.assertFalse(singleNestedPlusDirScope.contains(unrelatedNestedJarLeaf))
        Assertions.assertEquals(0, unrelatedNestedJarLeaf.hashCodeCalls.get())

        // Multiple nested JAR roots with no top-level archive roots.
        val betaDir = packagedJarRoot.findChild("beta") ?: error("Expected `beta` directory.")
        val multipleNestedOnlyScope = StandaloneProjectFactory.createLibraryModuleSearchScope(
            binaryRoots = emptyList(),
            binaryVirtualFiles = listOf(alphaSubDir, betaDir),
            mode = StandaloneLibraryScopeConstructionMode.ParentTraversal,
            environment = session.coreApplicationEnvironment,
            project = session.project,
        )
        Assertions.assertTrue(multipleNestedOnlyScope.contains(inSubClass))
        Assertions.assertTrue(multipleNestedOnlyScope.contains(inBetaClass))
        Assertions.assertFalse(multipleNestedOnlyScope.contains(alphaDir))
        Assertions.assertFalse(multipleNestedOnlyScope.contains(packagedJarRoot))
        Assertions.assertFalse(multipleNestedOnlyScope.contains(wholeJarRoot))
    }

    @Test
    fun testJdkJrtModuleScopeAndNonUniformJrtDepths() {
        val jdkHome = Paths.get(System.getProperty("java.home"))
        lateinit var sdkModule: KaLibraryModule

        val session = buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                platform = JvmPlatforms.defaultJvmPlatform
                sdkModule = addModule(
                    buildKtSdkModule {
                        addBinaryRootsFromJdkHome(jdkHome, isJre = false)
                        platform = JvmPlatforms.defaultJvmPlatform
                        libraryName = "JDK"
                    }
                )
            }
        }

        val jrtRoots = StandaloneProjectFactory.getVirtualFilesForLibraryRoots(sdkModule.binaryRoots, session.coreApplicationEnvironment)
        Assertions.assertTrue(jrtRoots.isNotEmpty(), "Expected modular JDK JRT roots for `$jdkHome`.")
        Assertions.assertTrue(jrtRoots.all { it.fileSystem.protocol == StandardFileSystems.JRT_PROTOCOL })

        val javaBaseRoot = jrtRoots.first { it.name == "java.base" }
        val modulesDir = javaBaseRoot.parent ?: error("Expected `modules` parent for `java.base`.")
        val imageRoot = modulesDir.parent ?: error("Expected image root parent for `modules`.")
        val stringClass = javaBaseRoot.findFileByRelativePath("java/lang/String.class")
            ?: error("Expected `java/lang/String.class` in `java.base`.")

        val sdkScope = sdkModule.baseContentScope
        Assertions.assertTrue(sdkScope.contains(javaBaseRoot))
        Assertions.assertTrue(sdkScope.contains(stringClass))
        // Ancestors shallower than depth-2 module roots must not be matched.
        Assertions.assertFalse(sdkScope.contains(modulesDir))
        Assertions.assertFalse(sdkScope.contains(imageRoot))

        // Single JRT module scope must contain `java.base` classes while excluding other JDK modules at the same depth.
        val javaBaseOnlyScope = StandaloneProjectFactory.createLibraryModuleSearchScope(
            binaryRoots = emptyList(),
            binaryVirtualFiles = listOf(javaBaseRoot),
            mode = StandaloneLibraryScopeConstructionMode.ParentTraversal,
            environment = session.coreApplicationEnvironment,
            project = session.project,
        )
        Assertions.assertTrue(javaBaseOnlyScope.contains(javaBaseRoot))
        Assertions.assertTrue(javaBaseOnlyScope.contains(stringClass))
        Assertions.assertFalse(javaBaseOnlyScope.contains(modulesDir))
        Assertions.assertFalse(javaBaseOnlyScope.contains(imageRoot))
        val otherJrtRoot = jrtRoots.firstOrNull { it != javaBaseRoot }
        if (otherJrtRoot != null) {
            Assertions.assertFalse(javaBaseOnlyScope.contains(otherJrtRoot))
        }

        // Uniform-depth JRT lookup avoids hashing intermediate package directories.
        val jrtFs = TestProtocolFileSystem(StandardFileSystems.JRT_PROTOCOL)
        val pkgJava = CountingVirtualFile("${javaBaseRoot.path}/java", jrtFs, parentFile = javaBaseRoot, directory = true)
        val pkgLang = CountingVirtualFile("${javaBaseRoot.path}/java/lang", jrtFs, parentFile = pkgJava, directory = true)
        val leafClass = CountingVirtualFile("${javaBaseRoot.path}/java/lang/Custom.class", jrtFs, parentFile = pkgLang)
        Assertions.assertTrue(sdkScope.contains(leafClass))
        Assertions.assertEquals(0, leafClass.hashCodeCalls.get())
        Assertions.assertEquals(0, pkgLang.hashCodeCalls.get())
        Assertions.assertEquals(0, pkgJava.hashCodeCalls.get())

        // Non-uniform JRT depths (depth-2 `java.base` + depth-4 `java/util` inside another module) fall back safely.
        val javaLoggingRoot = jrtRoots.firstOrNull { it.name == "java.logging" } ?: otherJrtRoot
        if (javaLoggingRoot != null) {
            val loggingSubPkg = javaLoggingRoot.findFileByRelativePath("java/util/logging")
            if (loggingSubPkg != null) {
                val nonUniformJrtScope = StandaloneProjectFactory.createLibraryModuleSearchScope(
                    binaryRoots = emptyList(),
                    binaryVirtualFiles = listOf(javaBaseRoot, loggingSubPkg),
                    mode = StandaloneLibraryScopeConstructionMode.ParentTraversal,
                    environment = session.coreApplicationEnvironment,
                    project = session.project,
                )
                Assertions.assertTrue(nonUniformJrtScope.contains(stringClass))
                Assertions.assertTrue(nonUniformJrtScope.contains(loggingSubPkg))
                Assertions.assertFalse(nonUniformJrtScope.contains(javaLoggingRoot))
                Assertions.assertFalse(nonUniformJrtScope.contains(modulesDir))
                Assertions.assertFalse(nonUniformJrtScope.contains(imageRoot))
            }
        }
    }

    @Test
    fun testMixedProtocolAndDistinctCustomProtocolScopes() {
        val jar1 = compileToJar(testDataPath("library"), tempDir)
        val unpackedClassesDir = compileInlineSourceToDirectory("dirLib", "package dirpkg\nclass DirClass")
        val otherDir = Files.createDirectories(tempDir.resolve("unrelatedDir"))
        val session = createMinimalSession()

        val jar1Root = getJarRootVirtualFile(jar1, session)
        val dirRoot = getLocalVirtualFile(unpackedClassesDir)
        val dirClass = dirRoot.findFileByRelativePath("dirpkg/DirClass.class")
            ?: error("Expected `dirpkg/DirClass.class` in `$dirRoot`.")
        val unrelatedLocalDir = getLocalVirtualFile(otherDir)

        val alphaFs = TestProtocolFileSystem("alpha")
        val betaFs = TestProtocolFileSystem("beta")
        val gammaFs = TestProtocolFileSystem("gamma")

        val alphaRoot = CountingVirtualFile("/shared/root", alphaFs, directory = true)
        val alphaChild = CountingVirtualFile("/shared/root/Item.bin", alphaFs, parentFile = alphaRoot)
        val betaSamePath = CountingVirtualFile("/shared/root/Item.bin", betaFs, parentFile = CountingVirtualFile("/shared/root", betaFs, directory = true))
        val betaRoot = CountingVirtualFile("/beta/lib", betaFs, directory = true)
        val betaChild = CountingVirtualFile("/beta/lib/Entry.bin", betaFs, parentFile = betaRoot)
        val gammaFile = CountingVirtualFile("/shared/root/Item.bin", gammaFs)

        val mixedScope = StandaloneProjectFactory.createLibraryModuleSearchScope(
            binaryRoots = listOf(jar1, unpackedClassesDir),
            binaryVirtualFiles = listOf(alphaRoot, betaRoot),
            mode = StandaloneLibraryScopeConstructionMode.ParentTraversal,
            environment = session.coreApplicationEnvironment,
            project = session.project,
        )

        Assertions.assertTrue(mixedScope.contains(jar1Root))
        Assertions.assertTrue(mixedScope.contains(dirRoot))
        Assertions.assertTrue(mixedScope.contains(dirClass))
        Assertions.assertFalse(mixedScope.contains(unrelatedLocalDir))

        Assertions.assertTrue(mixedScope.contains(alphaRoot))
        Assertions.assertTrue(mixedScope.contains(alphaChild))
        Assertions.assertFalse(mixedScope.contains(betaSamePath), "A root in `alpha://` must not match a same-path file in `beta://`.")
        Assertions.assertTrue(mixedScope.contains(betaChild))

        Assertions.assertFalse(mixedScope.contains(gammaFile))
        Assertions.assertEquals(0, gammaFile.parentCalls.get(), "Unregistered custom protocol should fast-reject without parent traversal.")
    }

    @Test
    fun testLibraryScopeMergingInResolutionScopeAndUniteWith() {
        val jar1 = compileToJar(testDataPath("library"), tempDir)
        val jar2 = compileToJar(testDataPath("otherLibrary"), tempDir)
        val excludedJar = compileInlineSourceToJar("excluded", "package excluded\nclass Excluded")
        val classesDir = compileInlineSourceToDirectory("dirDep", "package dirdep\nclass DirDep")
        val jdkHome = Paths.get(System.getProperty("java.home"))

        val mainSrcDir = Files.createDirectories(tempDir.resolve("mainSrc"))
        val mainKtPath = mainSrcDir.resolve("Main.kt")
        mainKtPath.writeText("package main\nfun main() {}")

        lateinit var lib1Module: KaLibraryModule
        lateinit var lib2Module: KaLibraryModule
        lateinit var dirLibModule: KaLibraryModule
        lateinit var sdkModule: KaLibraryModule
        lateinit var sourceModule: KaSourceModule

        val session = buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                platform = JvmPlatforms.defaultJvmPlatform

                sdkModule = addModule(
                    buildKtSdkModule {
                        addBinaryRootsFromJdkHome(jdkHome, isJre = false)
                        platform = JvmPlatforms.defaultJvmPlatform
                        libraryName = "JDK"
                    }
                )
                lib1Module = addLibraryModule(jar1, "lib1")
                lib2Module = addLibraryModule(jar2, "lib2")
                dirLibModule = addLibraryModule(classesDir, "dirLib")
                val emptyLibModule = addModule(
                    buildKtLibraryModule {
                        platform = JvmPlatforms.defaultJvmPlatform
                        libraryName = "emptyLib"
                    }
                )

                sourceModule = addModule(
                    buildKtSourceModule {
                        addSourceRoot(mainSrcDir)
                        addRegularDependency(sdkModule)
                        addRegularDependency(lib1Module)
                        addRegularDependency(lib2Module)
                        addRegularDependency(dirLibModule)
                        addRegularDependency(emptyLibModule)
                        platform = JvmPlatforms.defaultJvmPlatform
                        moduleName = "main"
                    }
                )
            }
        }

        val resolutionScope = KaResolutionScopeProvider.getInstance(session.project).getResolutionScope(sourceModule)
        val parentTraversalScopesInResolution = collectParentTraversalScopes(resolutionScope.underlyingSearchScope)
        Assertions.assertEquals(
            1,
            parentTraversalScopesInResolution.size,
            "Expected all ParentTraversal library/SDK scopes in resolutionScope to merge into 1 scope, got: $parentTraversalScopesInResolution",
        )

        val jar1Root = getJarRootVirtualFile(jar1, session)
        val jar2Root = getJarRootVirtualFile(jar2, session)
        val excludedJarRoot = getJarRootVirtualFile(excludedJar, session)
        val dirClass = getLocalVirtualFile(classesDir).findFileByRelativePath("dirdep/DirDep.class")
            ?: error("Expected `dirdep/DirDep.class`.")
        val jrtStringClass = StandaloneProjectFactory.getVirtualFilesForLibraryRoots(sdkModule.binaryRoots, session.coreApplicationEnvironment)
            .first { it.name == "java.base" }
            .findFileByRelativePath("java/lang/String.class")
            ?: error("Expected `java/lang/String.class`.")
        val mainVirtualFile = session.modulesWithFiles.getValue(sourceModule).single().virtualFile

        Assertions.assertTrue(resolutionScope.contains(mainVirtualFile))
        Assertions.assertTrue(resolutionScope.contains(jrtStringClass))
        Assertions.assertTrue(resolutionScope.contains(dirClass))
        for (file in LibraryUtils.getAllVirtualFilesFromRoot(jar1Root, includeRoot = false).filter { it.extension == "class" }) {
            Assertions.assertTrue(resolutionScope.contains(file))
        }
        for (file in LibraryUtils.getAllVirtualFilesFromRoot(jar2Root, includeRoot = false).filter { it.extension == "class" }) {
            Assertions.assertTrue(resolutionScope.contains(file))
        }
        for (file in LibraryUtils.getAllVirtualFilesFromRoot(excludedJarRoot, includeRoot = false)) {
            Assertions.assertFalse(resolutionScope.contains(file))
        }

        // Direct `uniteWith` also merges parent-traversal library scopes from the same project.
        val s1 = lib1Module.baseContentScope
        val s2 = lib2Module.baseContentScope
        Assertions.assertSame(s1, s1.uniteWith(s1))
        val directUnion = s1.uniteWith(s2)
        Assertions.assertEquals(1, GlobalSearchScopeUtil.flattenUnionScope(directUnion).size)
        Assertions.assertTrue(directUnion.toString().startsWith("Parent-traversal library search scope"))
        Assertions.assertTrue(directUnion.contains(jar1Root))
        Assertions.assertTrue(directUnion.contains(jar2Root))
        Assertions.assertFalse(directUnion.contains(excludedJarRoot))

        // Merging via `KaGlobalSearchScopeMerger` on raw base scopes also produces a single scope.
        val mergedBase = KaGlobalSearchScopeMerger.getInstance(session.project).union(
            listOf(s1, s2, dirLibModule.baseContentScope, sdkModule.baseContentScope)
        )
        Assertions.assertEquals(1, GlobalSearchScopeUtil.flattenUnionScope(mergedBase).size)
        Assertions.assertTrue(mergedBase.toString().startsWith("Parent-traversal library search scope"))

        // Scopes bound to a different Project must not have their project association overwritten by `uniteWith` or `KaGlobalSearchScopeMerger`.
        val otherSession = createMinimalSession()
        val foreignScope = StandaloneProjectFactory.createLibraryModuleSearchScope(
            binaryRoots = listOf(excludedJar),
            binaryVirtualFiles = emptyList(),
            mode = StandaloneLibraryScopeConstructionMode.ParentTraversal,
            environment = otherSession.coreApplicationEnvironment,
            project = otherSession.project,
        )
        val crossProjectDirectUnion = s1.uniteWith(foreignScope)
        val crossProjectDirectParts = GlobalSearchScopeUtil.flattenUnionScope(crossProjectDirectUnion)
        Assertions.assertEquals(2, crossProjectDirectParts.size)
        Assertions.assertTrue(crossProjectDirectParts.any { it === foreignScope && it.project === otherSession.project })

        val crossProjectMergerUnion = KaGlobalSearchScopeMerger.getInstance(session.project).union(listOf(s1, s2, foreignScope))
        val crossProjectMergerParts = GlobalSearchScopeUtil.flattenUnionScope(crossProjectMergerUnion)
        Assertions.assertEquals(2, crossProjectMergerParts.size)
        Assertions.assertTrue(crossProjectMergerParts.any { it === foreignScope && it.project === otherSession.project })
        Assertions.assertTrue(crossProjectMergerParts.any { it !== foreignScope && it.project === session.project && it.contains(jar1Root) && it.contains(jar2Root) })
    }

    private fun collectParentTraversalScopes(scope: GlobalSearchScope): List<GlobalSearchScope> {
        val result = mutableListOf<GlobalSearchScope>()
        fun visit(current: GlobalSearchScope) {
            val unionParts = GlobalSearchScopeUtil.flattenUnionScope(current)
            if (unionParts.size > 1) {
                unionParts.forEach(::visit)
                return
            }
            if (GlobalSearchScopeUtil.isIntersectionScope(current)) {
                GlobalSearchScopeUtil.flattenIntersectionScope(current).forEach(::visit)
                return
            }
            if (current.toString().startsWith("Parent-traversal library search scope")) {
                result.add(current)
            }
        }
        visit(scope)
        return result
    }

    private fun createMinimalSession(): StandaloneAnalysisAPISession =
        buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                platform = JvmPlatforms.defaultJvmPlatform
            }
        }

    private fun compileInlineSourceToJar(name: String, vararg sources: String): Path {
        val srcDir = Files.createDirectories(tempDir.resolve("${name}Src"))
        sources.forEachIndexed { index, content ->
            srcDir.resolve("File$index.kt").writeText(content)
        }
        return compileToJar(srcDir, tempDir)
    }

    private fun compileInlineSourceToDirectory(name: String, content: String): Path {
        val srcDir = Files.createDirectories(tempDir.resolve("${name}Src"))
        val srcFile = srcDir.resolve("Source.kt")
        srcFile.writeText(content)
        val outDir = Files.createDirectories(tempDir.resolve("${name}Classes"))
        MockLibraryUtil.runJvmCompiler(listOf(srcFile.absolutePathString(), "-d", outDir.absolutePathString()))
        return outDir
    }

    private class TestProtocolFileSystem(
        private val protocolName: String,
    ) : DeprecatedVirtualFileSystem() {
        override fun getProtocol(): String = protocolName
        override fun findFileByPath(path: String): VirtualFile? = null
        override fun refresh(asynchronous: Boolean) {}
        override fun refreshAndFindFileByPath(path: String): VirtualFile? = null
    }

    private class CountingVirtualFile(
        private val rawPath: String,
        private val fs: VirtualFileSystem,
        private val parentFile: VirtualFile? = null,
        private val directory: Boolean = false,
    ) : VirtualFile() {
        val parentCalls = AtomicInteger()
        val pathCalls = AtomicInteger()
        val hashCodeCalls = AtomicInteger()

        override fun getName(): String = rawPath.substringAfterLast('/')
        override fun getFileSystem(): VirtualFileSystem = fs
        override fun getPath(): String {
            pathCalls.incrementAndGet()
            return rawPath
        }
        override fun isWritable(): Boolean = false
        override fun isDirectory(): Boolean = directory
        override fun isValid(): Boolean = true
        override fun getParent(): VirtualFile? {
            parentCalls.incrementAndGet()
            return parentFile
        }
        override fun getChildren(): Array<VirtualFile> = EMPTY_ARRAY
        override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream =
            throw UnsupportedOperationException()
        override fun contentsToByteArray(): ByteArray = ByteArray(0)
        override fun getTimeStamp(): Long = 0L
        override fun getLength(): Long = 0L
        override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) {}
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun hashCode(): Int {
            hashCodeCalls.incrementAndGet()
            return rawPath.hashCode()
        }
        override fun equals(other: Any?): Boolean =
            this === other || (other is CountingVirtualFile && rawPath == other.rawPath && fs == other.fs)
    }

    /**
     * Adds a library module with the given [binaryRoot] and [libraryName] on the provider's platform.
     *
     * @param libraryScopeConstructionMode Overrides the provider-wide [KaModuleContainerBuilder.libraryScopeConstructionMode] if
     *  specified.
     */
    private fun KaModuleContainerBuilder.addLibraryModule(
        binaryRoot: Path,
        libraryName: String,
        libraryScopeConstructionMode: StandaloneLibraryScopeConstructionMode? = null,
    ): KaLibraryModule {
        val providerPlatform = platform
        return addModule(
            buildKtLibraryModule {
                addBinaryRoot(binaryRoot)
                platform = providerPlatform
                this.libraryName = libraryName
                libraryScopeConstructionMode?.let { this.libraryScopeConstructionMode = it }
            }
        )
    }

    /**
     * @property containedFiles The files which the library scope should contain.
     * @property nonContainedFiles The files which the library scope should *not* contain.
     */
    private class LibraryFiles(
        val containedFiles: Collection<VirtualFile>,
        val nonContainedFiles: Collection<VirtualFile>,
    )

    private fun collectLibraryFiles(
        includedLibraryJar: Path,
        excludedLibraryJar: Path,
        session: StandaloneAnalysisAPISession,
    ): LibraryFiles {
        val includedLibraryJarRoot = getJarRootVirtualFile(includedLibraryJar, session)
        val excludedLibraryJarRoot = getJarRootVirtualFile(excludedLibraryJar, session)

        val containedFiles = LibraryUtils.getAllVirtualFilesFromRoot(includedLibraryJarRoot, includeRoot = true)

        val nonContainedFiles = buildList {
            // All files of a different JAR, including its root. Both JARs are called `library.jar`, but reside in different directories.
            addAll(LibraryUtils.getAllVirtualFilesFromRoot(excludedLibraryJarRoot, includeRoot = true))

            // The JAR archive itself and the directory containing it, both in the local file system.
            add(getLocalVirtualFile(includedLibraryJar))
            add(getLocalVirtualFile(includedLibraryJar.parent))

            // A source file from which the library was compiled.
            add(getLocalVirtualFile(testDataPath("library").resolve("library.kt")))
        }

        Assertions.assertTrue(containedFiles.any { !it.isDirectory }, "The included library JAR should contain at least one file.")
        Assertions.assertTrue(
            nonContainedFiles.any { !it.isDirectory && it.url.startsWith(excludedLibraryJarRoot.url) },
            "The excluded library JAR should contain at least one file.",
        )

        return LibraryFiles(containedFiles, nonContainedFiles)
    }

    private fun getJarRootVirtualFile(jar: Path, session: StandaloneAnalysisAPISession): VirtualFile =
        StandaloneProjectFactory.getVirtualFilesForLibraryRoots(listOf(jar), session.coreApplicationEnvironment).single()

    private fun getLocalVirtualFile(path: Path): VirtualFile =
        StandardFileSystems.local().findFileByPath(path.toAbsolutePath().toString())
            ?: error("Cannot find a local virtual file for `$path`.")

    private fun assertLibraryScope(
        module: KaLibraryModule,
        expectedDescriptionPrefix: String,
        files: LibraryFiles,
    ) {
        val scope = module.baseContentScope
        Assertions.assertTrue(
            scope.toString().startsWith(expectedDescriptionPrefix),
            "Expected a library scope matching \"$expectedDescriptionPrefix\", but got: $scope",
        )

        for (file in files.containedFiles) {
            Assertions.assertTrue(scope.contains(file), "The scope of `${module.libraryName}` should contain `$file`.")
        }

        for (file in files.nonContainedFiles) {
            Assertions.assertFalse(scope.contains(file), "The scope of `${module.libraryName}` should not contain `$file`.")
        }
    }
}
