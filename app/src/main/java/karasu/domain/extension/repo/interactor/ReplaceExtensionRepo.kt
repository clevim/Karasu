package karasu.domain.extension.repo.interactor

import karasu.domain.extension.repo.ExtensionRepoRepository
import karasu.domain.extension.repo.model.ExtensionRepo

class ReplaceExtensionRepo(
    private val extensionRepoRepository: ExtensionRepoRepository
) {
    suspend fun await(repo: ExtensionRepo) {
        extensionRepoRepository.replaceRepository(repo)
    }
}
