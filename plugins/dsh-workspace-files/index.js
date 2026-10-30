/**
 * Workspace files — Host half.
 *
 * The browser half needs no route of its own: it talks to Host Remotes this
 * deployment already mounts (`session.list`, `workspaceFiles.list`,
 * `session.openWorkspacePath`, `session.workspacePathApplications`). Opening a
 * path is therefore always verified by the Host — the plugin never spawns a
 * process itself, and a path outside the composed filesystem is refused by the
 * Session Controller rather than by this package.
 *
 * This module stays deliberately empty: the Loader row exists so the bundle is
 * a regular package, and its Client half is picked up from `dsh.client`.
 */

/** Plugin identity for the loader row and diagnostics. */
export const name = '@local/dsh-workspace-files'

/**
 * Mount this row.
 * @param _ctx - Host plugin context (unused: the Host Remote assembly serves it).
 * @param _config - this row's configuration (unused).
 */
export function apply(_ctx, _config) {}
