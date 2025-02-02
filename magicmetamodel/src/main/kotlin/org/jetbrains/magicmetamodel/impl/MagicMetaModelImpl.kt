package org.jetbrains.magicmetamodel.impl

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.TextDocumentIdentifier
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.platform.backend.workspace.BuilderSnapshot
import com.intellij.platform.backend.workspace.WorkspaceModel
import org.jetbrains.magicmetamodel.DocumentTargetsDetails
import org.jetbrains.magicmetamodel.MagicMetaModel
import org.jetbrains.magicmetamodel.MagicMetaModelDiff
import org.jetbrains.magicmetamodel.MagicMetaModelProjectConfig
import org.jetbrains.magicmetamodel.ProjectDetails
import org.jetbrains.magicmetamodel.impl.PerformanceLogger.logPerformance
import org.jetbrains.magicmetamodel.impl.workspacemodel.ModuleDetails
import org.jetbrains.magicmetamodel.impl.workspacemodel.ModuleName
import org.jetbrains.magicmetamodel.impl.workspacemodel.WorkspaceModelToProjectDetailsTransformer
import org.jetbrains.magicmetamodel.impl.workspacemodel.WorkspaceModelUpdater
import org.jetbrains.magicmetamodel.impl.workspacemodel.impl.updaters.Library

internal class DefaultMagicMetaModelDiff(
    private val workspaceModel: WorkspaceModel,
    private val builderSnapshot: BuilderSnapshot,
    private val mmmStorageReplacement: LoadedTargetsStorage,
    private val mmmInstance: MagicMetaModelImpl,
    private val targetLoadListeners: Set<() -> Unit>
) : MagicMetaModelDiff {

  override suspend fun applyOnWorkspaceModel() {
    val storageReplacement = builderSnapshot.getStorageReplacement()
    writeAction {
      if (workspaceModel.replaceProjectModel(storageReplacement)) {
        mmmInstance.loadStorage(mmmStorageReplacement)
        targetLoadListeners.forEach { it() }
      }
    }
  }
}

// TODO - get rid of *Impl - we should name it 'DefaultMagicMetaModel' or something like that
/**
 * Basic implementation of [MagicMetaModel] supporting shared sources
 * provided by the BSP and build on top of [WorkspaceModel].
 */
public class MagicMetaModelImpl : MagicMetaModel, ConvertableToState<DefaultMagicMetaModelState> {

  private val magicMetaModelProjectConfig: MagicMetaModelProjectConfig
  private val projectDetails: ProjectDetails

  private val targetsDetailsForDocumentProvider: TargetsDetailsForDocumentProvider
  private val overlappingTargetsGraph: Map<BuildTargetIdentifier, Set<BuildTargetIdentifier>>

  private val targetIdToModuleDetails: Map<BuildTargetIdentifier, ModuleDetails>

  private var loadedTargetsStorage: LoadedTargetsStorage

  private val targetLoadListeners = mutableSetOf<() -> Unit>()

  internal constructor(
    magicMetaModelProjectConfig: MagicMetaModelProjectConfig,
    projectDetails: ProjectDetails,
  ) {
    log.debug { "Initializing MagicMetaModelImpl with: $magicMetaModelProjectConfig and $projectDetails..." }

    this.magicMetaModelProjectConfig = magicMetaModelProjectConfig
    this.projectDetails = projectDetails

    this.targetsDetailsForDocumentProvider = TargetsDetailsForDocumentProvider(projectDetails.sources)
    this.overlappingTargetsGraph = OverlappingTargetsGraph(targetsDetailsForDocumentProvider)

    this.targetIdToModuleDetails =
      TargetIdToModuleDetailsMap(projectDetails, magicMetaModelProjectConfig.projectBasePath)

    this.loadedTargetsStorage = LoadedTargetsStorage(projectDetails.targetsId)

    log.debug { "Initializing MagicMetaModelImpl done!" }
  }

  internal constructor(state: DefaultMagicMetaModelState, magicMetaModelProjectConfig: MagicMetaModelProjectConfig) {
    this.magicMetaModelProjectConfig = magicMetaModelProjectConfig
    this.loadedTargetsStorage =
      LoadedTargetsStorage(state.loadedTargetsStorageState)

    this.projectDetails = state.projectDetailsState.fromState() +
      WorkspaceModelToProjectDetailsTransformer(
        magicMetaModelProjectConfig.workspaceModel,
        loadedTargetsStorage,
        magicMetaModelProjectConfig.moduleNameProvider
      )

    this.targetsDetailsForDocumentProvider =
      TargetsDetailsForDocumentProvider(state.targetsDetailsForDocumentProviderState)
    this.overlappingTargetsGraph =
      state.overlappingTargetsGraph.map { (key, value) ->
        key.fromState() to value.map { it.fromState() }.toSet()
      }.toMap()

    this.targetIdToModuleDetails =
      TargetIdToModuleDetailsMap(projectDetails, magicMetaModelProjectConfig.projectBasePath)
  }

  override fun loadDefaultTargets(): MagicMetaModelDiff {
    log.debug { "Calculating default targets to load..." }

    val nonOverlappingTargetsToLoad = logPerformance("compute-non-overlapping-targets") {
      NonOverlappingTargets(projectDetails.targets, overlappingTargetsGraph)
    }

    log.debug { "Calculating default targets to load done! Targets to load: $nonOverlappingTargetsToLoad" }

    val builderSnapshot = magicMetaModelProjectConfig.workspaceModel.getBuilderSnapshot()
    val workspaceModelUpdater = WorkspaceModelUpdater.create(
      builderSnapshot.builder,
      magicMetaModelProjectConfig.virtualFileUrlManager,
      magicMetaModelProjectConfig.projectBasePath,
      magicMetaModelProjectConfig.moduleNameProvider,
    )

    workspaceModelUpdater.clear()
    val newStorage = loadedTargetsStorage.copy()
    newStorage.clear()

    val modulesToLoad = getModulesDetailsForTargetsToLoad(nonOverlappingTargetsToLoad)

    // TODO TEST TESTS TESTS TEST
    val libraries = projectDetails.libraries
    logPerformance("load-modules") {
      workspaceModelUpdater.loadModules(modulesToLoad)
      workspaceModelUpdater.loadLibraries(libraries?.map {
        Library(
                it.id.uri,
                null,
                it.jars.firstOrNull()
        )
      }.orEmpty())
    }
    newStorage.addTargets(nonOverlappingTargetsToLoad)

    return DefaultMagicMetaModelDiff(
      workspaceModel = magicMetaModelProjectConfig.workspaceModel,
      builderSnapshot = builderSnapshot,
      mmmStorageReplacement = newStorage,
      mmmInstance = this,
      targetLoadListeners = targetLoadListeners,
    )
  }

  // TODO what if null?
  private fun getModulesDetailsForTargetsToLoad(targetsToLoad: Collection<BuildTargetIdentifier>): List<ModuleDetails> =
    targetsToLoad.map { targetIdToModuleDetails[it]!! }

  override fun registerTargetLoadListener(function: () -> Unit) {
    targetLoadListeners.add(function)
  }

  public fun copyAllTargetLoadListenersTo(other: MagicMetaModelImpl) {
    targetLoadListeners.forEach { other.registerTargetLoadListener(it) }
  }

  override fun loadTarget(targetId: BuildTargetIdentifier): MagicMetaModelDiff? = when {
    loadedTargetsStorage.isTargetNotLoaded(targetId) -> doLoadTarget(targetId)
    else -> null
  }

  // TODO ughh so ugly
  private fun doLoadTarget(targetId: BuildTargetIdentifier): DefaultMagicMetaModelDiff {
    val targetsToRemove = overlappingTargetsGraph[targetId] ?: emptySet()
    // TODO test it!
    val loadedTargetsToRemove = targetsToRemove.filter { loadedTargetsStorage.isTargetLoaded(it) }

    val modulesToRemove = loadedTargetsToRemove.map {
      ModuleName(magicMetaModelProjectConfig.moduleNameProvider(BuildTargetIdentifier(it.uri)))
    }
    val builderSnapshot = magicMetaModelProjectConfig.workspaceModel.getBuilderSnapshot()
    val workspaceModelUpdater = WorkspaceModelUpdater.create(
      builderSnapshot.builder,
      magicMetaModelProjectConfig.virtualFileUrlManager,
      magicMetaModelProjectConfig.projectBasePath,
      magicMetaModelProjectConfig.moduleNameProvider,
    )

    workspaceModelUpdater.removeModules(modulesToRemove)
    val newStorage = loadedTargetsStorage.copy()
    newStorage.removeTargets(loadedTargetsToRemove)

    // TODO null!!!
    val moduleToAdd = targetIdToModuleDetails[targetId]!!
    workspaceModelUpdater.loadModule(moduleToAdd)
    newStorage.addTarget(targetId)

    return DefaultMagicMetaModelDiff(
      workspaceModel = magicMetaModelProjectConfig.workspaceModel,
      builderSnapshot = builderSnapshot,
      mmmStorageReplacement = newStorage,
      mmmInstance = this,
      targetLoadListeners = targetLoadListeners,
    )
  }

  override fun getTargetsDetailsForDocument(documentId: TextDocumentIdentifier): DocumentTargetsDetails {
    val documentTargets = targetsDetailsForDocumentProvider.getTargetsDetailsForDocument(documentId)

    // TODO maybe wo should check is there only 1 loaded? what if 2 are loaded - it means that we have a bug
    val loadedTarget = loadedTargetsStorage.getLoadedTargets().firstOrNull { documentTargets.contains(it) }
    val notLoadedTargets = loadedTargetsStorage.getNotLoadedTargets().filter { documentTargets.contains(it) }

    return DocumentTargetsDetails(
      loadedTargetId = loadedTarget,
      notLoadedTargetsIds = notLoadedTargets,
    )
  }

  override fun getAllLoadedTargets(): List<BuildTarget> =
    projectDetails.targets.filter { loadedTargetsStorage.isTargetLoaded(it.id) }

  override fun getAllNotLoadedTargets(): List<BuildTarget> =
    projectDetails.targets.filter { loadedTargetsStorage.isTargetNotLoaded(it.id) }

  override fun clear() {
    val builderSnapshot = magicMetaModelProjectConfig.workspaceModel.getBuilderSnapshot()
    val workspaceModelUpdater = WorkspaceModelUpdater.create(
      builderSnapshot.builder,
      magicMetaModelProjectConfig.virtualFileUrlManager,
      magicMetaModelProjectConfig.projectBasePath,
      magicMetaModelProjectConfig.moduleNameProvider,
    )

    workspaceModelUpdater.clear()
    loadedTargetsStorage.clear()
  }

  // TODO - test
  override fun toState(): DefaultMagicMetaModelState =
    DefaultMagicMetaModelState(
      projectDetailsState = projectDetails.toStateWithoutLoadedTargets(loadedTargetsStorage.getLoadedTargets()),
      targetsDetailsForDocumentProviderState = targetsDetailsForDocumentProvider.toState(),
      overlappingTargetsGraph = overlappingTargetsGraph.mapKeys { it.key.toState() }
        .mapValues { it.value.map { it.toState() } },
      loadedTargetsStorageState = loadedTargetsStorage.toState()
    )

  internal fun loadStorage(storage: LoadedTargetsStorage) {
    loadedTargetsStorage = storage
  }

  private companion object {
    private val log = logger<MagicMetaModelImpl>()
  }
}

public data class LoadedTargetsStorageState(
  public var allTargets: Collection<BuildTargetIdentifierState> = emptyList(),
  public var loadedTargets: List<BuildTargetIdentifierState> = emptyList(),
  public var notLoadedTargets: List<BuildTargetIdentifierState> = emptyList(),
)

public class LoadedTargetsStorage private constructor(
  private val allTargets: Collection<BuildTargetIdentifier>,
  private val loadedTargets: MutableSet<BuildTargetIdentifier>,
  private val notLoadedTargets: MutableSet<BuildTargetIdentifier>,
) {

  public constructor(allTargets: Collection<BuildTargetIdentifier>) : this(
    allTargets = allTargets,
    loadedTargets = mutableSetOf(),
    notLoadedTargets = allTargets.toMutableSet()
  )

  public constructor(state: LoadedTargetsStorageState) : this(
    allTargets = state.allTargets.map { it.fromState() },
    loadedTargets = state.loadedTargets.map { it.fromState() }.toMutableSet(),
    notLoadedTargets = state.notLoadedTargets.map { it.fromState() }.toMutableSet(),
  )

  public fun clear() {
    loadedTargets.clear()
    notLoadedTargets.clear()
    notLoadedTargets.addAll(allTargets)
  }

  public fun addTargets(targets: Collection<BuildTargetIdentifier>) {
    loadedTargets.addAll(targets)
    notLoadedTargets.removeAll(targets.toSet())
  }

  public fun addTarget(target: BuildTargetIdentifier) {
    loadedTargets.add(target)
    notLoadedTargets.remove(target)
  }

  public fun removeTargets(targets: Collection<BuildTargetIdentifier>) {
    loadedTargets.removeAll(targets.toSet())
    notLoadedTargets.addAll(targets)
  }

  public fun isTargetNotLoaded(targetId: BuildTargetIdentifier): Boolean =
    notLoadedTargets.contains(targetId)

  public fun isTargetLoaded(targetId: BuildTargetIdentifier): Boolean =
    loadedTargets.contains(targetId)

  public fun getLoadedTargets(): List<BuildTargetIdentifier> =
    loadedTargets.toList()

  public fun getNotLoadedTargets(): List<BuildTargetIdentifier> =
    notLoadedTargets.toList()

  public fun toState(): LoadedTargetsStorageState =
    LoadedTargetsStorageState(
      allTargets.map { it.toState() },
      loadedTargets.map { it.toState() },
      notLoadedTargets.map { it.toState() })

  public fun copy(): LoadedTargetsStorage =
    LoadedTargetsStorage(
      allTargets = allTargets.toList(),
      loadedTargets = loadedTargets.toMutableSet(),
      notLoadedTargets = notLoadedTargets.toMutableSet(),
    )
}
