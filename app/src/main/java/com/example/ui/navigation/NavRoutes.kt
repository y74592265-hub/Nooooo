package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable object HomeRoute

@Serializable object AllClipsRoute

@Serializable object FavoritesRoute

@Serializable data class CollectionsRoute(val categoryId: Long? = null)

@Serializable object SearchRoute

@Serializable object IntegrationsRoute

@Serializable object BackupSyncRoute

@Serializable object SettingsRoute

@Serializable data class ClipDetailsRoute(val clipId: Long)

@Serializable object TrashRoute
