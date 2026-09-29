package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.web.SharedApplicationScope;
import com.apimarketplace.common.web.SharedApplicationScopeClient;

/**
 * Share-link binding for file serves by storage-row id. A share token authenticates its holder AS
 * THE OWNER, so the owner-scoped row lookup alone would serve every file of the owner's workspace.
 * Under an APPLICATION share only the shared application's files are served: a row tagged with the
 * publication itself, or produced by one of its runs / clone workflows (orchestrator decides that,
 * through {@link SharedApplicationScopeClient}). Shared by the cloud {@code FileController} and the
 * CE {@code MonolithFileController} so the two doors apply the same rule. A file a tool uploaded
 * WITHOUT run tags (catalog binary output, generated image) is found by orchestrator through the
 * application run output that references its id.
 */
public final class SharedApplicationFileScope {

    private SharedApplicationFileScope() {}

    /**
     * @param client may be null (slim unit wiring): a share request is then refused, never served
     * @return true when the row may be served under {@code scope}; always true outside a share
     */
    public static boolean permits(SharedApplicationScope scope, StorageEntity entity,
                                  String tenantId, String organizationId,
                                  SharedApplicationScopeClient client) {
        if (!scope.isShare()) {
            return true;
        }
        if (scope.kind() != SharedApplicationScope.Kind.APPLICATION || entity == null) {
            return false;
        }
        if (scope.permitsPublication(entity.getSourcePublicationId())) {
            return true;
        }
        return client != null && client.fileBelongsToApplication(
                scope.publicationId(), tenantId, organizationId, entity.getRunId(), entity.getWorkflowId(),
                entity.getId());
    }
}
