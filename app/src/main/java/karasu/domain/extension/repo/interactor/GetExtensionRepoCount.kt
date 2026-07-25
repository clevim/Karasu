package karasu.domain.extension.repo.interactor

import karasu.domain.extension.repo.ExtensionRepoRepository

class GetExtensionRepoCount(
    private val extensionRepoRepository: ExtensionRepoRepository
) {
    fun subscribe() = extensionRepoRepository.getCount()
}
