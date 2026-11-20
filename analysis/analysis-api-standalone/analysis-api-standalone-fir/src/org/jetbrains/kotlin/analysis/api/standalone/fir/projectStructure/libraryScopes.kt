/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.standalone.fir.projectStructure

import com.intellij.core.CoreApplicationEnvironment
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.fir.utils.LibraryUtils
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinGlobalSearchScopeMergeStrategy
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneWorkaroundApi
import org.jetbrains.kotlin.analysis.api.standalone.projectStructure.StandaloneLibraryScopeConstructionMode
import java.nio.file.Path
import kotlin.reflect.KClass

@OptIn(StandaloneWorkaroundApi::class)
@KaImplementationDetail
internal fun createLibrarySearchScope(
    binaryRoots: Collection<Path>,
    binaryVirtualFiles: Collection<VirtualFile>,
    mode: StandaloneLibraryScopeConstructionMode,
    environment: CoreApplicationEnvironment,
    project: Project,
): GlobalSearchScope =
    when (mode) {
        StandaloneLibraryScopeConstructionMode.ParentTraversal ->
            createParentTraversalLibrarySearchScope(binaryRoots, binaryVirtualFiles, environment, project)

        StandaloneLibraryScopeConstructionMode.Trie ->
            // The trie relies on on-disk paths, so fall back to enumeration when a root lacks one (e.g. in-memory).
            if (binaryVirtualFiles.any { it.toNioPathOrNull() == null }) {
                createEnumerationLibrarySearchScope(binaryRoots, binaryVirtualFiles, environment, project)
            } else {
                createTrieLibrarySearchScope(binaryRoots, binaryVirtualFiles, environment, project)
            }

        StandaloneLibraryScopeConstructionMode.Enumeration ->
            createEnumerationLibrarySearchScope(binaryRoots, binaryVirtualFiles, environment, project)
        else -> error("Unexpected library scope construction mode: $mode")
    }

internal fun createParentTraversalLibrarySearchScope(
    binaryRoots: Collection<Path>,
    binaryVirtualFiles: Collection<VirtualFile>,
    environment: CoreApplicationEnvironment,
    project: Project,
): GlobalSearchScope {
    val allRoots = when {
        binaryRoots.isEmpty() -> binaryVirtualFiles
        binaryVirtualFiles.isEmpty() -> StandaloneProjectFactory.getVirtualFilesForLibraryRoots(binaryRoots, environment)
        else -> StandaloneProjectFactory.getVirtualFilesForLibraryRoots(binaryRoots, environment) + binaryVirtualFiles
    }
    return createParentTraversalLibrarySearchScope(allRoots, project)
}

internal fun createParentTraversalLibrarySearchScope(
    roots: Collection<VirtualFile>,
    project: Project,
): GlobalSearchScope {
    if (roots.isEmpty()) return GlobalSearchScope.EMPTY_SCOPE

    if (roots.size == 1) {
        return createSingleRootParentTraversalScope(roots.first(), project)
    }

    val uniqueRoots = if (roots is Set<VirtualFile>) roots else LinkedHashSet(roots)
    if (uniqueRoots.size == 1) {
        return createSingleRootParentTraversalScope(uniqueRoots.first(), project)
    }

    return createMultiRootParentTraversalScope(uniqueRoots, project)
}

private fun createSingleRootParentTraversalScope(
    root: VirtualFile,
    project: Project,
): ParentTraversalLibrarySearchScope {
    val protocol = root.fileSystem.protocol
    return when {
        protocol == StandardFileSystems.JAR_PROTOCOL && root.parent == null ->
            SingleJarParentTraversalLibrarySearchScope(root, project)

        protocol == StandardFileSystems.JRT_PROTOCOL ->
            JrtParentTraversalLibrarySearchScope(
                matcher = SingleUniformDepthRootsMatcher(root, computeAncestorDepth(root)),
                roots = setOf(root),
                project = project,
            )

        else ->
            SingleRootParentTraversalLibrarySearchScope(root, protocol, project)
    }
}

private fun createMultiRootParentTraversalScope(
    uniqueRoots: Set<VirtualFile>,
    project: Project,
): ParentTraversalLibrarySearchScope {
    if (uniqueRoots.all { it.fileSystem.protocol == StandardFileSystems.JAR_PROTOCOL && it.parent == null }) {
        return MultiJarParentTraversalLibrarySearchScope(uniqueRoots, project)
    }

    if (uniqueRoots.all { it.fileSystem.protocol == StandardFileSystems.JRT_PROTOCOL }) {
        return JrtParentTraversalLibrarySearchScope(
            matcher = createJrtRootsMatcher(uniqueRoots),
            roots = uniqueRoots,
            project = project,
        )
    }

    var jarArchiveRoots: MutableSet<VirtualFile>? = null
    var jarNestedRoots: MutableSet<VirtualFile>? = null
    var jrtRoots: MutableSet<VirtualFile>? = null
    var fileRoots: MutableSet<VirtualFile>? = null
    var otherRootsByProtocol: MutableMap<String, MutableSet<VirtualFile>>? = null

    for (root in uniqueRoots) {
        when (val protocol = root.fileSystem.protocol) {
            StandardFileSystems.JAR_PROTOCOL -> {
                if (root.parent == null) {
                    val set = jarArchiveRoots ?: LinkedHashSet<VirtualFile>().also { jarArchiveRoots = it }
                    set.add(root)
                } else {
                    val set = jarNestedRoots ?: LinkedHashSet<VirtualFile>().also { jarNestedRoots = it }
                    set.add(root)
                }
            }

            StandardFileSystems.JRT_PROTOCOL -> {
                val set = jrtRoots ?: LinkedHashSet<VirtualFile>().also { jrtRoots = it }
                set.add(root)
            }

            StandardFileSystems.FILE_PROTOCOL -> {
                val set = fileRoots ?: LinkedHashSet<VirtualFile>().also { fileRoots = it }
                set.add(root)
            }

            else -> {
                val map = otherRootsByProtocol
                    ?: LinkedHashMap<String, MutableSet<VirtualFile>>().also { otherRootsByProtocol = it }
                map.getOrPut(protocol) { LinkedHashSet() }.add(root)
            }
        }
    }

    return CompositeParentTraversalLibrarySearchScope(
        allRoots = uniqueRoots,
        jarMatcher = createJarRootsMatcher(jarArchiveRoots, jarNestedRoots),
        jrtMatcher = jrtRoots?.let(::createJrtRootsMatcher),
        fileMatcher = fileRoots?.let(::createAncestorRootsMatcher),
        otherMatchers = otherRootsByProtocol?.mapValues { entry -> createAncestorRootsMatcher(entry.value) }.orEmpty(),
        project = project,
    )
}

/**
 * Base class for Standalone library scopes backed by [VirtualFile.getParent] traversal.
 *
 * Concrete subclasses specialize common root topologies (single JAR archive, multi-JAR archives, modular JDK `jrt://` roots, single
 * directory/nested root, and mixed-protocol composite roots) so mismatched VFS protocols fast-reject in O(1) without touching parent chains
 * or computing paths/hashes, and archive/JRT lookups avoid intermediate directory hashing.
 */
internal sealed class ParentTraversalLibrarySearchScope(
    project: Project,
) : GlobalSearchScope(project) {
    abstract fun collectRootsTo(destination: MutableCollection<VirtualFile>)

    override fun uniteWith(scope: GlobalSearchScope): GlobalSearchScope {
        if (scope === this) return this
        val currentProject = project
        if (currentProject != null && scope is ParentTraversalLibrarySearchScope && scope.project == currentProject) {
            val mergedRoots = LinkedHashSet<VirtualFile>()
            collectRootsTo(mergedRoots)
            scope.collectRootsTo(mergedRoots)
            return createParentTraversalLibrarySearchScope(mergedRoots, currentProject)
        }
        return super.uniteWith(scope)
    }

    override fun isSearchInModuleContent(aModule: Module): Boolean = false

    override fun isSearchInLibraries(): Boolean = true

    override fun toString(): String {
        val rootFiles = LinkedHashSet<VirtualFile>().also(::collectRootsTo)
        return "Parent-traversal library search scope over $rootFiles"
    }
}

/**
 * Merges multiple [ParentTraversalLibrarySearchScope]s into a single combined scope so resolution scopes over hundreds of library JARs
 * perform a single parent walk and root lookup instead of fanning out across a linear [com.intellij.psi.search.UnionScope].
 */
internal class KotlinStandaloneLibraryScopeMergeStrategy :
    KotlinGlobalSearchScopeMergeStrategy<ParentTraversalLibrarySearchScope> {
    override val targetType: KClass<ParentTraversalLibrarySearchScope> = ParentTraversalLibrarySearchScope::class

    override fun uniteScopes(
        scopes: List<ParentTraversalLibrarySearchScope>,
        project: Project,
    ): List<GlobalSearchScope> {
        if (scopes.size <= 1) return scopes

        val [matchingScopes, foreignScopes] = scopes.partition { it.project == project }
        if (matchingScopes.size <= 1) return scopes

        val mergedRoots = LinkedHashSet<VirtualFile>()
        for (scope in matchingScopes) {
            scope.collectRootsTo(mergedRoots)
        }
        val mergedScope = createParentTraversalLibrarySearchScope(mergedRoots, project)
        return if (foreignScopes.isEmpty()) {
            listOf(mergedScope)
        } else {
            buildList(foreignScopes.size + 1) {
                add(mergedScope)
                addAll(foreignScopes)
            }
        }
    }
}

/**
 * Single top-level JAR/KLIB archive root (`jar:///path/to/lib.jar!/` where `archiveRoot.parent == null`).
 */
private class SingleJarParentTraversalLibrarySearchScope(
    private val archiveRoot: VirtualFile,
    project: Project,
) : ParentTraversalLibrarySearchScope(project) {
    override fun contains(file: VirtualFile): Boolean {
        if (file.fileSystem.protocol != StandardFileSystems.JAR_PROTOCOL) return false
        return findTopmostParent(file) == archiveRoot
    }

    override fun collectRootsTo(destination: MutableCollection<VirtualFile>) {
        destination.add(archiveRoot)
    }
}

/**
 * Multiple top-level JAR/KLIB archive roots (`root.parent == null` for every root).
 */
private class MultiJarParentTraversalLibrarySearchScope(
    private val archiveRoots: Set<VirtualFile>,
    project: Project,
) : ParentTraversalLibrarySearchScope(project) {
    override fun contains(file: VirtualFile): Boolean {
        if (file.fileSystem.protocol != StandardFileSystems.JAR_PROTOCOL) return false
        return findTopmostParent(file) in archiveRoots
    }

    override fun collectRootsTo(destination: MutableCollection<VirtualFile>) {
        destination.addAll(archiveRoots)
    }
}

/**
 * Modular JDK `jrt://` roots (typically ~50–75 `/jdk!/modules/<module>` roots at uniform depth 2).
 */
private class JrtParentTraversalLibrarySearchScope(
    private val matcher: ProtocolRootsMatcher,
    private val roots: Set<VirtualFile>,
    project: Project,
) : ParentTraversalLibrarySearchScope(project) {
    override fun contains(file: VirtualFile): Boolean {
        if (file.fileSystem.protocol != StandardFileSystems.JRT_PROTOCOL) return false
        return matcher.contains(file)
    }

    override fun collectRootsTo(destination: MutableCollection<VirtualFile>) {
        destination.addAll(roots)
    }
}

/**
 * Single non-archive-JAR / non-JRT root (such as a local classes directory, unpacked KLIB directory, nested JAR directory, or custom VFS
 * root).
 */
private class SingleRootParentTraversalLibrarySearchScope(
    private val root: VirtualFile,
    private val protocol: String,
    project: Project,
) : ParentTraversalLibrarySearchScope(project) {
    override fun contains(file: VirtualFile): Boolean {
        if (file.fileSystem.protocol != protocol) return false
        return containsSingleAncestor(file, root)
    }

    override fun collectRootsTo(destination: MutableCollection<VirtualFile>) {
        destination.add(root)
    }
}

/**
 * Multi-root scope spanning mixed protocols (e.g., merged JAR + JRT + local directory resolution scopes), multiple local directory roots,
 * nested JAR roots, or custom VFS protocols.
 */
private class CompositeParentTraversalLibrarySearchScope(
    private val allRoots: Set<VirtualFile>,
    private val jarMatcher: ProtocolRootsMatcher?,
    private val jrtMatcher: ProtocolRootsMatcher?,
    private val fileMatcher: ProtocolRootsMatcher?,
    private val otherMatchers: Map<String, ProtocolRootsMatcher>,
    project: Project,
) : ParentTraversalLibrarySearchScope(project) {
    override fun contains(file: VirtualFile): Boolean {
        val matcher = when (val protocol = file.fileSystem.protocol) {
            StandardFileSystems.JAR_PROTOCOL -> jarMatcher
            StandardFileSystems.JRT_PROTOCOL -> jrtMatcher
            StandardFileSystems.FILE_PROTOCOL -> fileMatcher
            else -> otherMatchers[protocol]
        } ?: return false
        return matcher.contains(file)
    }

    override fun collectRootsTo(destination: MutableCollection<VirtualFile>) {
        destination.addAll(allRoots)
    }
}

private sealed interface ProtocolRootsMatcher {
    fun contains(file: VirtualFile): Boolean
}

private class SingleJarArchiveRootsMatcher(
    private val archiveRoot: VirtualFile,
) : ProtocolRootsMatcher {
    override fun contains(file: VirtualFile): Boolean = findTopmostParent(file) == archiveRoot
}

private class MultiJarArchiveRootsMatcher(
    private val archiveRoots: Set<VirtualFile>,
) : ProtocolRootsMatcher {
    override fun contains(file: VirtualFile): Boolean = findTopmostParent(file) in archiveRoots
}

private class MixedJarRootsMatcher(
    private val archiveRoots: Set<VirtualFile>,
    private val nestedRoots: Set<VirtualFile>,
) : ProtocolRootsMatcher {
    override fun contains(file: VirtualFile): Boolean {
        var current = file
        while (true) {
            val parent = current.parent
            if (parent == null) {
                return current in archiveRoots
            }
            if (current in nestedRoots) {
                return true
            }
            current = parent
        }
    }
}

private class SingleUniformDepthRootsMatcher(
    private val root: VirtualFile,
    private val depth: Int,
) : ProtocolRootsMatcher {
    override fun contains(file: VirtualFile): Boolean = findAncestorAtDepth(file, depth) == root
}

private class MultiUniformDepthRootsMatcher(
    private val roots: Set<VirtualFile>,
    private val depth: Int,
) : ProtocolRootsMatcher {
    override fun contains(file: VirtualFile): Boolean {
        val ancestor = findAncestorAtDepth(file, depth) ?: return false
        return ancestor in roots
    }
}

private class SingleAncestorRootsMatcher(
    private val root: VirtualFile,
) : ProtocolRootsMatcher {
    override fun contains(file: VirtualFile): Boolean = containsSingleAncestor(file, root)
}

private class AncestorSetRootsMatcher(
    private val roots: Set<VirtualFile>,
) : ProtocolRootsMatcher {
    override fun contains(file: VirtualFile): Boolean = containsAnyAncestor(file, roots)
}

private fun createJarRootsMatcher(
    archiveRoots: Set<VirtualFile>?,
    nestedRoots: Set<VirtualFile>?,
): ProtocolRootsMatcher? = when {
    archiveRoots == null && nestedRoots == null -> null
    nestedRoots == null && archiveRoots != null ->
        if (archiveRoots.size == 1) {
            SingleJarArchiveRootsMatcher(archiveRoots.first())
        } else {
            MultiJarArchiveRootsMatcher(archiveRoots)
        }

    archiveRoots == null && nestedRoots != null ->
        createAncestorRootsMatcher(nestedRoots)

    else -> MixedJarRootsMatcher(
        archiveRoots = archiveRoots.orEmpty(),
        nestedRoots = nestedRoots.orEmpty(),
    )
}

private fun createJrtRootsMatcher(jrtRoots: Set<VirtualFile>): ProtocolRootsMatcher {
    if (jrtRoots.size == 1) {
        val single = jrtRoots.first()
        return SingleUniformDepthRootsMatcher(single, computeAncestorDepth(single))
    }
    val uniformDepth = computeUniformAncestorDepth(jrtRoots)
    return if (uniformDepth != null) {
        MultiUniformDepthRootsMatcher(jrtRoots, uniformDepth)
    } else {
        AncestorSetRootsMatcher(jrtRoots)
    }
}

private fun createAncestorRootsMatcher(roots: Set<VirtualFile>): ProtocolRootsMatcher =
    if (roots.size == 1) {
        SingleAncestorRootsMatcher(roots.first())
    } else {
        AncestorSetRootsMatcher(roots)
    }

private fun findTopmostParent(file: VirtualFile): VirtualFile {
    var current = file
    while (true) {
        val parent = current.parent ?: return current
        current = parent
    }
}

private fun computeAncestorDepth(file: VirtualFile): Int {
    var depth = 0
    var current = file.parent
    while (current != null) {
        depth++
        current = current.parent
    }
    return depth
}

private fun computeUniformAncestorDepth(roots: Set<VirtualFile>): Int? {
    val iterator = roots.iterator()
    if (!iterator.hasNext()) return null
    val expectedDepth = computeAncestorDepth(iterator.next())
    while (iterator.hasNext()) {
        if (computeAncestorDepth(iterator.next()) != expectedDepth) {
            return null
        }
    }
    return expectedDepth
}

/**
 * Finds the ancestor of [file] whose distance from the VFS root (`parent == null`) equals [targetDepth], or `null` if [file] is shallower
 * than [targetDepth]. Uses a two-pointer walk so intermediate directories never compute paths or hashes.
 */
private fun findAncestorAtDepth(file: VirtualFile, targetDepth: Int): VirtualFile? {
    var lead = file
    repeat(targetDepth) {
        lead = lead.parent ?: return null
    }
    var candidate = file
    while (true) {
        val nextLead = lead.parent ?: return candidate
        lead = nextLead
        candidate = candidate.parent ?: return null
    }
}

private fun containsSingleAncestor(file: VirtualFile, targetRoot: VirtualFile): Boolean {
    var current: VirtualFile? = file
    while (current != null) {
        if (current == targetRoot) return true
        current = current.parent
    }
    return false
}

private fun containsAnyAncestor(file: VirtualFile, targetRoots: Set<VirtualFile>): Boolean {
    var current: VirtualFile? = file
    while (current != null) {
        if (current in targetRoots) return true
        current = current.parent
    }
    return false
}

internal fun createTrieLibrarySearchScope(
    binaryRoots: Collection<Path>,
    binaryVirtualFiles: Collection<VirtualFile>,
    environment: CoreApplicationEnvironment,
    project: Project,
): GlobalSearchScope {
    val virtualFiles = StandaloneProjectFactory.getVirtualFilesForLibraryRoots(binaryRoots, environment) + binaryVirtualFiles
    return TrieLibrarySearchScope(virtualFiles, project)
}

private class TrieLibrarySearchScope(
    roots: List<VirtualFile>,
    project: Project,
) : GlobalSearchScope(project) {
    private val trie: SimpleTrie = SimpleTrie(roots.map { it.path })

    override fun contains(file: VirtualFile): Boolean = trie.contains(file.path)

    override fun isSearchInModuleContent(aModule: Module): Boolean = false

    override fun isSearchInLibraries(): Boolean = true

    override fun toString(): String = "Trie-based library search scope"
}

private class SimpleTrie(paths: List<String>) {
    class TrieNode {
        var isTerminal: Boolean = false
    }

    val root = TrieNode()

    private val m = mutableMapOf<Pair<TrieNode, String>, TrieNode>().apply {
        paths.forEach { path ->
            var p = root
            for (d in path.trim('/').split('/')) {
                p = getOrPut(Pair(p, d)) { TrieNode() }
            }
            p.isTerminal = true
        }
    }

    fun contains(s: String): Boolean {
        var p = root
        for (d in s.trim('/').split('/')) {
            p = m[Pair(p, d)]?.also {
                if (it.isTerminal)
                    return true
            } ?: return false
        }
        return false
    }
}

internal fun createEnumerationLibrarySearchScope(
    binaryRoots: Collection<Path>,
    binaryVirtualFiles: Collection<VirtualFile>,
    environment: CoreApplicationEnvironment,
    project: Project,
): GlobalSearchScope {
    val virtualFileUrls = buildSet {
        for (root in StandaloneProjectFactory.getVirtualFilesForLibraryRoots(binaryRoots, environment) + binaryVirtualFiles) {
            LibraryUtils.getAllVirtualFilesFromRoot(root, includeRoot = true)
                .mapTo(this) { it.url }
        }
    }

    return EnumerationLibrarySearchScope(virtualFileUrls, project)
}

private class EnumerationLibrarySearchScope(
    private val virtualFileUrls: Set<String>,
    project: Project,
) : GlobalSearchScope(project) {
    override fun contains(file: VirtualFile): Boolean = file.url in virtualFileUrls

    override fun isSearchInModuleContent(aModule: Module): Boolean = false

    override fun isSearchInLibraries(): Boolean = true

    override fun toString(): String = "Enumeration-based library search scope over $virtualFileUrls"
}
