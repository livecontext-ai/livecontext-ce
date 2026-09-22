/**
 * Configured values that must never be DISPLAYED, not merely never compared.
 *
 * <p>`resolved_params` is persisted and rendered, so the backend gate masks a value that
 * authenticates before it reaches the row. The configured-parameters fallback bypasses that
 * gate entirely: it reads the plan entry straight out of the canvas and renders it, which is
 * how a `crypto_jwt` node with no row came to show `Secret: <the HMAC secret>` in the Params
 * column, and an `http_request` came to show its whole `authConfig` block as JSON.
 *
 * <p>It is not a disclosure to a new principal - the same reader can open the edit form and
 * see the same values - but it is the same key answering two different ways in one panel: an
 * approval parked for two days showed its configured secrets, and the moment the signal
 * resolved the same panel showed them masked. One key, two answers, which is the defect this
 * whole alignment exists to remove.
 *
 * <p>Masked rather than dropped, with the marker the backend uses, so the reader is told the
 * parameter exists and why it is not shown.
 */
export const CONFIGURED_SECRETS: ReadonlySet<string> = new Set<string>([
  'ssh.password',
  'ssh.privateKey',
  'sftp.password',
  'sftp.privateKey',
  'database.password',
  'send_email.smtpPassword',
  // `flattenPlannedParams` flattens ONE level, so an http_request plan entry
  // yields `authConfig` as a single object key and never its members. Exempting
  // `authConfig.password` and friends would therefore match nothing; the whole
  // block is the reachable key, and it holds the four secrets, so the block is
  // what is exempted.
  'http_request.authConfig',
  'crypto_jwt.secret',
  'crypto_jwt.key',
]);

/** The same marker the backend gate writes, so both halves of the panel read alike. */
export const WITHHELD_CREDENTIAL = '<withheld: credential>';

