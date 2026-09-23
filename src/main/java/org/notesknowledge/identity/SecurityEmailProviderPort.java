package org.notesknowledge.identity;

/** Only an explicitly configured adapter may reach a real email provider. */
interface SecurityEmailProviderPort {
    enum Outcome { SUBMITTED, RETRYABLE, NON_RETRYABLE, AMBIGUOUS }

    Outcome submit(String recipient, SecurityEmailMessageRenderer.Message message);
}
