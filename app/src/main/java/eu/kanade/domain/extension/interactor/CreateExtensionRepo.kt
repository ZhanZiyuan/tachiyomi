package eu.kanade.domain.extension.interactor

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.api.ExtensionRepoUrl
import tachiyomi.core.preference.plusAssign

class CreateExtensionRepo(private val preferences: SourcePreferences) {

    fun await(name: String): Result {
        val normalized = ExtensionRepoUrl.normalize(name) ?: return Result.InvalidUrl
        preferences.extensionRepos() += normalized

        return Result.Success
    }

    sealed interface Result {
        data object InvalidUrl : Result
        data object Success : Result
    }
}
