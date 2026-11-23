package com.example.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.R
import com.example.core.preferences.AppLanguage
import com.example.domain.model.ClipSource
import com.example.ui.components.AddEditClipSheet
import com.example.ui.components.AppLockOverlay
import com.example.ui.components.ProvideSafeTextToolbar
import com.example.ui.navigation.AllClipsRoute
import com.example.ui.navigation.BackupSyncRoute
import com.example.ui.navigation.ClipDetailsRoute
import com.example.ui.navigation.CloudRecoveryRoute
import com.example.ui.navigation.CollectionsRoute
import com.example.ui.navigation.FavoritesRoute
import com.example.ui.navigation.HomeRoute
import com.example.ui.navigation.IntegrationsRoute
import com.example.ui.navigation.SearchRoute
import com.example.ui.navigation.SettingsRoute
import com.example.ui.navigation.StorageCapacityRoute
import com.example.ui.navigation.TrashRoute
import com.example.ui.screens.AllClipsScreen
import com.example.ui.screens.BackupSyncScreen
import com.example.ui.screens.ClipDetailsScreen
import com.example.ui.screens.CollectionsScreen
import com.example.ui.screens.FavoritesScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.IntegrationsScreen
import com.example.ui.screens.RecoveryCenterScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StorageCapacityScreen
import com.example.ui.screens.TrashScreen
import com.example.ui.theme.SmartClipboardTheme
import com.example.ui.viewmodel.ClipboardViewModel
import com.example.ui.viewmodel.RecoveryViewModel
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.SyncIntegrationsViewModel
import java.util.Locale

private data class TopNavItem(
  val route: Any,
  val labelRes: Int,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartClipboardRoot(
  sharedIncomingText: String?,
  onSharedTextConsumed: () -> Unit,
  isLocked: Boolean,
  lockErrorMessage: String?,
  onRequestUnlock: () -> Unit,
  clipboardViewModel: ClipboardViewModel = hiltViewModel(),
  syncViewModel: SyncIntegrationsViewModel = hiltViewModel(),
  settingsViewModel: SettingsViewModel = hiltViewModel(),
  recoveryViewModel: RecoveryViewModel = hiltViewModel(),
) {
  val settings by settingsViewModel.userSettings.collectAsStateWithLifecycle()
  val baseContext = LocalContext.current

  val localizedContext =
    remember(baseContext, settings.language) {
      applyLocaleContext(baseContext, settings.language)
    }
  val layoutDirection =
    when (settings.language) {
      AppLanguage.ARABIC -> LayoutDirection.Rtl
      AppLanguage.ENGLISH -> LayoutDirection.Ltr
      AppLanguage.SYSTEM ->
        if (
          localizedContext.resources.configuration.layoutDirection ==
            android.view.View.LAYOUT_DIRECTION_RTL
        ) {
          LayoutDirection.Rtl
        } else {
          LayoutDirection.Ltr
        }
    }

  val existingResultOwner = LocalActivityResultRegistryOwner.current
  val existingBackOwner = LocalOnBackPressedDispatcherOwner.current
  val resultRegistryOwner =
    remember(baseContext, existingResultOwner) {
      existingResultOwner ?: findContextOwner<ActivityResultRegistryOwner>(baseContext)
    }
  val backDispatcherOwner =
    remember(baseContext, existingBackOwner) {
      existingBackOwner ?: findContextOwner<OnBackPressedDispatcherOwner>(baseContext)
    }

  val providersList =
    remember(
      localizedContext,
      layoutDirection,
      resultRegistryOwner,
      backDispatcherOwner,
    ) {
      buildList {
        add(LocalContext provides localizedContext)
        add(LocalConfiguration provides localizedContext.resources.configuration)
        add(LocalLayoutDirection provides layoutDirection)
        if (resultRegistryOwner != null) {
          add(LocalActivityResultRegistryOwner provides resultRegistryOwner)
        }
        if (backDispatcherOwner != null) {
          add(LocalOnBackPressedDispatcherOwner provides backDispatcherOwner)
        }
      }.toTypedArray()
    }

  CompositionLocalProvider(*providersList) {
    SmartClipboardTheme(themeMode = settings.themeMode) {
      ProvideSafeTextToolbar {
        if (isLocked && settings.appLockEnabled) {
        AppLockOverlay(
          onUnlockClick = onRequestUnlock,
          errorMessage = lockErrorMessage,
          modifier = Modifier.fillMaxSize(),
        )
        return@ProvideSafeTextToolbar
      }

      val navController = rememberNavController()
      val backStackEntry by navController.currentBackStackEntryAsState()
      val currentDest = backStackEntry?.destination
      val snackbarHostState = remember { SnackbarHostState() }

      // Shared across routes & AddEditClipSheet; screen-specific flows are scoped per destination below
      val categories by clipboardViewModel.categories.collectAsStateWithLifecycle()

      var showAddSheet by remember { mutableStateOf(false) }
      var prefillContent by remember { mutableStateOf("") }
      var prefillSource by remember { mutableStateOf(ClipSource.MANUAL) }

      LaunchedEffect(sharedIncomingText) {
        if (!sharedIncomingText.isNullOrBlank()) {
          prefillContent = sharedIncomingText
          prefillSource = ClipSource.SHARE_SHEET
          showAddSheet = true
          onSharedTextConsumed()
        }
      }

      val activeLocalizedContext by rememberUpdatedState(localizedContext)

      LaunchedEffect(Unit) {
        clipboardViewModel.snackbarEvents.collect {
          snackbarHostState.showSnackbar(it.resolve(activeLocalizedContext))
        }
      }
      LaunchedEffect(Unit) {
        syncViewModel.statusMessages.collect {
          snackbarHostState.showSnackbar(it.resolve(activeLocalizedContext))
        }
      }
      LaunchedEffect(Unit) {
        settingsViewModel.events.collect {
          snackbarHostState.showSnackbar(it.resolve(activeLocalizedContext))
        }
      }
      LaunchedEffect(Unit) {
        recoveryViewModel.statusMessages.collect {
          snackbarHostState.showSnackbar(it.resolve(activeLocalizedContext))
        }
      }

      val topNavItems = remember {
        listOf(
          TopNavItem(HomeRoute, R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home, "nav_home"),
          TopNavItem(
            AllClipsRoute,
            R.string.nav_all_clips,
            Icons.Filled.ContentPaste,
            Icons.Outlined.ContentPaste,
            "nav_all_clips",
          ),
          TopNavItem(
            CollectionsRoute(),
            R.string.nav_collections,
            Icons.Filled.FolderCopy,
            Icons.Outlined.FolderCopy,
            "nav_collections",
          ),
          TopNavItem(
            SearchRoute,
            R.string.nav_search,
            Icons.Filled.Search,
            Icons.Outlined.Search,
            "nav_search",
          ),
          TopNavItem(
            BackupSyncRoute,
            R.string.nav_backup_sync,
            Icons.Filled.CloudSync,
            Icons.Outlined.CloudSync,
            "nav_backup_sync",
          ),
        )
      }

      val isTopLevel =
        currentDest?.hasRoute<HomeRoute>() == true ||
          currentDest?.hasRoute<AllClipsRoute>() == true ||
          currentDest?.hasRoute<CollectionsRoute>() == true ||
          currentDest?.hasRoute<SearchRoute>() == true ||
          currentDest?.hasRoute<BackupSyncRoute>() == true

      val screenTitle =
        when {
          currentDest?.hasRoute<HomeRoute>() == true -> stringResource(R.string.app_name)
          currentDest?.hasRoute<AllClipsRoute>() == true -> stringResource(R.string.nav_all_clips)
          currentDest?.hasRoute<FavoritesRoute>() == true -> stringResource(R.string.nav_favorites)
          currentDest?.hasRoute<CollectionsRoute>() == true -> stringResource(R.string.nav_collections)
          currentDest?.hasRoute<SearchRoute>() == true -> stringResource(R.string.nav_search)
          currentDest?.hasRoute<IntegrationsRoute>() == true -> stringResource(R.string.nav_integrations)
          currentDest?.hasRoute<BackupSyncRoute>() == true -> stringResource(R.string.nav_backup_sync)
          currentDest?.hasRoute<SettingsRoute>() == true -> stringResource(R.string.nav_settings)
          currentDest?.hasRoute<TrashRoute>() == true -> stringResource(R.string.nav_trash)
          currentDest?.hasRoute<ClipDetailsRoute>() == true -> stringResource(R.string.nav_clip_details)
          currentDest?.hasRoute<CloudRecoveryRoute>() == true -> stringResource(R.string.nav_cloud_recovery)
          currentDest?.hasRoute<StorageCapacityRoute>() == true -> stringResource(R.string.nav_storage_capacity)
          else -> stringResource(R.string.app_name)
        }

      fun navigateTopLevel(route: Any) {
        navController.navigate(route) {
          popUpTo(navController.graph.findStartDestination().id) { saveState = true }
          launchSingleTop = true
          restoreState = true
        }
      }

      BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
          contentWindowInsets = WindowInsets.safeDrawing,
          containerColor = MaterialTheme.colorScheme.background,
          snackbarHost = { SnackbarHost(snackbarHostState) },
          topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
              TopAppBar(
                title = {
                  Text(
                    text = screenTitle,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                },
                colors =
                  TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                  ),
                navigationIcon = {
                  if (!isTopLevel) {
                    IconButton(
                      onClick = { navController.popBackStack() },
                      modifier = Modifier.minimumInteractiveComponentSize().testTag("top_back_btn"),
                    ) {
                      Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                      )
                    }
                  }
                },
                actions = {
                  IconButton(
                    onClick = { navController.navigate(FavoritesRoute) },
                    modifier =
                      Modifier.minimumInteractiveComponentSize().testTag("topbar_favorites_btn"),
                  ) {
                    Icon(
                      Icons.Filled.Star,
                      contentDescription = stringResource(R.string.nav_favorites),
                    )
                  }
                  IconButton(
                    onClick = { navController.navigate(TrashRoute) },
                    modifier =
                      Modifier.minimumInteractiveComponentSize().testTag("topbar_trash_btn"),
                  ) {
                    Icon(
                      Icons.Filled.DeleteOutline,
                      contentDescription = stringResource(R.string.nav_trash),
                    )
                  }
                  IconButton(
                    onClick = { navController.navigate(SettingsRoute) },
                    modifier =
                      Modifier.minimumInteractiveComponentSize().testTag("topbar_settings_btn"),
                  ) {
                    Icon(
                      Icons.Filled.Settings,
                      contentDescription = stringResource(R.string.nav_settings),
                    )
                  }
                },
              )
            }
          },
          floatingActionButton = {
            if (isTopLevel) {
              FloatingActionButton(
                onClick = {
                  prefillContent = ""
                  prefillSource = ClipSource.MANUAL
                  showAddSheet = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_clip_fab"),
              ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add_clip))
              }
            }
          },
          bottomBar = {
            if (!useNavRail && isTopLevel) {
              Surface(
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
              ) {
                NavigationBar(
                  containerColor = MaterialTheme.colorScheme.surface,
                  tonalElevation = 0.dp,
                ) {
                  topNavItems.forEach { item ->
                    val selected =
                      when (item.route) {
                        is HomeRoute -> currentDest?.hasRoute<HomeRoute>() == true
                        is AllClipsRoute -> currentDest?.hasRoute<AllClipsRoute>() == true
                        is CollectionsRoute -> currentDest?.hasRoute<CollectionsRoute>() == true
                        is SearchRoute -> currentDest?.hasRoute<SearchRoute>() == true
                        is BackupSyncRoute -> currentDest?.hasRoute<BackupSyncRoute>() == true
                        else -> false
                      }
                    NavigationBarItem(
                      selected = selected,
                      onClick = { navigateTopLevel(item.route) },
                      icon = {
                        Icon(
                          if (selected) item.selectedIcon else item.unselectedIcon,
                          contentDescription = stringResource(item.labelRes),
                        )
                      },
                      label = {
                        Text(
                          text = stringResource(item.labelRes),
                          maxLines = 1,
                          softWrap = false,
                          overflow = TextOverflow.Ellipsis,
                          style = MaterialTheme.typography.labelMedium,
                        )
                      },
                      colors =
                        NavigationBarItemDefaults.colors(
                          selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                          selectedTextColor = MaterialTheme.colorScheme.primary,
                          indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                          unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                          unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                      modifier = Modifier.testTag(item.testTag),
                    )
                  }
                }
              }
            }
          },
        ) { innerPadding ->
          Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (useNavRail && isTopLevel) {
              NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                topNavItems.forEach { item ->
                  val selected =
                    when (item.route) {
                      is HomeRoute -> currentDest?.hasRoute<HomeRoute>() == true
                      is AllClipsRoute -> currentDest?.hasRoute<AllClipsRoute>() == true
                      is CollectionsRoute -> currentDest?.hasRoute<CollectionsRoute>() == true
                      is SearchRoute -> currentDest?.hasRoute<SearchRoute>() == true
                      is BackupSyncRoute -> currentDest?.hasRoute<BackupSyncRoute>() == true
                      else -> false
                    }
                  NavigationRailItem(
                    selected = selected,
                    onClick = { navigateTopLevel(item.route) },
                    icon = {
                      Icon(
                        if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = stringResource(item.labelRes),
                      )
                    },
                    label = {
                      Text(
                        text = stringResource(item.labelRes),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                      )
                    },
                    modifier = Modifier.testTag(item.testTag),
                  )
                }
              }
              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
              NavHost(
                navController = navController,
                startDestination = HomeRoute,
                enterTransition = { fadeIn(animationSpec = tween(180)) },
                exitTransition = { fadeOut(animationSpec = tween(150)) },
              ) {
                composable<HomeRoute> {
                  val pagedClips by clipboardViewModel.pagedActiveClips.collectAsStateWithLifecycle()
                  val totalCount by clipboardViewModel.totalActiveClipCount.collectAsStateWithLifecycle()
                  val autoCapturedBanner by
                    clipboardViewModel.lastAutoCapturedBanner.collectAsStateWithLifecycle()
                  val storageStats by clipboardViewModel.storageStats.collectAsStateWithLifecycle()
                  HomeScreen(
                    clips = pagedClips,
                    totalActiveCount = totalCount,
                    categories = categories,
                    autoCapturedBanner = autoCapturedBanner,
                    storageStats = storageStats,
                    onCaptureClipboardNow = {
                      val pasted = clipboardViewModel.readSystemClipboardNow()
                      prefillContent = pasted.orEmpty()
                      prefillSource = ClipSource.CLIPBOARD
                      showAddSheet = true
                    },
                    onUndoAutoCaptured = clipboardViewModel::undoAutoCapturedClip,
                    onMarkBannerSensitive = clipboardViewModel::markBannerClipSensitive,
                    onDismissAutoCapturedBanner = clipboardViewModel::dismissAutoCapturedBanner,
                    onAddNewClip = {
                      prefillContent = ""
                      prefillSource = ClipSource.MANUAL
                      showAddSheet = true
                    },
                    onOpenClipDetails = { id -> navController.navigate(ClipDetailsRoute(id)) },
                    onCopyClip = clipboardViewModel::copyClip,
                    onTogglePin = clipboardViewModel::togglePin,
                    onToggleFavorite = clipboardViewModel::toggleFavorite,
                    onOpenFavorites = { navController.navigate(FavoritesRoute) },
                    onOpenIntegrations = { navController.navigate(IntegrationsRoute) },
                    onOpenRecovery = { navController.navigate(CloudRecoveryRoute) },
                    onOpenStorage = { navController.navigate(StorageCapacityRoute) },
                    onOpenTrash = { navController.navigate(TrashRoute) },
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                    onOpenBackupSync = { navigateTopLevel(BackupSyncRoute) },
                  )
                }

                composable<AllClipsRoute> {
                  val pagedClips by clipboardViewModel.pagedActiveClips.collectAsStateWithLifecycle()
                  val totalCount by clipboardViewModel.totalActiveClipCount.collectAsStateWithLifecycle()
                  val selectedIds by clipboardViewModel.selectedClipIds.collectAsStateWithLifecycle()
                  val storageStats by clipboardViewModel.storageStats.collectAsStateWithLifecycle()
                  AllClipsScreen(
                    clips = pagedClips,
                    totalActiveCount = totalCount,
                    categories = categories,
                    selectedClipIds = selectedIds,
                    storageStats = storageStats,
                    onLoadMore = clipboardViewModel::loadMorePagedClips,
                    onOpenBackupSync = { navigateTopLevel(BackupSyncRoute) },
                    onOpenStorage = { navController.navigate(StorageCapacityRoute) },
                    onToggleSelection = clipboardViewModel::toggleSelection,
                    onSelectAll = clipboardViewModel::selectAll,
                    onClearSelection = clipboardViewModel::clearSelection,
                    onBulkDelete = clipboardViewModel::moveToTrash,
                    onBulkMoveCategory = clipboardViewModel::moveSelectedToCategory,
                    onOpenClipDetails = { id -> navController.navigate(ClipDetailsRoute(id)) },
                    onCopyClip = clipboardViewModel::copyClip,
                    onTogglePin = clipboardViewModel::togglePin,
                    onToggleFavorite = clipboardViewModel::toggleFavorite,
                    onAddNewClip = {
                      prefillContent = ""
                      prefillSource = ClipSource.MANUAL
                      showAddSheet = true
                    },
                  )
                }

                composable<FavoritesRoute> {
                  val favoriteClips by clipboardViewModel.favoriteClips.collectAsStateWithLifecycle()
                  FavoritesScreen(
                    favorites = favoriteClips,
                    categories = categories,
                    onOpenClipDetails = { id -> navController.navigate(ClipDetailsRoute(id)) },
                    onCopyClip = clipboardViewModel::copyClip,
                    onTogglePin = clipboardViewModel::togglePin,
                    onToggleFavorite = clipboardViewModel::toggleFavorite,
                  )
                }

                composable<CollectionsRoute> { entry ->
                  val route = entry.toRoute<CollectionsRoute>()
                  val activeClips by clipboardViewModel.activeClips.collectAsStateWithLifecycle()
                  CollectionsScreen(
                    categories = categories,
                    clips = activeClips,
                    initialCategoryId = route.categoryId,
                    onCreateCategory = clipboardViewModel::createCategory,
                    onRenameCategory = clipboardViewModel::renameCategory,
                    onDeleteCategory = clipboardViewModel::deleteCustomCategory,
                    onOpenClipDetails = { id -> navController.navigate(ClipDetailsRoute(id)) },
                    onCopyClip = clipboardViewModel::copyClip,
                    onTogglePin = clipboardViewModel::togglePin,
                    onToggleFavorite = clipboardViewModel::toggleFavorite,
                  )
                }

                composable<SearchRoute> {
                  val searchFilter by clipboardViewModel.searchFilter.collectAsStateWithLifecycle()
                  val searchResults by clipboardViewModel.searchResults.collectAsStateWithLifecycle()
                  SearchScreen(
                    filter = searchFilter,
                    results = searchResults,
                    categories = categories,
                    onQueryChange = clipboardViewModel::updateSearchQuery,
                    onCategoryChange = clipboardViewModel::updateSearchCategory,
                    onTypeChange = clipboardViewModel::updateSearchType,
                    onDateFilterChange = clipboardViewModel::updateSearchDateFilter,
                    onSortOrderChange = clipboardViewModel::updateSearchSortOrder,
                    onOpenClipDetails = { id -> navController.navigate(ClipDetailsRoute(id)) },
                    onCopyClip = clipboardViewModel::copyClip,
                    onTogglePin = clipboardViewModel::togglePin,
                    onToggleFavorite = clipboardViewModel::toggleFavorite,
                  )
                }

                composable<IntegrationsRoute> {
                  val telegramConfig by syncViewModel.telegramConfig.collectAsStateWithLifecycle()
                  val discordConfig by syncViewModel.discordConfig.collectAsStateWithLifecycle()
                  val discordBotName by
                    syncViewModel.discordVerifiedBotName.collectAsStateWithLifecycle()
                  val discordGuilds by syncViewModel.discordGuilds.collectAsStateWithLifecycle()
                  val driveConfig by syncViewModel.googleDriveConfig.collectAsStateWithLifecycle()
                  val driveFolders by syncViewModel.driveFolders.collectAsStateWithLifecycle()
                  val syncBusy by syncViewModel.isBusy.collectAsStateWithLifecycle()
                  IntegrationsScreen(
                    viewModel = syncViewModel,
                    telegramConfig = telegramConfig,
                    discordConfig = discordConfig,
                    discordVerifiedBotName = discordBotName,
                    discordGuilds = discordGuilds,
                    driveConfig = driveConfig,
                    driveFolders = driveFolders,
                    isBusy = syncBusy,
                  )
                }

                composable<BackupSyncRoute> {
                  val telegramConfig by syncViewModel.telegramConfig.collectAsStateWithLifecycle()
                  val discordConfig by syncViewModel.discordConfig.collectAsStateWithLifecycle()
                  val driveConfig by syncViewModel.googleDriveConfig.collectAsStateWithLifecycle()
                  val driveBackups by syncViewModel.driveBackups.collectAsStateWithLifecycle()
                  val syncState by syncViewModel.syncState.collectAsStateWithLifecycle()
                  val queueSummary by syncViewModel.queueSummary.collectAsStateWithLifecycle()
                  val syncLogs by syncViewModel.syncLogs.collectAsStateWithLifecycle()
                  val lastGlobalSyncAt by syncViewModel.lastGlobalSyncAt.collectAsStateWithLifecycle()
                  val syncBusy by syncViewModel.isBusy.collectAsStateWithLifecycle()
                  BackupSyncScreen(
                    viewModel = syncViewModel,
                    syncState = syncState,
                    queueSummary = queueSummary,
                    syncLogs = syncLogs,
                    userSettings = settings,
                    telegramConfig = telegramConfig,
                    discordConfig = discordConfig,
                    driveConfig = driveConfig,
                    driveBackups = driveBackups,
                    lastGlobalSyncAt = lastGlobalSyncAt,
                    isBusy = syncBusy,
                    onOpenIntegrations = { navController.navigate(IntegrationsRoute) },
                    onOpenRecoveryCenter = { navController.navigate(CloudRecoveryRoute) },
                    onOpenStorageManager = { navController.navigate(StorageCapacityRoute) },
                  )
                }

                composable<CloudRecoveryRoute> {
                  val healthSummaries by recoveryViewModel.healthSummaries.collectAsStateWithLifecycle()
                  val scanResult by recoveryViewModel.scanResult.collectAsStateWithLifecycle()
                  val selectedRemoteIds by
                    recoveryViewModel.selectedRemoteIds.collectAsStateWithLifecycle()
                  val conflictResolutions by
                    recoveryViewModel.conflictResolutions.collectAsStateWithLifecycle()
                  val recoveryBusy by recoveryViewModel.isBusy.collectAsStateWithLifecycle()
                  RecoveryCenterScreen(
                    healthSummaries = healthSummaries,
                    scanResult = scanResult,
                    selectedRemoteIds = selectedRemoteIds,
                    conflictResolutions = conflictResolutions,
                    isBusy = recoveryBusy,
                    onScanAll = { recoveryViewModel.scanProviders() },
                    onToggleSelect = recoveryViewModel::toggleItemSelection,
                    onSelectMissingOnly = recoveryViewModel::selectAllMissing,
                    onSetConflictResolution = recoveryViewModel::setConflictResolution,
                    onRestoreSelected = recoveryViewModel::restoreSelected,
                    onRestoreAllMissing = recoveryViewModel::restoreAllMissing,
                  )
                }

                composable<StorageCapacityRoute> {
                  val storageStats by clipboardViewModel.storageStats.collectAsStateWithLifecycle()
                  LaunchedEffect(
                    settings.storageSoftQuotaMb,
                    settings.storageWarnThreshold1,
                    settings.storageWarnThreshold2,
                    settings.storageWarnThreshold3,
                  ) {
                    clipboardViewModel.refreshStorageStats()
                  }
                  StorageCapacityScreen(
                    stats = storageStats,
                    settings = settings,
                    onUpdateQuotaMb = settingsViewModel::setStorageSoftQuotaMb,
                    onUpdateThresholds = settingsViewModel::setStorageWarnThresholds,
                    onDeleteSelected = clipboardViewModel::deleteClipsFromStorage,
                    onEmptyTrash = clipboardViewModel::emptyTrash,
                    onBackupNow = { navigateTopLevel(BackupSyncRoute) },
                  )
                }

                composable<SettingsRoute> {
                  SettingsScreen(
                    settings = settings,
                    onThemeChange = settingsViewModel::setThemeMode,
                    onLanguageChange = settingsViewModel::setLanguage,
                    onAppLockToggle = settingsViewModel::setAppLockEnabled,
                    onAutoCaptureToggle = settingsViewModel::setAutoCapture,
                    onRetentionDaysChange = settingsViewModel::setRetentionDays,
                    onClearSystemClipboard = settingsViewModel::clearSystemClipboard,
                    onWipeAllData = settingsViewModel::wipeAllData,
                    onInstantAutoSaveToggle = settingsViewModel::setInstantAutoSave,
                    onAutoDetectSensitiveToggle =
                      settingsViewModel::setAutoDetectSensitivePatterns,
                    onBlockSensitiveSyncToggle = settingsViewModel::setBlockSensitiveCloudSync,
                    onSkipAutoSaveSensitiveToggle = settingsViewModel::setSkipAutoSaveSensitive,
                    onSaveExcludedAppsCsv = settingsViewModel::setExcludedSourceAppsCsv,
                    onDeleteCloudToggle = settingsViewModel::setDeleteCloudOnLocalDelete,
                    onOpenStorageManager = { navController.navigate(StorageCapacityRoute) },
                  )
                }

                composable<TrashRoute> {
                  val trashedClips by clipboardViewModel.trashedClips.collectAsStateWithLifecycle()
                  TrashScreen(
                    trashedClips = trashedClips,
                    onRestore = clipboardViewModel::restoreFromTrash,
                    onDeleteForever = clipboardViewModel::deleteForever,
                    onEmptyTrash = clipboardViewModel::emptyTrash,
                  )
                }

                composable<ClipDetailsRoute> { entry ->
                  val route = entry.toRoute<ClipDetailsRoute>()
                  val clip by
                    clipboardViewModel
                      .observeClip(route.clipId)
                      .collectAsStateWithLifecycle(initialValue = null)
                  ClipDetailsScreen(
                    clip = clip,
                    categories = categories,
                    onCopy = clipboardViewModel::copyClip,
                    onUpdateClip = clipboardViewModel::updateClip,
                    onTogglePin = clipboardViewModel::togglePin,
                    onToggleFavorite = clipboardViewModel::toggleFavorite,
                    onDelete = { id ->
                      clipboardViewModel.moveToTrash(listOf(id))
                      navController.popBackStack()
                    },
                    onPushToProvider = clipboardViewModel::pushSingleClipToProvider,
                    onPasteSystemClipboard = clipboardViewModel::readSystemClipboardNow,
                  )
                }
              }
            }
          }
        }
      }

      if (showAddSheet) {
        AddEditClipSheet(
          categories = categories,
          initialContent = prefillContent,
          onPasteSystemClipboard = clipboardViewModel::readSystemClipboardNow,
          onDismiss = { showAddSheet = false },
          onSave = { title, content, catId, tags, isSens, type ->
            showAddSheet = false
            clipboardViewModel.saveClip(
              title = title,
              content = content,
              categoryId = catId,
              tags = tags,
              isSensitive = isSens,
              contentType = type,
              source = prefillSource,
            )
          },
        )
      }
      }
    }
  }
}

private fun applyLocaleContext(base: Context, language: AppLanguage): Context {
  if (language == AppLanguage.SYSTEM) return base
  val locale = Locale.forLanguageTag(language.tag)
  Locale.setDefault(locale)
  val config = Configuration(base.resources.configuration)
  config.setLocale(locale)
  config.setLayoutDirection(locale)
  val configContext = base.createConfigurationContext(config)
  return LocalizedContextWrapper(base, configContext.resources)
}

private class LocalizedContextWrapper(
  base: Context,
  private val localizedResources: Resources,
) : ContextWrapper(base) {
  override fun getResources(): Resources = localizedResources

  override fun getAssets(): AssetManager = localizedResources.assets
}

private inline fun <reified T> findContextOwner(context: Context): T? {
  var current: Context? = context
  while (current != null) {
    if (current is T) return current
    current = (current as? ContextWrapper)?.baseContext
  }
  return null
}
