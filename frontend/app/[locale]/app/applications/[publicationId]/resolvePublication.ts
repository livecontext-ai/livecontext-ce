import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { IS_CE } from '@/lib/edition';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

/**
 * Resolve the publication backing an application view.
 *
 * A cloud-linked CE browses CLOUD publications whose ids are absent from the
 * local catalog, so an acquired cloud app's {@link publicationService.getPublicationById}
 * 404s - which previously dropped the detail page into its error state and never
 * created a run (the installed app "did not display"). Fall back to the
 * cloud-parity remote by-id proxy (`/publications/remote/by-id/...`, the same one
 * the marketplace preview uses) so the installed app still opens. The clone itself
 * (workflowId / plan / interfaces) is local, so only this metadata read is remote.
 *
 * Cloud builds (non-CE) keep the single auth'd path: there is no remote proxy there,
 * and a genuine local miss must still surface as an error.
 */
export async function resolveApplicationPublication(
  publicationId: string,
): Promise<WorkflowPublication> {
  try {
    return await publicationService.getPublicationById(publicationId);
  } catch (err) {
    if (IS_CE) {
      const pub = await publicationService.getPublicationByIdPublic(publicationId, /* remote */ true);
      // Stamp remote=true so the detail view threads it down: the publisher id/avatar of a
      // cloud-acquired app live in the CLOUD user namespace, so PublisherAvatar (and the
      // showcase reads) must use the remote proxy (/publications/remote/users/{id}/avatar),
      // NOT the local /users/{id}/avatar - which returns a default placeholder for a cloud id.
      return { ...pub, remote: true };
    }
    throw err;
  }
}
